// FS-1: the feedback-case paths in `firestore.rules`, the file that gets deployed.
//
// What this suite has to establish before any app code is written against it:
//   * a screenshot costs one reservation, one quota step, one attachment and one case slot,
//     all in a single commit, and none of them can be had without the others;
//   * at most 3 screenshots per opening text or message, and at most 10 active per case;
//   * a tombstone is one-way, returns exactly one slot, and a returned slot can be reused;
//   * the owner's messages are approved-only and append-only;
//   * closing is the writer's, and a revoked owner is no owner at all.
//
// The client in these tests reads the counters before it writes, the way the app will. Where a
// test wants a hostile client it says so and builds the batch by hand.
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
  // The shared seed predates FS-1; give its cases the FS-1 shape.
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    for (const [caseId, ownerUid] of [['case-alice', 'alice'], ['case-bob', 'bob']]) {
      await setDoc(doc(db, 'cases', caseId), {
        ownerUid,
        title: `${ownerUid} case`,
        body: '',
        statusCache: 'Mottaget',
        lastStatusEventId: null,
        attachmentCount: 0,
        activeAttachmentCount: 0,
        createdAt: Timestamp.now(),
        updatedAt: Timestamp.now(),
        lastActivityAt: Timestamp.now(),
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

async function revoke(uid) {
  await poke(`users/${uid}`, { status: 'revoked' });
}

/**
 * The commit the app makes for one screenshot: reservation, quota step, attachment, case
 * slot and -- for a message's screenshot -- the message's slot. `omit` drops one of the
 * parts by name, `extra` merges fields into one, to build a hostile variant.
 */
async function attachBatch(uid, caseId, aid, opts = {}) {
  const { messageId = null, maxBytes = 1024, omit = [], extra = {}, also } = opts;
  const c = await peek(`cases/${caseId}`);
  const q = await peek(`users/${uid}/quota/attachments`);
  const db = as(env, uid).firestore();
  const b = writeBatch(db);

  if (!omit.includes('reservation')) {
    b.set(doc(db, `users/${uid}/uploads/${aid}`), {
      caseId, maxBytes, createdAt: serverTimestamp(), ...extra.reservation,
    });
  }
  if (!omit.includes('quota')) {
    const quotaRef = doc(db, `users/${uid}/quota/attachments`);
    if (q) {
      b.update(quotaRef, {
        count: q.count + 1, bytes: q.bytes + maxBytes, windowCount: q.windowCount + 1,
        lastUploadId: aid,
      });
    } else {
      b.set(quotaRef, {
        count: 1, bytes: maxBytes, windowStart: serverTimestamp(), windowCount: 1,
        lastUploadId: aid,
      });
    }
  }
  if (!omit.includes('attachment')) {
    b.set(doc(db, `cases/${caseId}/attachments/${aid}`), {
      ownerUid: uid, caseId, messageId, maxBytes, createdAt: serverTimestamp(),
      ...extra.attachment,
    });
  }
  if (!omit.includes('case')) {
    const upd = {
      activeAttachmentCount: c.activeAttachmentCount + 1,
      attachmentFor: aid,
      lastActivityAt: serverTimestamp(),
    };
    if (messageId == null) upd.attachmentCount = c.attachmentCount + 1;
    b.update(doc(db, `cases/${caseId}`), { ...upd, ...extra.case });
  }
  if (messageId != null && !omit.includes('message')) {
    const m = await peek(`cases/${caseId}/events/${messageId}`);
    b.update(doc(db, `cases/${caseId}/events/${messageId}`), {
      attachmentCount: m.attachmentCount + 1, attachmentFor: aid, ...extra.message,
    });
  }
  if (also) also(b, db);
  return b;
}

async function tombstoneBatch(uid, caseId, aid, { omit = [], extra = {} } = {}) {
  const c = await peek(`cases/${caseId}`);
  const db = as(env, uid).firestore();
  const b = writeBatch(db);
  if (!omit.includes('attachment')) {
    b.update(doc(db, `cases/${caseId}/attachments/${aid}`), {
      deleteRequestedAt: serverTimestamp(), ...extra.attachment,
    });
  }
  if (!omit.includes('case')) {
    b.update(doc(db, `cases/${caseId}`), {
      activeAttachmentCount: c.activeAttachmentCount - 1, attachmentFor: aid, ...extra.case,
    });
  }
  return b;
}

async function messageBatch(uid, caseId, eventId, body = 'Ett till problem', { bump = true } = {}) {
  const db = as(env, uid).firestore();
  const b = writeBatch(db);
  b.set(doc(db, `cases/${caseId}/events/${eventId}`), {
    type: 'message', visibility: 'public', actorUid: uid, body, attachmentCount: 0,
    createdAt: serverTimestamp(),
  });
  if (bump) b.update(doc(db, `cases/${caseId}`), { lastActivityAt: serverTimestamp() });
  return b;
}

const newCase = (uid, over = {}) => ({
  ownerUid: uid, title: 'Knappen fungerar inte', body: 'Detaljer', statusCache: 'Mottaget',
  lastStatusEventId: null, attachmentCount: 0, activeAttachmentCount: 0,
  createdAt: serverTimestamp(), updatedAt: serverTimestamp(), lastActivityAt: serverTimestamp(),
  ...over,
});

// ------------------------------------------------------------------ cases

describe('cases', () => {
  test('an approved account opens a case', async () => {
    const db = as(env, 'alice').firestore();
    await assertSucceeds(setDoc(doc(db, 'cases/c-new'), newCase('alice')));
  });

  test('a pending, revoked or unverified account cannot', async () => {
    await assertFails(setDoc(doc(as(env, 'pat').firestore(), 'cases/c-pat'), newCase('pat')));
    await assertFails(setDoc(
      doc(as(env, 'alice', { email_verified: false }).firestore(), 'cases/c-x'), newCase('alice'),
    ));
    await revoke('alice');
    await assertFails(setDoc(doc(as(env, 'alice').firestore(), 'cases/c-rev'), newCase('alice')));
  });

  test('a case opens empty, as Mottaget, in its own owner\'s name', async () => {
    const db = as(env, 'alice').firestore();
    for (const over of [
      { ownerUid: 'bob' },
      { statusCache: 'Planerat' },
      { attachmentCount: 1 },
      { activeAttachmentCount: 1 },
      { title: '' },
      { title: 'x'.repeat(201) },
      { lastActivityAt: Timestamp.fromMillis(0) },
      { closedAt: serverTimestamp() },
      { sha256: 'a'.repeat(64) },
    ]) {
      await assertFails(setDoc(doc(db, 'cases/c-bad'), newCase('alice', over)), JSON.stringify(over));
    }
  });

  test('the owner and an admin read a case; nobody else does', async () => {
    await assertSucceeds(getDoc(doc(as(env, 'alice').firestore(), 'cases/case-alice')));
    await assertSucceeds(getDoc(doc(as(env, 'reader').firestore(), 'cases/case-alice')));
    await assertFails(getDoc(doc(as(env, 'bob').firestore(), 'cases/case-alice')));
    await assertFails(getDoc(doc(as(env, 'pat').firestore(), 'cases/case-alice')));
  });

  test('a list must be constrained to the caller\'s own cases', async () => {
    const db = as(env, 'alice').firestore();
    await assertSucceeds(getDocs(query(collection(db, 'cases'), where('ownerUid', '==', 'alice'))));
    await assertFails(getDocs(collection(db, 'cases')));
    await assertSucceeds(getDocs(collection(as(env, 'reader').firestore(), 'cases')));
  });

  test('a revoked owner loses its cases at the next request', async () => {
    await revoke('alice');
    const db = as(env, 'alice').firestore();
    await assertFails(getDoc(doc(db, 'cases/case-alice')));
    await assertFails(getDocs(query(collection(db, 'cases'), where('ownerUid', '==', 'alice'))));
  });

  test('the owner cannot edit, close, or delete a case', async () => {
    const db = as(env, 'alice').firestore();
    await assertFails(updateDoc(doc(db, 'cases/case-alice'), { title: 'Annat' }));
    await assertFails(updateDoc(doc(db, 'cases/case-alice'), { statusCache: 'Levererat' }));
    await assertFails(updateDoc(doc(db, 'cases/case-alice'), { closedAt: serverTimestamp() }));
    await assertFails(deleteDoc(doc(db, 'cases/case-alice')));
  });

  test('the writer closes a case, once, now', async () => {
    const db = as(env, 'lars').firestore();
    await assertFails(updateDoc(doc(db, 'cases/case-alice'), { closedAt: Timestamp.fromMillis(0) }));
    await assertSucceeds(updateDoc(doc(db, 'cases/case-alice'), { closedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(db, 'cases/case-alice'), { closedAt: serverTimestamp() }));
    // A reader-admin is not a writer.
    await assertFails(updateDoc(doc(as(env, 'reader').firestore(), 'cases/case-bob'), {
      closedAt: serverTimestamp(),
    }));
  });

  test('the owner cannot move lastActivityAt anywhere but now', async () => {
    const db = as(env, 'alice').firestore();
    await assertFails(updateDoc(doc(db, 'cases/case-alice'), { lastActivityAt: Timestamp.fromMillis(0) }));
  });
});

// ------------------------------------------------------------------ screenshots

describe('a screenshot is one commit of four parts', () => {
  test('the whole commit succeeds, and leaves the counters right', async () => {
    await assertSucceeds((await attachBatch('alice', 'case-alice', 'a1')).commit());
    const c = await peek('cases/case-alice');
    assert(c.activeAttachmentCount === 1 && c.attachmentCount === 1 && c.attachmentFor === 'a1');
    const q = await peek('users/alice/quota/attachments');
    assert(q.count === 1 && q.bytes === 1024 && q.lastUploadId === 'a1');
  });

  test('an account with no quota document creates it on its first screenshot', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await deleteDoc(doc(ctx.firestore(), 'users/alice/quota/attachments'));
    });
    await assertSucceeds((await attachBatch('alice', 'case-alice', 'a1')).commit());
    await assertSucceeds((await attachBatch('alice', 'case-alice', 'a2')).commit());
    const q = await peek('users/alice/quota/attachments');
    assert(q.count === 2 && q.windowCount === 2);
  });

  test('a quota document cannot be created out of thin air', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await deleteDoc(doc(ctx.firestore(), 'users/alice/quota/attachments'));
    });
    const db = as(env, 'alice').firestore();
    await assertFails(setDoc(doc(db, 'users/alice/quota/attachments'), {
      count: 1, bytes: 0, windowStart: serverTimestamp(), windowCount: 1, lastUploadId: 'ghost',
    }));
  });

  for (const part of ['reservation', 'quota', 'attachment', 'case']) {
    test(`without its ${part} it is refused`, async () => {
      await assertFails((await attachBatch('alice', 'case-alice', 'a1', { omit: [part] })).commit());
    });
  }

  test('one case slot cannot cover two attachments', async () => {
    // A hostile client adds a second attachment, with its own reservation, under one +1.
    const b = await attachBatch('alice', 'case-alice', 'a1', {
      also: (batch, db) => {
        batch.set(doc(db, 'users/alice/uploads/a2'), {
          caseId: 'case-alice', maxBytes: 1024, createdAt: serverTimestamp(),
        });
        batch.set(doc(db, 'cases/case-alice/attachments/a2'), {
          ownerUid: 'alice', caseId: 'case-alice', messageId: null, maxBytes: 1024,
          createdAt: serverTimestamp(),
        });
      },
    });
    await assertFails(b.commit());
  });

  test('a spent attachment id is never reused', async () => {
    await assertSucceeds((await attachBatch('alice', 'case-alice', 'a1')).commit());
    await assertFails((await attachBatch('alice', 'case-alice', 'a1')).commit());
    await assertSucceeds((await tombstoneBatch('alice', 'case-alice', 'a1')).commit());
    await assertFails((await attachBatch('alice', 'case-alice', 'a1')).commit());
  });

  test('no content fingerprint is accepted anywhere', async () => {
    const sha = { sha256: 'a'.repeat(64) };
    await assertFails((await attachBatch('alice', 'case-alice', 'a1', {
      extra: { attachment: sha },
    })).commit());
    await assertFails((await attachBatch('alice', 'case-alice', 'a1', {
      extra: { reservation: sha },
    })).commit());
  });

  test('the attachment carries the reservation\'s ceiling, which is at most 2 MiB', async () => {
    await assertFails((await attachBatch('alice', 'case-alice', 'a1', {
      extra: { attachment: { maxBytes: 2048 } },
    })).commit());
    await assertFails((await attachBatch('alice', 'case-alice', 'a1', {
      maxBytes: 2 * 1024 * 1024 + 1,
    })).commit());
    await assertSucceeds((await attachBatch('alice', 'case-alice', 'a1', {
      maxBytes: 2 * 1024 * 1024,
    })).commit());
  });

  test('the attachment is the caller\'s own, on the caller\'s own case', async () => {
    await assertFails((await attachBatch('alice', 'case-bob', 'a1')).commit());
    await assertFails((await attachBatch('alice', 'case-alice', 'a1', {
      extra: { attachment: { ownerUid: 'bob' } },
    })).commit());
    await assertFails((await attachBatch('alice', 'case-alice', 'a1', {
      extra: { reservation: { caseId: 'case-bob' } },
    })).commit());
  });

  test('a pending or revoked account cannot attach', async () => {
    await assertFails((await attachBatch('pat', 'case-alice', 'a1')).commit());
    await revoke('alice');
    await assertFails((await attachBatch('alice', 'case-alice', 'a1')).commit());
  });

  test('at most 3 screenshots with the opening text', async () => {
    for (const aid of ['a1', 'a2', 'a3']) {
      await assertSucceeds((await attachBatch('alice', 'case-alice', aid)).commit());
    }
    await assertFails((await attachBatch('alice', 'case-alice', 'a4')).commit());
  });

  test('the opening counter never moves backwards on a tombstone', async () => {
    for (const aid of ['a1', 'a2', 'a3']) {
      await assertSucceeds((await attachBatch('alice', 'case-alice', aid)).commit());
    }
    await assertSucceeds((await tombstoneBatch('alice', 'case-alice', 'a1')).commit());
    await assertFails((await attachBatch('alice', 'case-alice', 'a4')).commit());
    await assertFails((await tombstoneBatch('alice', 'case-alice', 'a2', {
      extra: { case: { attachmentCount: 1 } },
    })).commit());
  });

  test('at most 10 active screenshots on a case', async () => {
    await poke('cases/case-alice', { activeAttachmentCount: 10, attachmentCount: 3 });
    await assertSucceeds(messageBatch('alice', 'case-alice', 'm1').then((b) => b.commit()));
    await assertFails((await attachBatch('alice', 'case-alice', 'a11', { messageId: 'm1' })).commit());
  });

  test('a closed case takes no new screenshots', async () => {
    await poke('cases/case-alice', { closedAt: Timestamp.now() });
    await assertFails((await attachBatch('alice', 'case-alice', 'a1')).commit());
  });

  test('the counters cannot be moved on their own', async () => {
    const db = as(env, 'alice').firestore();
    await assertFails(updateDoc(doc(db, 'cases/case-alice'), {
      activeAttachmentCount: 1, attachmentCount: 1, attachmentFor: 'ghost',
      lastActivityAt: serverTimestamp(),
    }));
    await assertFails(updateDoc(doc(db, 'cases/case-alice'), { activeAttachmentCount: 0, attachmentFor: 'x' }));
  });
});

// ------------------------------------------------------------------ tombstones

describe('tombstones', () => {
  beforeEach(async () => {
    await assertSucceeds((await attachBatch('alice', 'case-alice', 'a1')).commit());
  });

  test('the owner takes a screenshot down and the slot comes back', async () => {
    await assertSucceeds((await tombstoneBatch('alice', 'case-alice', 'a1')).commit());
    const c = await peek('cases/case-alice');
    assert(c.activeAttachmentCount === 0);
    const a = await peek('cases/case-alice/attachments/a1');
    assert(a.deleteRequestedAt instanceof Timestamp);
  });

  test('a returned slot can be spent again: 10 active, one down, one more up', async () => {
    await poke('cases/case-alice', { activeAttachmentCount: 10, attachmentCount: 3 });
    await assertSucceeds(messageBatch('alice', 'case-alice', 'm1').then((b) => b.commit()));
    await assertFails((await attachBatch('alice', 'case-alice', 'a2', { messageId: 'm1' })).commit());
    await assertSucceeds((await tombstoneBatch('alice', 'case-alice', 'a1')).commit());
    await assertSucceeds((await attachBatch('alice', 'case-alice', 'a2', { messageId: 'm1' })).commit());
  });

  test('a tombstone is one-way and happens once', async () => {
    await assertSucceeds((await tombstoneBatch('alice', 'case-alice', 'a1')).commit());
    await assertFails((await tombstoneBatch('alice', 'case-alice', 'a1')).commit());
    const db = as(env, 'alice').firestore();
    await assertFails(updateDoc(doc(db, 'cases/case-alice/attachments/a1'), {
      deleteRequestedAt: null,
    }));
    await assertFails(deleteDoc(doc(db, 'cases/case-alice/attachments/a1')));
  });

  test('a tombstone without its slot, or a slot without its tombstone, is refused', async () => {
    await assertFails((await tombstoneBatch('alice', 'case-alice', 'a1', { omit: ['case'] })).commit());
    await assertFails((await tombstoneBatch('alice', 'case-alice', 'a1', { omit: ['attachment'] })).commit());
  });

  test('a tombstone is dated now and touches nothing else', async () => {
    await assertFails((await tombstoneBatch('alice', 'case-alice', 'a1', {
      extra: { attachment: { deleteRequestedAt: Timestamp.fromMillis(0) } },
    })).commit());
    await assertFails((await tombstoneBatch('alice', 'case-alice', 'a1', {
      extra: { attachment: { maxBytes: 1 } },
    })).commit());
  });

  test('the writer can take a screenshot down; a stranger and a reader-admin cannot', async () => {
    await assertFails((await tombstoneBatch('bob', 'case-alice', 'a1')).commit());
    await assertFails((await tombstoneBatch('reader', 'case-alice', 'a1')).commit());
    await assertSucceeds((await tombstoneBatch('lars', 'case-alice', 'a1')).commit());
  });

  test('a revoked owner cannot take a screenshot down', async () => {
    await revoke('alice');
    await assertFails((await tombstoneBatch('alice', 'case-alice', 'a1')).commit());
  });

  test('the owner can still take a screenshot down after the case is closed', async () => {
    await poke('cases/case-alice', { closedAt: Timestamp.now() });
    await assertSucceeds((await tombstoneBatch('alice', 'case-alice', 'a1')).commit());
  });

  test('attachment metadata is read by its case owner and an admin only', async () => {
    await assertSucceeds(getDoc(doc(as(env, 'alice').firestore(), 'cases/case-alice/attachments/a1')));
    await assertSucceeds(getDoc(doc(as(env, 'reader').firestore(), 'cases/case-alice/attachments/a1')));
    await assertFails(getDoc(doc(as(env, 'bob').firestore(), 'cases/case-alice/attachments/a1')));
    await revoke('alice');
    await assertFails(getDoc(doc(as(env, 'alice').firestore(), 'cases/case-alice/attachments/a1')));
  });

  test('reservations are read by their owner and an admin, and never change', async () => {
    await assertSucceeds(getDoc(doc(as(env, 'alice').firestore(), 'users/alice/uploads/a1')));
    await assertFails(getDoc(doc(as(env, 'bob').firestore(), 'users/alice/uploads/a1')));
    const db = as(env, 'alice').firestore();
    await assertFails(updateDoc(doc(db, 'users/alice/uploads/a1'), { maxBytes: 2 }));
    await assertFails(deleteDoc(doc(db, 'users/alice/uploads/a1')));
  });
});

// ------------------------------------------------------------------ messages

describe('the owner\'s messages', () => {
  test('an approved owner writes a message and marks the case active in the same commit', async () => {
    await assertSucceeds((await messageBatch('alice', 'case-alice', 'm1')).commit());
  });

  test('a message without the activity mark is refused', async () => {
    await assertFails((await messageBatch('alice', 'case-alice', 'm1', 'x', { bump: false })).commit());
  });

  test('only on the caller\'s own open case, and only while approved', async () => {
    await assertFails((await messageBatch('alice', 'case-bob', 'm1')).commit());
    await assertFails((await messageBatch('pat', 'case-alice', 'm1')).commit());
    await poke('cases/case-alice', { closedAt: Timestamp.now() });
    await assertFails((await messageBatch('alice', 'case-alice', 'm1')).commit());
    await revoke('bob');
    await assertFails((await messageBatch('bob', 'case-bob', 'm2')).commit());
  });

  test('a message is public, attributed, non-empty, and starts without screenshots', async () => {
    const db = as(env, 'alice').firestore();
    const base = {
      type: 'message', visibility: 'public', actorUid: 'alice', body: 'Hej', attachmentCount: 0,
      createdAt: serverTimestamp(),
    };
    for (const over of [
      { type: 'status_change' }, { visibility: 'internal' }, { actorUid: 'lars' }, { body: '' },
      { attachmentCount: 3 }, { createdAt: Timestamp.fromMillis(0) },
    ]) {
      const b = writeBatch(db);
      b.set(doc(db, 'cases/case-alice/events/m1'), { ...base, ...over });
      b.update(doc(db, 'cases/case-alice'), { lastActivityAt: serverTimestamp() });
      await assertFails(b.commit(), JSON.stringify(over));
    }
  });

  test('a message is append-only', async () => {
    await assertSucceeds((await messageBatch('alice', 'case-alice', 'm1')).commit());
    const db = as(env, 'alice').firestore();
    await assertFails(updateDoc(doc(db, 'cases/case-alice/events/m1'), { body: 'Ändrat' }));
    await assertFails(deleteDoc(doc(db, 'cases/case-alice/events/m1')));
    await assertFails(updateDoc(doc(as(env, 'lars').firestore(), 'cases/case-alice/events/m1'), {
      body: 'Ändrat',
    }));
  });

  test('at most 3 screenshots per message, whatever the opening text holds', async () => {
    await assertSucceeds((await messageBatch('alice', 'case-alice', 'm1')).commit());
    for (const aid of ['a1', 'a2', 'a3']) {
      await assertSucceeds((await attachBatch('alice', 'case-alice', aid, { messageId: 'm1' })).commit());
    }
    await assertFails((await attachBatch('alice', 'case-alice', 'a4', { messageId: 'm1' })).commit());
    // The opening text's own three are still free.
    await assertSucceeds((await attachBatch('alice', 'case-alice', 'a5')).commit());
    const c = await peek('cases/case-alice');
    assert(c.activeAttachmentCount === 4 && c.attachmentCount === 1);
  });

  test('a message screenshot moves the message counter, not the opening one', async () => {
    await assertSucceeds((await messageBatch('alice', 'case-alice', 'm1')).commit());
    await assertFails((await attachBatch('alice', 'case-alice', 'a1', {
      messageId: 'm1', omit: ['message'],
    })).commit());
    await assertFails((await attachBatch('alice', 'case-alice', 'a1', {
      messageId: 'm1', extra: { case: { attachmentCount: 1 } },
    })).commit());
  });

  test('a screenshot cannot be hung on someone else\'s event', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'cases/case-alice/events/note'), {
        type: 'note', visibility: 'public', actorUid: 'lars', attachmentCount: 0,
        createdAt: Timestamp.now(),
      });
    });
    await assertFails((await attachBatch('alice', 'case-alice', 'a1', { messageId: 'note' })).commit());
  });

  test('the owner sees public events only; an admin sees all', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await setDoc(doc(db, 'cases/case-alice/events/pub'), {
        type: 'note', visibility: 'public', actorUid: 'lars', createdAt: Timestamp.now(),
      });
      await setDoc(doc(db, 'cases/case-alice/events/int'), {
        type: 'note', visibility: 'internal', actorUid: 'lars', createdAt: Timestamp.now(),
      });
    });
    const alice = as(env, 'alice').firestore();
    await assertSucceeds(getDoc(doc(alice, 'cases/case-alice/events/pub')));
    await assertFails(getDoc(doc(alice, 'cases/case-alice/events/int')));
    await assertSucceeds(getDocs(query(
      collection(alice, 'cases/case-alice/events'), where('visibility', '==', 'public'),
    )));
    await assertFails(getDocs(collection(alice, 'cases/case-alice/events')));
    await assertSucceeds(getDoc(doc(as(env, 'reader').firestore(), 'cases/case-alice/events/int')));
    await assertFails(getDoc(doc(as(env, 'bob').firestore(), 'cases/case-alice/events/pub')));
  });
});

function assert(cond) {
  if (!cond) throw new Error('assertion failed');
}
