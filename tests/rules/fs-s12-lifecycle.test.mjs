// FS-S12: the feedback lifecycle in `firestore.rules`, the file that gets deployed.
//
// What this suite has to establish:
//   * a case and an owner message are written `submitted`, then finalised to `accepted` in a
//     second commit; `accepted` is one-way, and only an accepted case is an admin's to see;
//   * both commits carry the approval generation the client captured, and a generation that is
//     no longer current -- a revoke and a reapproval in between, or an old client that sends
//     none -- is refused at create and at finalize;
//   * a withdrawal intent is an ID-only document any verified account may write, revoked and
//     pending included (M3=A). It blocks a later create, finalize or attachment under that id,
//     and it cannot delete or otherwise touch anything that is already accepted;
//   * withdrawals are rate limited by a sliding window that heals by itself;
//   * `activityRev` / `lastRelevantAt` move by exactly one, and only for an owner message
//     finalize, a screenshot, a public writer reply and a status change;
//   * `retentionState == 'purging'` blocks every relevant write.
import { after, before, beforeEach, describe, test } from 'node:test';
import {
  collection, deleteDoc, doc, getDoc, getDocs, query, serverTimestamp, setDoc, Timestamp,
  updateDoc, where, writeBatch,
} from 'firebase/firestore';
import { as, assertFails, assertSucceeds, makeM1Env, seed } from './helpers.mjs';

let env;
before(async () => { env = await makeM1Env(); });
after(async () => { await env.cleanup(); });
beforeEach(async () => {
  await env.clearFirestore();
  await seed(env);
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    for (const [caseId, ownerUid] of [['case-alice', 'alice'], ['case-bob', 'bob']]) {
      await setDoc(doc(db, 'cases', caseId), {
        ownerUid, title: `${ownerUid} case`, body: '', statusCache: 'Mottaget',
        lastStatusEventId: null, attachmentCount: 0, activeAttachmentCount: 0,
        createdAt: Timestamp.now(), updatedAt: Timestamp.now(), lastActivityAt: Timestamp.now(),
        state: 'accepted', acceptedAt: Timestamp.now(), activityRev: 0, approvalGeneration: 0,
      });
    }
  });
});

// ------------------------------------------------------------------ helpers

async function peek(path) {
  let data;
  await env.withSecurityRulesDisabled(async (ctx) => {
    const snap = await getDoc(doc(ctx.firestore(), path));
    data = snap.exists() ? snap.data() : undefined;
  });
  return data;
}

async function poke(path, data, merge = true) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), path), data, { merge });
  });
}

async function setStatus(uid, status, gen) {
  const upd = { status };
  if (gen !== undefined) upd.approvalGeneration = gen;
  await poke(`users/${uid}`, upd);
}

const newCase = (uid, over = {}) => ({
  ownerUid: uid, title: 'Knappen fungerar inte', body: 'Detaljer', statusCache: 'Mottaget',
  lastStatusEventId: null, attachmentCount: 0, activeAttachmentCount: 0,
  createdAt: serverTimestamp(), updatedAt: serverTimestamp(), lastActivityAt: serverTimestamp(),
  state: 'submitted', activityRev: 0, approvalGeneration: 0,
  ...over,
});

const newMessage = (uid, over = {}) => ({
  type: 'message', visibility: 'public', actorUid: uid, body: 'Ett till problem',
  attachmentCount: 0, createdAt: serverTimestamp(), state: 'submitted', approvalGeneration: 0,
  ...over,
});

function finalizeCase(uid, caseId, gen = 0, over = {}) {
  const db = as(env, uid).firestore();
  return updateDoc(doc(db, `cases/${caseId}`), {
    state: 'accepted', acceptedAt: serverTimestamp(), approvalGeneration: gen, ...over,
  });
}

/** The second commit of an owner message: the event to accepted, the case bumped once. */
async function finalizeMessage(uid, caseId, eventId, { gen = 0, rev, event = {}, kase = {}, omit = [] } = {}) {
  const c = await peek(`cases/${caseId}`);
  const db = as(env, uid).firestore();
  const b = writeBatch(db);
  if (!omit.includes('event')) {
    b.update(doc(db, `cases/${caseId}/events/${eventId}`), {
      state: 'accepted', acceptedAt: serverTimestamp(), approvalGeneration: gen, ...event,
    });
  }
  if (!omit.includes('case')) {
    b.update(doc(db, `cases/${caseId}`), {
      activityRev: (rev ?? c.activityRev) + 1, lastRelevantAt: serverTimestamp(),
      lastActivityAt: serverTimestamp(), activityFor: eventId, ...kase,
    });
  }
  return b.commit();
}

/** A withdrawal intent plus its rate-limit step, the way S3 will send it. */
async function withdrawalBatch(uid, targetId, { kind = 'case', caseId = targetId, extra = {}, quota = {}, omit = [] } = {}) {
  const q = await peek(`users/${uid}/quota/withdrawals`);
  const db = as(env, uid, extra.token ?? {}).firestore();
  const b = writeBatch(db);
  if (!omit.includes('withdrawal')) {
    b.set(doc(db, `users/${uid}/withdrawals/${targetId}`), {
      kind, caseId, createdAt: serverTimestamp(), ...extra.withdrawal,
    });
  }
  if (!omit.includes('quota')) {
    const ref = doc(db, `users/${uid}/quota/withdrawals`);
    if (q) {
      b.update(ref, { windowCount: q.windowCount + 1, lastWithdrawalId: targetId, ...quota });
    } else {
      b.set(ref, { windowStart: serverTimestamp(), windowCount: 1, lastWithdrawalId: targetId, ...quota });
    }
  }
  return b.commit();
}

/** A writer's public reply (or internal note), with the case bump when it should carry one. */
async function writerNote(caseId, eventId, { visibility = 'public', bump = true, kase = {} } = {}) {
  const c = await peek(`cases/${caseId}`);
  const db = as(env, 'lars').firestore();
  const b = writeBatch(db);
  b.set(doc(db, `cases/${caseId}/events/${eventId}`), {
    type: 'note', visibility, actorUid: 'lars', body: 'Svar', createdAt: serverTimestamp(),
    state: 'accepted',
  });
  if (bump) {
    b.update(doc(db, `cases/${caseId}`), {
      activityRev: c.activityRev + 1, lastRelevantAt: serverTimestamp(), activityFor: eventId,
      publicRev: (c.publicRev ?? 0) + 1, lastActivityAt: serverTimestamp(), ...kase,
    });
  }
  return b.commit();
}

async function statusBatch(caseId, eventId, to, { bump = true } = {}) {
  const c = await peek(`cases/${caseId}`);
  const db = as(env, 'lars').firestore();
  const b = writeBatch(db);
  b.set(doc(db, `cases/${caseId}/events/${eventId}`), {
    type: 'status_change', visibility: 'public', actorUid: 'lars', fromStatus: c.statusCache,
    toStatus: to, createdAt: serverTimestamp(), state: 'accepted',
  });
  const upd = { statusCache: to, lastStatusEventId: eventId, updatedAt: serverTimestamp() };
  if (bump) {
    Object.assign(upd, {
      activityRev: c.activityRev + 1, lastRelevantAt: serverTimestamp(),
      publicRev: (c.publicRev ?? 0) + 1, lastActivityAt: serverTimestamp(),
    });
  }
  b.update(doc(db, `cases/${caseId}`), upd);
  return b.commit();
}

/** An opening-text screenshot on an accepted case, bump included. */
async function attachBatch(uid, caseId, aid, { gen = 0 } = {}) {
  const c = await peek(`cases/${caseId}`);
  const q = await peek(`users/${uid}/quota/attachments`);
  const db = as(env, uid).firestore();
  const b = writeBatch(db);
  b.set(doc(db, `users/${uid}/uploads/${aid}`), { caseId, maxBytes: 1024, createdAt: serverTimestamp() });
  b.update(doc(db, `users/${uid}/quota/attachments`), {
    count: q.count + 1, bytes: q.bytes + 1024, windowCount: q.windowCount + 1, lastUploadId: aid,
  });
  b.set(doc(db, `cases/${caseId}/attachments/${aid}`), {
    ownerUid: uid, caseId, messageId: null, maxBytes: 1024, createdAt: serverTimestamp(),
    approvalGeneration: gen,
  });
  b.update(doc(db, `cases/${caseId}`), {
    activeAttachmentCount: c.activeAttachmentCount + 1, attachmentCount: c.attachmentCount + 1,
    attachmentFor: aid, lastActivityAt: serverTimestamp(),
    activityRev: c.activityRev + 1, lastRelevantAt: serverTimestamp(),
  });
  return b.commit();
}

function assert(cond, msg = 'assertion failed') {
  if (!cond) throw new Error(msg);
}

// ------------------------------------------------------------------ two-phase lifecycle

describe('a case is submitted, then accepted', () => {
  test('create submitted, finalize accepted, once', async () => {
    const db = as(env, 'alice').firestore();
    await assertSucceeds(setDoc(doc(db, 'cases/c1'), newCase('alice')));
    assert((await peek('cases/c1')).state === 'submitted');
    await assertSucceeds(finalizeCase('alice', 'c1'));
    const c = await peek('cases/c1');
    assert(c.state === 'accepted' && c.acceptedAt instanceof Timestamp);
    // A second finalize has nothing to move, and nothing may move it back.
    await assertFails(finalizeCase('alice', 'c1'));
    await assertFails(updateDoc(doc(db, 'cases/c1'), { state: 'submitted' }));
    await assertFails(deleteDoc(doc(db, 'cases/c1')));
  });

  test('a finalize is dated now and touches nothing else', async () => {
    await setDoc(doc(as(env, 'alice').firestore(), 'cases/c1'), newCase('alice'));
    await assertFails(finalizeCase('alice', 'c1', 0, { acceptedAt: Timestamp.fromMillis(0) }));
    await assertFails(finalizeCase('alice', 'c1', 0, { title: 'Annat' }));
    await assertFails(finalizeCase('alice', 'c1', 0, { activityRev: 1 }));
    await assertFails(finalizeCase('alice', 'c1', 0, { state: 'purging' }));
    await assertFails(finalizeCase('bob', 'c1'));
    await assertSucceeds(finalizeCase('alice', 'c1'));
  });

  test('nobody creates a case already accepted', async () => {
    const db = as(env, 'alice').firestore();
    await assertFails(setDoc(doc(db, 'cases/c1'), newCase('alice', { state: 'accepted' })));
    await assertFails(setDoc(doc(db, 'cases/c1'), newCase('alice', { acceptedAt: serverTimestamp() })));
    await assertFails(setDoc(doc(db, 'cases/c1'), newCase('alice', { lastRelevantAt: serverTimestamp() })));
    await assertFails(setDoc(doc(db, 'cases/c1'), newCase('alice', { withdrawnAt: serverTimestamp() })));
    await assertFails(setDoc(doc(db, 'cases/c1'), newCase('alice', { retentionState: 'active' })));
  });

  test('an old client -- no state, no generation -- is refused', async () => {
    const db = as(env, 'alice').firestore();
    const old = newCase('alice');
    delete old.state; delete old.activityRev; delete old.approvalGeneration;
    await assertFails(setDoc(doc(db, 'cases/c1'), old));
    const noGen = newCase('alice');
    delete noGen.approvalGeneration;
    await assertFails(setDoc(doc(db, 'cases/c1'), noGen));
  });

  test('a revoke between create and finalize stops the finalize', async () => {
    await setDoc(doc(as(env, 'alice').firestore(), 'cases/c1'), newCase('alice'));
    await setStatus('alice', 'revoked');
    await assertFails(finalizeCase('alice', 'c1'));
    assert((await peek('cases/c1')).state === 'submitted');
  });

  test('a submitted case is the owner\'s alone to read', async () => {
    await setDoc(doc(as(env, 'alice').firestore(), 'cases/c1'), newCase('alice'));
    await assertSucceeds(getDoc(doc(as(env, 'alice').firestore(), 'cases/c1')));
    await assertFails(getDoc(doc(as(env, 'reader').firestore(), 'cases/c1')));
    await assertFails(getDoc(doc(as(env, 'lars').firestore(), 'cases/c1')));
    await finalizeCase('alice', 'c1');
    await assertSucceeds(getDoc(doc(as(env, 'reader').firestore(), 'cases/c1')));
  });

  test('an admin list must ask for accepted cases', async () => {
    const reader = as(env, 'reader').firestore();
    await assertFails(getDocs(collection(reader, 'cases')));
    await assertFails(getDocs(query(collection(reader, 'cases'), where('state', '==', 'submitted'))));
    await assertSucceeds(getDocs(query(collection(reader, 'cases'), where('state', '==', 'accepted'))));
  });
});

describe('an owner message is submitted, then accepted', () => {
  test('create touches only the event; finalize bumps the case once', async () => {
    const db = as(env, 'alice').firestore();
    await assertSucceeds(setDoc(doc(db, 'cases/case-alice/events/m1'), newMessage('alice')));
    assert((await peek('cases/case-alice')).activityRev === 0);
    await assertSucceeds(finalizeMessage('alice', 'case-alice', 'm1'));
    const c = await peek('cases/case-alice');
    assert(c.activityRev === 1 && c.activityFor === 'm1' && c.lastRelevantAt instanceof Timestamp);
    assert((await peek('cases/case-alice/events/m1')).state === 'accepted');
    // Twice is refused, and a finalize cannot be repeated as a second bump.
    await assertFails(finalizeMessage('alice', 'case-alice', 'm1'));
    await assertFails(finalizeMessage('alice', 'case-alice', 'm1', { omit: ['event'] }));
    await assertFails(updateDoc(doc(db, 'cases/case-alice/events/m1'), { state: 'submitted' }));
  });

  test('neither half of a finalize goes alone', async () => {
    await setDoc(doc(as(env, 'alice').firestore(), 'cases/case-alice/events/m1'), newMessage('alice'));
    await assertFails(finalizeMessage('alice', 'case-alice', 'm1', { omit: ['case'] }));
    await assertFails(finalizeMessage('alice', 'case-alice', 'm1', { omit: ['event'] }));
  });

  test('hostile finalize variants are refused', async () => {
    await setDoc(doc(as(env, 'alice').firestore(), 'cases/case-alice/events/m1'), newMessage('alice'));
    for (const v of [
      { kase: { activityRev: 2 } },
      { kase: { activityFor: 'other' } },
      { kase: { lastRelevantAt: Timestamp.fromMillis(0) } },
      { kase: { withdrawnAt: serverTimestamp() } },
      { kase: { state: 'submitted' } },
      { event: { acceptedAt: Timestamp.fromMillis(0) } },
      { event: { body: 'Ändrat' } },
      { event: { approvalGeneration: 1 } },
    ]) {
      await assertFails(finalizeMessage('alice', 'case-alice', 'm1', v), JSON.stringify(v));
    }
    await assertSucceeds(finalizeMessage('alice', 'case-alice', 'm1'));
  });

  test('a message cannot be created accepted, or on a submitted case', async () => {
    const db = as(env, 'alice').firestore();
    await assertFails(setDoc(doc(db, 'cases/case-alice/events/m1'), newMessage('alice', { state: 'accepted' })));
    const old = newMessage('alice');
    delete old.state; delete old.approvalGeneration;
    await assertFails(setDoc(doc(db, 'cases/case-alice/events/m1'), old));
    await setDoc(doc(db, 'cases/c1'), newCase('alice'));
    await assertFails(setDoc(doc(db, 'cases/c1/events/m1'), newMessage('alice')));
  });

  test('two finalizes from the same revision: the second is refused', async () => {
    const db = as(env, 'alice').firestore();
    await setDoc(doc(db, 'cases/case-alice/events/m1'), newMessage('alice'));
    await setDoc(doc(db, 'cases/case-alice/events/m2'), newMessage('alice'));
    await assertSucceeds(finalizeMessage('alice', 'case-alice', 'm1', { rev: 0 }));
    await assertFails(finalizeMessage('alice', 'case-alice', 'm2', { rev: 0 }));
    await assertSucceeds(finalizeMessage('alice', 'case-alice', 'm2', { rev: 1 }));
    assert((await peek('cases/case-alice')).activityRev === 2);
  });

  test('a submitted message is not an admin\'s to read', async () => {
    await setDoc(doc(as(env, 'alice').firestore(), 'cases/case-alice/events/m1'), newMessage('alice'));
    await assertSucceeds(getDoc(doc(as(env, 'alice').firestore(), 'cases/case-alice/events/m1')));
    await assertFails(getDoc(doc(as(env, 'reader').firestore(), 'cases/case-alice/events/m1')));
    const reader = as(env, 'reader').firestore();
    await assertFails(getDocs(collection(reader, 'cases/case-alice/events')));
    await assertSucceeds(getDocs(query(
      collection(reader, 'cases/case-alice/events'), where('state', '==', 'accepted'),
    )));
  });
});

// ------------------------------------------------------------------ approval generation

describe('approvalGeneration', () => {
  test('a stale generation is refused at create and at finalize, the current one is not', async () => {
    // Approved at 1, revoked, reapproved at 2: whatever was captured at 1 is stale.
    await setStatus('alice', 'approved', 2);
    const db = as(env, 'alice').firestore();
    await assertFails(setDoc(doc(db, 'cases/c1'), newCase('alice', { approvalGeneration: 1 })));
    await assertFails(setDoc(doc(db, 'cases/case-alice/events/m1'), newMessage('alice', { approvalGeneration: 1 })));
    await assertSucceeds(setDoc(doc(db, 'cases/c1'), newCase('alice', { approvalGeneration: 2 })));
    await assertSucceeds(setDoc(doc(db, 'cases/case-alice/events/m1'), newMessage('alice', { approvalGeneration: 2 })));
    await assertSucceeds(finalizeCase('alice', 'c1', 2));
    await assertSucceeds(finalizeMessage('alice', 'case-alice', 'm1', { gen: 2 }));
  });

  test('something created under an old generation finalizes only under the current one', async () => {
    const db = as(env, 'alice').firestore();
    await setDoc(doc(db, 'cases/c1'), newCase('alice', { approvalGeneration: 0 }));
    await setDoc(doc(db, 'cases/case-alice/events/m1'), newMessage('alice', { approvalGeneration: 0 }));
    // Revoked and reapproved in between.
    await setStatus('alice', 'approved', 1);
    await assertFails(finalizeCase('alice', 'c1', 0));
    await assertFails(finalizeMessage('alice', 'case-alice', 'm1', { gen: 0 }));
    // An old client that finalizes without naming a generation leaves the stale one standing.
    await assertFails(updateDoc(doc(db, 'cases/c1'), { state: 'accepted', acceptedAt: serverTimestamp() }));
    // The explicit Send recaptures the current generation.
    await assertSucceeds(finalizeCase('alice', 'c1', 1));
    await assertSucceeds(finalizeMessage('alice', 'case-alice', 'm1', { gen: 1 }));
  });

  test('a screenshot carries the current generation too', async () => {
    await setStatus('alice', 'approved', 3);
    await assertFails(attachBatch('alice', 'case-alice', 'a1', { gen: 2 }));
    await assertSucceeds(attachBatch('alice', 'case-alice', 'a1', { gen: 3 }));
  });
});

// ------------------------------------------------------------------ withdrawals (M3=A)

describe('withdrawal intents', () => {
  test('an approved, a revoked and a pending account may each withdraw by id', async () => {
    await assertSucceeds(withdrawalBatch('alice', 'c1'));
    await setStatus('bob', 'revoked');
    await assertSucceeds(withdrawalBatch('bob', 'c2'));
    await assertSucceeds(withdrawalBatch('pat', 'c3'));
    const w = await peek('users/bob/withdrawals/c2');
    assert(Object.keys(w).sort().join() === 'caseId,createdAt,kind');
  });

  test('an unverified or unregistered account cannot, nor for somebody else', async () => {
    await assertFails(withdrawalBatch('alice', 'c1', { extra: { token: { email_verified: false } } }));
    await assertFails(withdrawalBatch('ghost', 'c1'));
    const db = as(env, 'alice').firestore();
    const b = writeBatch(db);
    b.set(doc(db, 'users/bob/withdrawals/c1'), { kind: 'case', caseId: 'c1', createdAt: serverTimestamp() });
    b.set(doc(db, 'users/bob/quota/withdrawals'), { windowStart: serverTimestamp(), windowCount: 1, lastWithdrawalId: 'c1' });
    await assertFails(b.commit());
  });

  test('a withdrawal is ID-only and content-free', async () => {
    for (const w of [
      { withdrawal: { body: 'Hemligt' } },
      { withdrawal: { title: 'x' } },
      { withdrawal: { createdAt: Timestamp.fromMillis(0) } },
      { withdrawal: { kind: 'user' } },
      { withdrawal: { caseId: '' } },
      { withdrawal: { caseId: 'x'.repeat(129) } },
      { withdrawal: { caseId: 'other' } },
    ]) {
      await assertFails(withdrawalBatch('alice', 'c1', { extra: w }), JSON.stringify(w));
    }
    await assertSucceeds(withdrawalBatch('alice', 'm1', { kind: 'message', caseId: 'case-alice' }));
  });

  test('a withdrawal needs its rate-limit step, and one step covers one withdrawal', async () => {
    await assertFails(withdrawalBatch('alice', 'c1', { omit: ['quota'] }));
    await assertFails(withdrawalBatch('alice', 'c1', { omit: ['withdrawal'] }));
    await assertFails(withdrawalBatch('alice', 'c1', { quota: { lastWithdrawalId: 'c2' } }));
    await assertSucceeds(withdrawalBatch('alice', 'c1'));
  });

  test('a withdrawal is create-only and private', async () => {
    await assertSucceeds(withdrawalBatch('alice', 'c1'));
    const db = as(env, 'alice').firestore();
    await assertFails(updateDoc(doc(db, 'users/alice/withdrawals/c1'), { kind: 'message' }));
    await assertFails(deleteDoc(doc(db, 'users/alice/withdrawals/c1')));
    await assertFails(withdrawalBatch('alice', 'c1'));
    await assertSucceeds(getDoc(doc(db, 'users/alice/withdrawals/c1')));
    await assertFails(getDoc(doc(as(env, 'bob').firestore(), 'users/alice/withdrawals/c1')));
    await assertFails(getDocs(collection(db, 'users/alice/withdrawals')));
    await assertFails(deleteDoc(doc(db, 'users/alice/quota/withdrawals')));
  });

  test('at most 20 an hour; the 21st is refused', async () => {
    await poke('users/alice/quota/withdrawals', {
      windowStart: Timestamp.now(), windowCount: 19, lastWithdrawalId: 'x',
    });
    await assertSucceeds(withdrawalBatch('alice', 'w20'));
    await assertFails(withdrawalBatch('alice', 'w21'));
    // Nor by starting a fresh window early.
    await assertFails(withdrawalBatch('alice', 'w21', { quota: { windowCount: 1, windowStart: serverTimestamp() } }));
  });

  test('the window heals by itself after an hour, with no lifetime cap', async () => {
    await poke('users/alice/quota/withdrawals', {
      windowStart: Timestamp.fromMillis(Date.now() - 2 * 3600 * 1000), windowCount: 20,
      lastWithdrawalId: 'x',
    });
    await assertSucceeds(withdrawalBatch('alice', 'w1', { quota: { windowCount: 1, windowStart: serverTimestamp() } }));
    assert((await peek('users/alice/quota/withdrawals')).windowCount === 1);
  });

  test('a writer can reset the window, and nobody else', async () => {
    await poke('users/alice/quota/withdrawals', { windowStart: Timestamp.now(), windowCount: 20, lastWithdrawalId: 'x' });
    const reset = { windowCount: 0, resetBy: 'lars', resetAt: serverTimestamp() };
    await assertFails(updateDoc(doc(as(env, 'alice').firestore(), 'users/alice/quota/withdrawals'), { ...reset, resetBy: 'alice' }));
    await assertFails(updateDoc(doc(as(env, 'reader').firestore(), 'users/alice/quota/withdrawals'), { ...reset, resetBy: 'reader' }));
    await assertSucceeds(updateDoc(doc(as(env, 'lars').firestore(), 'users/alice/quota/withdrawals'), reset));
    await assertSucceeds(withdrawalBatch('alice', 'w1'));
  });

  test('a withdrawn id can be neither created nor finalized', async () => {
    const db = as(env, 'alice').firestore();
    // Withdrawn before it was ever sent.
    await withdrawalBatch('alice', 'c1');
    await assertFails(setDoc(doc(db, 'cases/c1'), newCase('alice')));
    await withdrawalBatch('alice', 'm1', { kind: 'message', caseId: 'case-alice' });
    await assertFails(setDoc(doc(db, 'cases/case-alice/events/m1'), newMessage('alice')));
    // Withdrawn between create and finalize.
    await setDoc(doc(db, 'cases/c2'), newCase('alice'));
    await setDoc(doc(db, 'cases/case-alice/events/m2'), newMessage('alice'));
    await withdrawalBatch('alice', 'c2');
    await withdrawalBatch('alice', 'm2', { kind: 'message', caseId: 'case-alice' });
    await assertFails(finalizeCase('alice', 'c2'));
    await assertFails(finalizeMessage('alice', 'case-alice', 'm2'));
  });

  test('a withdrawn screenshot id cannot be attached', async () => {
    await withdrawalBatch('alice', 'a1', { kind: 'attachment', caseId: 'case-alice' });
    await assertFails(attachBatch('alice', 'case-alice', 'a1'));
    await assertSucceeds(attachBatch('alice', 'case-alice', 'a2'));
  });

  test('withdrawing something accepted deletes nothing and changes nothing', async () => {
    await setDoc(doc(as(env, 'alice').firestore(), 'cases/case-alice/events/m1'), newMessage('alice'));
    await finalizeMessage('alice', 'case-alice', 'm1');
    const beforeCase = await peek('cases/case-alice');
    await assertSucceeds(withdrawalBatch('alice', 'case-alice'));
    await assertSucceeds(withdrawalBatch('alice', 'm1', { kind: 'message', caseId: 'case-alice' }));
    const afterCase = await peek('cases/case-alice');
    assert(afterCase.state === 'accepted' && !('withdrawnAt' in afterCase));
    assert(afterCase.activityRev === beforeCase.activityRev);
    assert((await peek('cases/case-alice/events/m1')).state === 'accepted');
    // And no client path turns the intent into a deletion.
    await setStatus('alice', 'revoked');
    const db = as(env, 'alice').firestore();
    await assertFails(deleteDoc(doc(db, 'cases/case-alice')));
    await assertFails(deleteDoc(doc(db, 'cases/case-alice/events/m1')));
    await assertFails(updateDoc(doc(db, 'cases/case-alice'), { withdrawnAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(db, 'cases/case-alice'), { state: 'submitted' }));
    await assertFails(updateDoc(doc(db, 'cases/case-alice/events/m1'), { state: 'submitted' }));
    for (const who of ['lars', 'alice']) {
      await assertFails(deleteDoc(doc(as(env, who).firestore(), 'cases/case-alice')));
    }
  });

  test('a revoked or pending account still cannot write anything but an intent', async () => {
    await setStatus('alice', 'revoked');
    const db = as(env, 'alice').firestore();
    await assertFails(setDoc(doc(db, 'cases/c1'), newCase('alice')));
    await assertFails(setDoc(doc(db, 'cases/case-alice/events/m1'), newMessage('alice')));
    await assertFails(attachBatch('alice', 'case-alice', 'a1'));
    await assertFails(setDoc(doc(as(env, 'pat').firestore(), 'cases/c2'), newCase('pat')));
  });
});

// ------------------------------------------------------------------ activity bump

describe('what moves activityRev, and what does not', () => {
  test('a public writer reply bumps, bound to that reply', async () => {
    await assertSucceeds(writerNote('case-alice', 'r1'));
    const c = await peek('cases/case-alice');
    assert(c.activityRev === 1 && c.activityFor === 'r1');
  });

  test('a public writer reply may also be written without a bump only if... it may not', async () => {
    await assertFails(writerNote('case-alice', 'r1', { bump: false }));
  });

  test('an internal note does not bump, and cannot be made to', async () => {
    await assertFails(writerNote('case-alice', 'n1', { visibility: 'internal' }));
    await assertSucceeds(writerNote('case-alice', 'n1', { visibility: 'internal', bump: false }));
    assert((await peek('cases/case-alice')).activityRev === 0);
  });

  test('a bump without its event, or by two, is refused', async () => {
    const db = as(env, 'lars').firestore();
    await assertFails(updateDoc(doc(db, 'cases/case-alice'), {
      activityRev: 1, lastRelevantAt: serverTimestamp(), activityFor: 'ghost',
    }));
    await assertFails(writerNote('case-alice', 'r1', { kase: { activityRev: 2 } }));
    await assertFails(writerNote('case-alice', 'r1', { kase: { lastRelevantAt: Timestamp.fromMillis(0) } }));
    await assertSucceeds(writerNote('case-alice', 'r1'));
    // An existing event cannot be named for a second bump.
    await assertFails(updateDoc(doc(db, 'cases/case-alice'), {
      activityRev: 2, lastRelevantAt: serverTimestamp(), activityFor: 'r1',
    }));
  });

  test('the owner cannot bump on its own', async () => {
    const db = as(env, 'alice').firestore();
    await assertFails(updateDoc(doc(db, 'cases/case-alice'), {
      activityRev: 1, lastRelevantAt: serverTimestamp(),
    }));
    await assertFails(updateDoc(doc(db, 'cases/case-alice'), { lastActivityAt: serverTimestamp() }));
  });

  test('a status change bumps; without the bump it is refused', async () => {
    await assertFails(statusBatch('case-alice', 's1', 'Under granskning', { bump: false }));
    await assertSucceeds(statusBatch('case-alice', 's1', 'Under granskning'));
    assert((await peek('cases/case-alice')).activityRev === 1);
  });

  test('a screenshot bumps; closing and a tombstone do not', async () => {
    await assertSucceeds(attachBatch('alice', 'case-alice', 'a1'));
    assert((await peek('cases/case-alice')).activityRev === 1);
    const db = as(env, 'alice').firestore();
    const b = writeBatch(db);
    b.update(doc(db, 'cases/case-alice/attachments/a1'), { deleteRequestedAt: serverTimestamp() });
    b.update(doc(db, 'cases/case-alice'), { activeAttachmentCount: 0, attachmentFor: 'a1' });
    await assertSucceeds(b.commit());
    await assertSucceeds(updateDoc(doc(as(env, 'lars').firestore(), 'cases/case-alice'), {
      closedAt: serverTimestamp(),
    }));
    await assertFails(updateDoc(doc(as(env, 'lars').firestore(), 'cases/case-bob'), {
      closedAt: serverTimestamp(), activityRev: 1, lastRelevantAt: serverTimestamp(),
    }));
    assert((await peek('cases/case-alice')).activityRev === 1);
  });

  test('nobody writes retention or withdrawal fields from a client', async () => {
    for (const who of ['alice', 'lars']) {
      const db = as(env, who).firestore();
      for (const f of [{ withdrawnAt: serverTimestamp() }, { retentionState: 'purging' }, { lastRelevantAt: serverTimestamp() }]) {
        await assertFails(updateDoc(doc(db, 'cases/case-alice'), f), `${who} ${JSON.stringify(f)}`);
      }
    }
  });
});

// ------------------------------------------------------------------ purging and withdrawn

describe('a case being purged, or withdrawn, takes no relevant write', () => {
  for (const [label, mark] of [
    ['purging', { retentionState: 'purging' }],
    ['withdrawn', { withdrawnAt: Timestamp.now() }],
  ]) {
    test(`${label}: no message, finalize, screenshot, reply or status`, async () => {
      const db = as(env, 'alice').firestore();
      await setDoc(doc(db, 'cases/case-alice/events/m1'), newMessage('alice'));
      await setDoc(doc(db, 'cases/c1'), newCase('alice'));
      await poke('cases/case-alice', mark);
      await poke('cases/c1', mark);
      await assertFails(setDoc(doc(db, 'cases/case-alice/events/m2'), newMessage('alice')));
      await assertFails(finalizeMessage('alice', 'case-alice', 'm1'));
      await assertFails(finalizeCase('alice', 'c1'));
      await assertFails(attachBatch('alice', 'case-alice', 'a1'));
      await assertFails(writerNote('case-alice', 'r1'));
      await assertFails(statusBatch('case-alice', 's1', 'Under granskning'));
    });
  }
});

describe('children need an accepted parent', () => {
  test('no screenshot on a submitted case, none on a submitted message', async () => {
    const db = as(env, 'alice').firestore();
    await setDoc(doc(db, 'cases/c1'), newCase('alice'));
    await assertFails(attachBatch('alice', 'c1', 'a1'));
    // A message that is still submitted takes no screenshot either.
    await setDoc(doc(db, 'cases/case-alice/events/m1'), newMessage('alice'));
    const c = await peek('cases/case-alice');
    const q = await peek('users/alice/quota/attachments');
    const b = writeBatch(db);
    b.set(doc(db, 'users/alice/uploads/a2'), { caseId: 'case-alice', maxBytes: 1024, createdAt: serverTimestamp() });
    b.update(doc(db, 'users/alice/quota/attachments'), {
      count: q.count + 1, bytes: q.bytes + 1024, windowCount: q.windowCount + 1, lastUploadId: 'a2',
    });
    b.set(doc(db, 'cases/case-alice/attachments/a2'), {
      ownerUid: 'alice', caseId: 'case-alice', messageId: 'm1', maxBytes: 1024,
      createdAt: serverTimestamp(), approvalGeneration: 0,
    });
    b.update(doc(db, 'cases/case-alice'), {
      activeAttachmentCount: c.activeAttachmentCount + 1, attachmentFor: 'a2',
      lastActivityAt: serverTimestamp(), activityRev: c.activityRev + 1, lastRelevantAt: serverTimestamp(),
    });
    b.update(doc(db, 'cases/case-alice/events/m1'), { attachmentCount: 1, attachmentFor: 'a2' });
    await assertFails(b.commit());
  });

  test('no writer reply or status on a submitted case', async () => {
    await setDoc(doc(as(env, 'alice').firestore(), 'cases/c1'), newCase('alice'));
    await assertFails(writerNote('c1', 'r1'));
    await assertFails(writerNote('c1', 'n1', { visibility: 'internal', bump: false }));
    await assertFails(statusBatch('c1', 's1', 'Under granskning'));
  });
});
