// FS-S468 (M4): admin account deletion against the emulator, with in-memory Storage and Auth.
// Dry run by default, exact project guard, every user-linked path gone, seat released once,
// partial failure resumes, a second run is a no-op, other accounts untouched.
import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, test } from 'node:test';
import { deleteApp, initializeApp } from 'firebase-admin/app';
import { FieldValue, getFirestore, Timestamp } from 'firebase-admin/firestore';
import { AccountDeletionError, deleteAccount, inventory, memoryAuth, TARGET_PROJECT } from '../../server/account-deletion.mjs';
import { memoryStorage } from '../../server/storage.mjs';
import { run } from '../../scripts/admin/m1-bootstrap.mjs';

assert.ok(process.env.FIRESTORE_EMULATOR_HOST, 'run through `npm run test:rules`');

// The tool is locked to the real project id, so the emulator app carries it too. Nothing leaves
// the machine: FIRESTORE_EMULATOR_HOST is set for the whole process.
let app;
let db;
before(() => {
  app = initializeApp({ projectId: TARGET_PROJECT }, 'account-deletion');
  db = getFirestore(app);
});
after(async () => {
  await clear();
  await deleteApp(app);
});

async function clear() {
  for (const col of ['users', 'admins', 'config', 'cases']) {
    for (const d of (await db.collection(col).get()).docs) await db.recursiveDelete(d.ref);
  }
}

let storage;
let auth;
const deps = (over = {}) => ({ db, FieldValue, storage, auth, projectId: TARGET_PROJECT, actorUid: 'lars', ...over });
const apply = (over = {}) => deps({ apply: true, ...over });
const peek = async (path) => {
  const s = await db.doc(path).get();
  return s.exists ? s.data() : undefined;
};
const now = Timestamp.now();

async function putCase(caseId, ownerUid, extra = {}) {
  await db.doc(`cases/${caseId}`).set({
    ownerUid, title: 't', body: 'b', statusCache: 'Mottaget', state: 'accepted', createdAt: now,
    acceptedAt: now, activityRev: 1, publicRev: 1, activeAttachmentCount: 1, attachmentCount: 1, ...extra,
  });
  await db.doc(`cases/${caseId}/events/e1`).set({ type: 'message', visibility: 'public', actorUid: ownerUid, body: 'x', state: 'accepted', createdAt: now });
  await db.doc(`cases/${caseId}/events/n1`).set({ type: 'note', visibility: 'internal', actorUid: 'lars', body: 'private', state: 'accepted', createdAt: now });
  await db.doc(`cases/${caseId}/attachments/a1`).set({ caseId, ownerUid, createdAt: now, contentType: 'image/png' });
  storage.put(`case-attachments/${ownerUid}/${caseId}/a1`);
}

beforeEach(async () => {
  await clear();
  storage = memoryStorage();
  auth = memoryAuth({ alice: { email: 'alice@example.com', emailVerified: true }, bob: { email: 'bob@example.com', emailVerified: true } });
  await db.doc('config/limits').set({ maxApprovedUsers: 10, uploadsEnabled: false });
  await db.doc('config/counters').set({ approvedUsers: 3, seatFor: 'lars' });
  for (const uid of ['alice', 'bob', 'lars']) {
    await db.doc(`users/${uid}`).set({ uid, email: `${uid}@example.com`, status: 'approved', approvalGeneration: 1, createdAt: now });
  }
  await db.doc('admins/lars').set({ role: 'writer' });
  await db.doc('admins/alice').set({ role: 'reader', revokedAt: now });

  await putCase('c1', 'alice');
  await putCase('c2', 'alice', { state: 'submitted', acceptedAt: null });
  await putCase('c3', 'alice', { closedAt: now });
  await putCase('b1', 'bob');
  storage.put('case-attachments/alice/orphan/zz'); // no attachment document names it
  for (const [col, id, data] of [
    ['uploads', 'a9', { caseId: 'c1', createdAt: now }],
    ['quota', 'attachments', { count: 2 }],
    ['quota', 'withdrawals', { count: 1 }],
    ['withdrawals', 't1', { caseId: 'c1', createdAt: now }],
    ['caseReads', 'c1', { seenPublicRev: 1, readAt: now }],
    ['notificationTokens', 'phone', { token: 'tok', platform: 'android', updatedAt: now }],
    ['notificationSends', 'c1:r1', { caseId: 'c1', sentAt: now }],
  ]) await db.doc(`users/alice/${col}/${id}`).set(data);
  await db.doc('users/bob/caseReads/b1').set({ seenPublicRev: 1, readAt: now });
});

const rejects = (p, code) => assert.rejects(p, (e) => e instanceof AccountDeletionError && e.code === code);

describe('guards', () => {
  test('project guard is exact, before any read', async () => {
    for (const projectId of ['optiqon-voice', 'optioqon-voice', 'optiqon-voice-47498 ', undefined]) {
      await rejects(deleteAccount(apply({ projectId }), 'alice'), 'WRONG_PROJECT');
    }
    assert.equal((await peek('users/alice')).status, 'approved');
  });

  test('bad uid, self deletion and missing adapters are refused', async () => {
    for (const uid of ['', '../x', 'a/b', null]) await rejects(deleteAccount(apply(), uid), 'BAD_UID');
    await rejects(deleteAccount(apply(), 'lars'), 'SELF_DELETION');
    await rejects(deleteAccount(apply({ auth: undefined }), 'alice'), 'MISSING_DEPS');
  });
});

describe('dry run', () => {
  test('is the default, lists ids and counts only, and writes nothing', async () => {
    const r = await deleteAccount(deps(), 'alice');
    assert.equal(r.applied, false);
    assert.deepEqual(r.inventory, {
      uid: 'alice',
      auth: 'active',
      user: { status: 'approved', deletionStarted: false },
      admin: true,
      seat: 'release',
      userCollections: { caseReads: 1, notificationSends: 1, notificationTokens: 1, quota: 2, uploads: 1, withdrawals: 1 },
      cases: [
        { caseId: 'c1', state: 'accepted', attachments: 1, events: 2 },
        { caseId: 'c2', state: 'submitted', attachments: 1, events: 2 },
        { caseId: 'c3', state: 'accepted', attachments: 1, events: 2 },
      ],
      storageObjects: 4,
      empty: false,
    });
    assert.ok(!JSON.stringify(r).includes('private'), 'the inventory names no content');
    assert.equal((await peek('users/alice')).status, 'approved');
    assert.equal(storage.objects.size, 5);
    assert.equal((await peek('config/counters')).approvedUsers, 3);
    assert.equal(auth.store.get('alice').disabled, false);
  });
});

describe('apply', () => {
  test('removes every user-linked path, releases the seat once, and leaves bob alone', async () => {
    const r = await deleteAccount(apply(), 'alice');
    assert.equal(r.applied, true);
    assert.equal(r.after.empty, true);

    for (const path of ['users/alice', 'admins/alice', 'cases/c1', 'cases/c2', 'cases/c3']) {
      assert.equal(await peek(path), undefined, path);
    }
    for (const c of ['c1', 'c2', 'c3']) {
      assert.equal((await db.collection(`cases/${c}/events`).get()).size, 0);
      assert.equal((await db.collection(`cases/${c}/attachments`).get()).size, 0);
    }
    assert.deepEqual((await db.doc('users/alice').listCollections()).map((c) => c.id), []);
    assert.deepEqual([...storage.objects], ['case-attachments/bob/b1/a1']);
    assert.equal(auth.store.has('alice'), false);
    assert.deepEqual(await peek('config/counters'), { approvedUsers: 2, seatFor: 'alice' });

    assert.ok(await peek('cases/b1'));
    assert.ok(await peek('users/bob/caseReads/b1'));
    assert.equal((await peek('users/bob')).status, 'approved');
    assert.ok(auth.store.has('bob'));
  });

  test('screenshots are unreadable before any object is deleted', async () => {
    let seen;
    await deleteAccount(apply({
      hooks: {
        async after(step) {
          if (step !== 2) return;
          seen = {
            objects: storage.objects.size,
            user: (await peek('users/alice')).status,
            tombstones: (await db.collectionGroup('attachments').get()).docs
              .filter((d) => d.get('ownerUid') === 'alice')
              .map((d) => d.get('deleteRequestedAt') != null),
            purging: (await db.collection('cases').where('ownerUid', '==', 'alice').get()).docs.map((d) => d.get('retentionState')),
            authDisabled: auth.store.get('alice').disabled,
          };
        },
      },
    }), 'alice');
    assert.deepEqual(seen, {
      objects: 5,
      user: 'revoked',
      tombstones: [true, true, true],
      purging: ['purging', 'purging', 'purging'],
      authDisabled: true,
    });
  });

  test('a second run is a no-op; the seat is not released twice', async () => {
    await deleteAccount(apply(), 'alice');
    const r = await deleteAccount(apply(), 'alice');
    assert.equal(r.alreadyDeleted, true);
    assert.deepEqual(await peek('config/counters'), { approvedUsers: 2, seatFor: 'alice' });
  });

  test('a pending or revoked account releases no seat', async () => {
    await db.doc('users/alice').update({ status: 'revoked' });
    await db.doc('config/counters').set({ approvedUsers: 2, seatFor: 'alice' });
    await deleteAccount(apply(), 'alice');
    assert.deepEqual(await peek('config/counters'), { approvedUsers: 2, seatFor: 'alice' });
  });

  test('an account known only to Auth, or only to Firestore, is deleted too', async () => {
    auth.store.set('ghost', { uid: 'ghost', disabled: false });
    await deleteAccount(apply(), 'ghost');
    assert.equal(auth.store.has('ghost'), false);
    auth.store.delete('alice');
    await deleteAccount(apply(), 'alice');
    assert.equal(await peek('users/alice'), undefined);
  });
});

describe('partial failure and resume', () => {
  for (const step of [1, 2, 3, 4, 5]) {
    test(`a crash after step ${step} is finished by the next run`, async () => {
      await assert.rejects(deleteAccount(apply({
        hooks: { after: async (s) => { if (s === step) throw new Error('crash'); } },
      }), 'alice'), /crash/);
      if (step >= 1) assert.equal((await peek('config/counters')).approvedUsers, 2);
      const r = await deleteAccount(apply(), 'alice');
      assert.equal(r.after.empty, true);
      assert.deepEqual(await peek('config/counters'), { approvedUsers: 2, seatFor: 'alice' });
      assert.deepEqual([...storage.objects], ['case-attachments/bob/b1/a1']);
    });
  }

  test('a failing Storage delete and a failing Auth delete are retried by the next run', async () => {
    storage.failNext('remove');
    await assert.rejects(deleteAccount(apply(), 'alice'), /injected remove/);
    auth.failNext('deleteUser');
    await assert.rejects(deleteAccount(apply(), 'alice'), /injected deleteUser/);
    assert.equal(auth.store.has('alice'), true);
    assert.equal((await deleteAccount(apply(), 'alice')).after.empty, true);
    assert.equal(auth.store.has('alice'), false);
  });

  test('an upload that lands during the deletion does not survive it', async () => {
    await deleteAccount(apply({
      hooks: { after: async (s) => { if (s === 3) storage.put('case-attachments/alice/c1/late'); } },
    }), 'alice');
    assert.deepEqual([...storage.objects], ['case-attachments/bob/b1/a1']);
  });

  test('an inconsistent attachment document does not block the deletion', async () => {
    await db.doc('cases/c1/attachments/bad').set({ caseId: 'other', ownerUid: 'alice', createdAt: now });
    assert.equal((await deleteAccount(apply(), 'alice')).after.empty, true);
  });
});

describe('admin tooling', () => {
  test('an account being deleted cannot be approved again', async () => {
    await db.doc('users/alice').update({ status: 'revoked', deletionStartedAt: now });
    await db.doc('users/lars').update({ email: 'lars@optiqon.se' });
    await assert.rejects(run({
      command: 'approve', target: 'alice@example.com', projectId: TARGET_PROJECT, apply: true,
      deps: { db, FieldValue, identity: { email: 'lars@optiqon.se' }, lookupUid: async (e) => (e === 'lars@optiqon.se' ? 'lars' : 'alice'), log: () => {} },
    }), (e) => e.code === 'ACCOUNT_DELETING');
    assert.equal((await peek('users/alice')).status, 'revoked');
  });

  test('inventory of an unknown account is empty', async () => {
    assert.equal((await inventory(deps(), 'nobody')).empty, true);
  });
});
