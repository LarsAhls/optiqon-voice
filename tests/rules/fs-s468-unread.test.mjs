// FS-S468: unread state and notification tokens, through the deployed rules.
//
//   * `publicRev` moves by exactly one with a writer's public reply or status change, never
//     with an owner message, a screenshot, an internal note or a close;
//   * the owner's read marker is its own, only forward, never past what exists, and closed to
//     a revoked or pending account (no side channel into whether support answered);
//   * a notification token is registered by an approved account for itself only, removable by
//     its signed-in owner whatever its status, and listable by nobody.
import { after, before, beforeEach, describe, test } from 'node:test';
import {
  collection, deleteDoc, doc, getDoc, getDocs, serverTimestamp, setDoc, Timestamp, updateDoc,
  writeBatch,
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
      await setDoc(doc(db, `cases/${caseId}`), {
        ownerUid, title: 't', body: '', statusCache: 'Mottaget', lastStatusEventId: null,
        attachmentCount: 0, activeAttachmentCount: 0, createdAt: Timestamp.now(), updatedAt: Timestamp.now(),
        lastActivityAt: Timestamp.now(), state: 'accepted', acceptedAt: Timestamp.now(), activityRev: 0,
        approvalGeneration: 0,
      });
    }
    await setDoc(doc(db, 'cases/case-sub'), {
      ownerUid: 'alice', title: 't', body: '', statusCache: 'Mottaget', lastStatusEventId: null,
      attachmentCount: 0, activeAttachmentCount: 0, createdAt: Timestamp.now(), updatedAt: Timestamp.now(),
      lastActivityAt: Timestamp.now(), state: 'submitted', activityRev: 0, approvalGeneration: 0,
    });
  });
});

async function peek(path) {
  let out;
  await env.withSecurityRulesDisabled(async (ctx) => {
    const s = await getDoc(doc(ctx.firestore(), path));
    out = s.exists() ? s.data() : undefined;
  });
  return out;
}

async function setStatus(uid, status) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await updateDoc(doc(ctx.firestore(), `users/${uid}`), { status });
  });
}

async function reply(caseId, eventId, { publicRev, withActivity = true } = {}) {
  const c = await peek(`cases/${caseId}`);
  const db = as(env, 'lars').firestore();
  const b = writeBatch(db);
  b.set(doc(db, `cases/${caseId}/events/${eventId}`), {
    type: 'note', visibility: 'public', actorUid: 'lars', body: 'Svar', createdAt: serverTimestamp(),
    state: 'accepted',
  });
  const upd = { activityRev: c.activityRev + 1, lastRelevantAt: serverTimestamp(), activityFor: eventId };
  if (publicRev !== null) upd.publicRev = publicRev ?? (c.publicRev ?? 0) + 1;
  if (withActivity) upd.lastActivityAt = serverTimestamp();
  b.update(doc(db, `cases/${caseId}`), upd);
  return b.commit();
}

async function status(caseId, eventId, to, { publicRev } = {}) {
  const c = await peek(`cases/${caseId}`);
  const db = as(env, 'lars').firestore();
  const b = writeBatch(db);
  b.set(doc(db, `cases/${caseId}/events/${eventId}`), {
    type: 'status_change', visibility: 'public', actorUid: 'lars', fromStatus: c.statusCache,
    toStatus: to, createdAt: serverTimestamp(), state: 'accepted',
  });
  const upd = {
    statusCache: to, lastStatusEventId: eventId, updatedAt: serverTimestamp(),
    activityRev: c.activityRev + 1, lastRelevantAt: serverTimestamp(), lastActivityAt: serverTimestamp(),
  };
  if (publicRev !== null) upd.publicRev = publicRev ?? (c.publicRev ?? 0) + 1;
  b.update(doc(db, `cases/${caseId}`), upd);
  return b.commit();
}

const mark = (uid, caseId, seenPublicRev, extra = {}) =>
  setDoc(doc(as(env, uid).firestore(), `users/${uid}/caseReads/${caseId}`),
    { seenPublicRev, readAt: serverTimestamp(), ...extra });

function assert(cond, msg = 'assertion failed') {
  if (!cond) throw new Error(msg);
}

// ------------------------------------------------------------------ publicRev

describe('what makes a case unread', () => {
  test('a public reply and a status change each move publicRev by exactly one', async () => {
    await assertSucceeds(reply('case-alice', 'r1'));
    assert((await peek('cases/case-alice')).publicRev === 1);
    await assertSucceeds(status('case-alice', 's1', 'Under granskning'));
    const c = await peek('cases/case-alice');
    assert(c.publicRev === 2 && c.activityRev === 2);
  });

  test('a reply or status change without the step, or with two, is refused', async () => {
    await assertFails(reply('case-alice', 'r1', { publicRev: null }));
    await assertFails(reply('case-alice', 'r1', { publicRev: 2 }));
    await assertFails(reply('case-alice', 'r1', { withActivity: false }));
    await assertFails(status('case-alice', 's1', 'Under granskning', { publicRev: null }));
    await assertFails(status('case-alice', 's1', 'Under granskning', { publicRev: 5 }));
  });

  test('an internal note, a close and the owner never move it', async () => {
    const w = as(env, 'lars').firestore();
    const b = writeBatch(w);
    b.set(doc(w, 'cases/case-alice/events/n1'), {
      type: 'note', visibility: 'internal', actorUid: 'lars', body: 'x', createdAt: serverTimestamp(), state: 'accepted',
    });
    b.update(doc(w, 'cases/case-alice'), { publicRev: 1 });
    await assertFails(b.commit());
    await assertFails(updateDoc(doc(w, 'cases/case-alice'), { closedAt: serverTimestamp(), publicRev: 1 }));
    await assertFails(updateDoc(doc(as(env, 'alice').firestore(), 'cases/case-alice'), { publicRev: 1 }));
    assert((await peek('cases/case-alice')).publicRev === undefined);
  });
});

// ------------------------------------------------------------------ read marker

describe('the read marker', () => {
  test('the owner marks what it has seen, only forward, never past what exists', async () => {
    await reply('case-alice', 'r1');
    await reply('case-alice', 'r2');
    await assertSucceeds(mark('alice', 'case-alice', 1));
    await assertSucceeds(mark('alice', 'case-alice', 2));
    await assertSucceeds(mark('alice', 'case-alice', 2)); // a duplicate is harmless
    await assertFails(mark('alice', 'case-alice', 1));
    await assertFails(mark('alice', 'case-alice', 3));
    await assertSucceeds(getDoc(doc(as(env, 'alice').firestore(), 'users/alice/caseReads/case-alice')));
    await assertSucceeds(getDocs(collection(as(env, 'alice').firestore(), 'users/alice/caseReads')));
  });

  test('zero on a case with no support activity yet is fine; a stray field or clock is not', async () => {
    await assertSucceeds(mark('alice', 'case-alice', 0));
    await assertFails(mark('alice', 'case-alice', 0, { body: 'x' }));
    await assertFails(setDoc(doc(as(env, 'alice').firestore(), 'users/alice/caseReads/case-alice'),
      { seenPublicRev: 0, readAt: Timestamp.fromMillis(0) }));
    await assertFails(mark('alice', 'case-alice', 0.5));
  });

  test('nobody marks or reads another account\'s markers, a foreign case, or a submitted one', async () => {
    await reply('case-bob', 'r1');
    await assertFails(mark('alice', 'case-bob', 1));
    await assertFails(setDoc(doc(as(env, 'bob').firestore(), 'users/alice/caseReads/case-alice'),
      { seenPublicRev: 0, readAt: serverTimestamp() }));
    await assertSucceeds(mark('bob', 'case-bob', 1));
    await assertFails(getDoc(doc(as(env, 'alice').firestore(), 'users/bob/caseReads/case-bob')));
    await assertFails(mark('alice', 'case-sub', 0));
    await assertFails(mark('alice', 'no-such-case', 0));
  });

  test('a revoked or pending account has no side channel, and nobody deletes a marker', async () => {
    await reply('case-alice', 'r1');
    await assertSucceeds(mark('alice', 'case-alice', 0));
    await setStatus('alice', 'revoked');
    await assertFails(getDoc(doc(as(env, 'alice').firestore(), 'users/alice/caseReads/case-alice')));
    await assertFails(mark('alice', 'case-alice', 1));
    await setStatus('alice', 'approved');
    await assertFails(deleteDoc(doc(as(env, 'alice').firestore(), 'users/alice/caseReads/case-alice')));
    await assertFails(getDoc(doc(as(env, 'lars').firestore(), 'users/alice/caseReads/case-alice')));
  });

  test('the old reads path stays closed', async () => {
    await assertFails(setDoc(doc(as(env, 'alice').firestore(), 'users/alice/reads/case-alice'),
      { seenPublicRev: 0, readAt: serverTimestamp() }));
  });
});

// ------------------------------------------------------------------ notification tokens

describe('notification tokens', () => {
  const tok = (uid, id = 'inst-1', over = {}) => setDoc(
    doc(as(env, uid).firestore(), `users/${uid}/notificationTokens/${id}`),
    { token: 'fcm-token-abc', platform: 'android', updatedAt: serverTimestamp(), ...over },
  );

  test('an approved account registers and rotates its own token', async () => {
    await assertSucceeds(tok('alice'));
    await assertSucceeds(tok('alice', 'inst-1', { token: 'fcm-token-rotated' }));
    await assertSucceeds(getDoc(doc(as(env, 'alice').firestore(), 'users/alice/notificationTokens/inst-1')));
  });

  test('no other account, no stray field, no list', async () => {
    await assertFails(setDoc(doc(as(env, 'bob').firestore(), 'users/alice/notificationTokens/inst-1'),
      { token: 't', platform: 'android', updatedAt: serverTimestamp() }));
    await assertFails(tok('alice', 'inst-1', { body: 'x' }));
    await assertFails(tok('alice', 'inst-1', { platform: 'ios' }));
    await assertFails(tok('alice', 'inst-1', { token: '' }));
    await assertFails(tok('alice', 'inst-1', { updatedAt: Timestamp.fromMillis(0) }));
    await assertSucceeds(tok('alice'));
    await assertFails(getDocs(collection(as(env, 'alice').firestore(), 'users/alice/notificationTokens')));
    await assertFails(getDoc(doc(as(env, 'bob').firestore(), 'users/alice/notificationTokens/inst-1')));
    await assertFails(getDoc(doc(as(env, 'lars').firestore(), 'users/alice/notificationTokens/inst-1')));
  });

  test('a pending or revoked account cannot register, but can always remove', async () => {
    await assertFails(tok('pat'));
    await assertSucceeds(tok('alice'));
    await setStatus('alice', 'revoked');
    await assertFails(tok('alice', 'inst-2'));
    await assertSucceeds(deleteDoc(doc(as(env, 'alice').firestore(), 'users/alice/notificationTokens/inst-1')));
    await assertFails(deleteDoc(doc(as(env, 'bob').firestore(), 'users/alice/notificationTokens/inst-2')));
  });
});
