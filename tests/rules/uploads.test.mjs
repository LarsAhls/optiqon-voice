// Negative tests 10 and 11: the reservation is the only thing that grants an upload, and
// it cannot be created, edited or reused outside the counted transaction.
import { after, before, beforeEach, describe, test } from 'node:test';
import {
  deleteDoc, doc, serverTimestamp, setDoc, updateDoc, writeBatch,
} from 'firebase/firestore';
import { as, assertFails, assertSucceeds, makeEnv, seed } from './helpers.mjs';

let env;
before(async () => { env = await makeEnv(); });
after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); await seed(env); });

const SHA = 'a'.repeat(64);
const MIB = 1024 * 1024;

/**
 * The four writes the app makes for one attachment. Each parameter exists so a test can
 * break exactly one of them and watch the whole commit fail.
 */
function reserve(db, {
  uid = 'alice',
  caseId = 'case-alice',
  aid = 'att-1',
  maxBytes = MIB,
  sha256 = SHA,
  quota = { count: 1, bytes: MIB, windowCount: 1, lastUploadId: 'att-1' },
  attachmentCount = 1,
  skip = [],
} = {}) {
  const batch = writeBatch(db);
  if (!skip.includes('reservation')) {
    batch.set(doc(db, `users/${uid}/uploads/${aid}`), {
      caseId, maxBytes, sha256, createdAt: serverTimestamp(),
    });
  }
  if (!skip.includes('quota')) {
    batch.update(doc(db, `users/${uid}/quota/attachments`), quota);
  }
  if (!skip.includes('case')) {
    batch.update(doc(db, `cases/${caseId}`), { attachmentCount });
  }
  if (!skip.includes('attachment')) {
    batch.set(doc(db, `cases/${caseId}/attachments/${aid}`), {
      ownerUid: uid, caseId, sha256, createdAt: serverTimestamp(),
    });
  }
  return batch.commit();
}

async function setQuota(env, uid, values) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await updateDoc(doc(ctx.firestore(), `users/${uid}/quota/attachments`), values);
  });
}

describe('a reservation only exists as part of a counted transaction', () => {
  test('the full four-write commit succeeds', async () => {
    const db = as(env, 'alice').firestore();
    await assertSucceeds(reserve(db));
  });

  test('a reservation without the counter bump is denied (test 10)', async () => {
    const db = as(env, 'alice').firestore();
    await assertFails(reserve(db, { skip: ['quota'] }));
  });

  test('a counter bump without the reservation is denied (test 10)', async () => {
    const db = as(env, 'alice').firestore();
    await assertFails(reserve(db, { skip: ['reservation'] }));
  });

  test('a counter that moves by more than one is denied (test 10)', async () => {
    const db = as(env, 'alice').firestore();
    await assertFails(reserve(db, {
      quota: { count: 3, bytes: MIB, windowCount: 1, lastUploadId: 'att-1' },
    }));
  });

  test('a byte total that does not match the reserved size is denied', async () => {
    const db = as(env, 'alice').firestore();
    await assertFails(reserve(db, {
      quota: { count: 1, bytes: 1, windowCount: 1, lastUploadId: 'att-1' },
    }));
  });

  test('the reservation must point at the case being written', async () => {
    const db = as(env, 'alice').firestore();
    const batch = writeBatch(db);
    batch.set(doc(db, 'users/alice/uploads/att-1'), {
      caseId: 'case-bob', maxBytes: MIB, sha256: SHA, createdAt: serverTimestamp(),
    });
    batch.update(doc(db, 'users/alice/quota/attachments'), {
      count: 1, bytes: MIB, windowCount: 1, lastUploadId: 'att-1',
    });
    batch.update(doc(db, 'cases/case-alice'), { attachmentCount: 1 });
    batch.set(doc(db, 'cases/case-alice/attachments/att-1'), {
      ownerUid: 'alice', caseId: 'case-alice', sha256: SHA, createdAt: serverTimestamp(),
    });
    await assertFails(batch.commit());
  });

  test('a malformed sha256 is denied', async () => {
    const db = as(env, 'alice').firestore();
    await assertFails(reserve(db, { sha256: 'not-a-hash' }));
  });

  test('a client-supplied objectKey is denied', async () => {
    const db = as(env, 'alice').firestore();
    const batch = writeBatch(db);
    batch.set(doc(db, 'users/alice/uploads/att-1'), {
      caseId: 'case-alice', maxBytes: MIB, sha256: SHA, createdAt: serverTimestamp(),
    });
    batch.update(doc(db, 'users/alice/quota/attachments'), {
      count: 1, bytes: MIB, windowCount: 1, lastUploadId: 'att-1',
    });
    batch.update(doc(db, 'cases/case-alice'), { attachmentCount: 1 });
    batch.set(doc(db, 'cases/case-alice/attachments/att-1'), {
      ownerUid: 'alice',
      caseId: 'case-alice',
      sha256: SHA,
      objectKey: 'r2/anything',
      createdAt: serverTimestamp(),
    });
    await assertFails(batch.commit());
  });

  test('nobody can reserve against another owner case', async () => {
    const db = as(env, 'alice').firestore();
    await assertFails(reserve(db, { caseId: 'case-bob' }));
  });
});

describe('the caps are real', () => {
  test('the 50th reservation is the last one (test 10)', async () => {
    await setQuota(env, 'alice', { count: 49, bytes: 49 * MIB, windowCount: 0 });
    const db = as(env, 'alice').firestore();
    await assertSucceeds(reserve(db, {
      quota: { count: 50, bytes: 50 * MIB, windowCount: 1, lastUploadId: 'att-1' },
    }));
    // A second attempt at the boundary has no room left, which is also what the losing
    // side of a real race sees after its transaction retries against the new value.
    await assertFails(reserve(db, {
      aid: 'att-2',
      quota: { count: 51, bytes: 51 * MIB, windowCount: 2, lastUploadId: 'att-2' },
      attachmentCount: 2,
    }));
  });

  test('100 MiB is the byte ceiling (test 10)', async () => {
    await setQuota(env, 'alice', { count: 1, bytes: 100 * MIB - 1, windowCount: 0 });
    const db = as(env, 'alice').firestore();
    await assertFails(reserve(db, {
      quota: { count: 2, bytes: 101 * MIB - 1, windowCount: 1, lastUploadId: 'att-1' },
    }));
  });

  test('20 reservations an hour is the burst ceiling', async () => {
    await setQuota(env, 'alice', { count: 1, bytes: MIB, windowCount: 20 });
    const db = as(env, 'alice').firestore();
    await assertFails(reserve(db, {
      quota: { count: 2, bytes: 2 * MIB, windowCount: 21, lastUploadId: 'att-1' },
    }));
  });

  test('a fourth attachment on one case is denied (test 10)', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await updateDoc(doc(ctx.firestore(), 'cases/case-alice'), { attachmentCount: 3 });
    });
    const db = as(env, 'alice').firestore();
    await assertFails(reserve(db, { attachmentCount: 4 }));
  });

  test('a single file over 2 MiB is denied', async () => {
    const db = as(env, 'alice').firestore();
    await assertFails(reserve(db, {
      maxBytes: 3 * MIB,
      quota: { count: 1, bytes: 3 * MIB, windowCount: 1, lastUploadId: 'att-1' },
    }));
  });
});

describe('a spent reservation stays spent', () => {
  beforeEach(async () => {
    const db = as(env, 'alice').firestore();
    await reserve(db);
  });

  test('the owner cannot edit or delete a reservation (test 11)', async () => {
    const db = as(env, 'alice').firestore();
    const ref = doc(db, 'users/alice/uploads/att-1');
    await assertFails(updateDoc(ref, { maxBytes: 2 * 1024 * 1024 }));
    await assertFails(deleteDoc(ref));
    await assertFails(setDoc(ref, {
      caseId: 'case-alice', maxBytes: MIB, sha256: SHA, createdAt: serverTimestamp(),
    }));
  });

  test('the writer cannot delete a reservation either (test 11)', async () => {
    const db = as(env, 'lars').firestore();
    await assertFails(deleteDoc(doc(db, 'users/alice/uploads/att-1')));
  });

  test('delete intent is one-way and cannot be lifted', async () => {
    const db = as(env, 'alice').firestore();
    const ref = doc(db, 'cases/case-alice/attachments/att-1');
    await assertSucceeds(updateDoc(ref, { deleteRequestedAt: serverTimestamp() }));
    await assertFails(updateDoc(ref, { deleteRequestedAt: serverTimestamp() }));
    await assertFails(deleteDoc(ref));
  });

  test('a reader-admin cannot request deletion, a writer can', async () => {
    const reader = as(env, 'reader').firestore();
    await assertFails(updateDoc(
      doc(reader, 'cases/case-alice/attachments/att-1'), { deleteRequestedAt: serverTimestamp() }));
    const lars = as(env, 'lars').firestore();
    await assertSucceeds(updateDoc(
      doc(lars, 'cases/case-alice/attachments/att-1'), { deleteRequestedAt: serverTimestamp() }));
  });

  test('another tester cannot read the attachment metadata', async () => {
    const db = as(env, 'bob').firestore();
    await assertFails(updateDoc(
      doc(db, 'cases/case-alice/attachments/att-1'), { deleteRequestedAt: serverTimestamp() }));
  });
});
