// FS-S468 (S7): the admin tool — approvalGeneration backfill, admin grant/revoke, and the
// delete-account wiring. Emulator Firestore, in-memory claims/Auth/Storage. Never live.
import assert from 'node:assert/strict';
import { after, before, beforeEach, test } from 'node:test';
import { deleteApp, initializeApp } from 'firebase-admin/app';
import { FieldValue, getFirestore, Timestamp } from 'firebase-admin/firestore';
import { AdminToolError, run, TARGET_PROJECT } from '../../scripts/admin/voice-admin.mjs';
import { ADMIN_EMAIL } from '../../scripts/admin/m1-bootstrap.mjs';
import { memoryAuth } from '../../server/account-deletion.mjs';
import { memoryStorage } from '../../server/storage.mjs';

assert.ok(process.env.FIRESTORE_EMULATOR_HOST, 'run through `npm run test:rules`');

let app;
let db;
before(() => {
  app = initializeApp({ projectId: TARGET_PROJECT }, 'voice-admin');
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

const AUTH = { [ADMIN_EMAIL]: 'lars', 'tester@example.com': 'tester', 'other@example.com': 'other' };
let claimStore;
let auth;
let storage;
const claims = {
  async get(uid) {
    return claimStore.get(uid) ?? {};
  },
  async set(uid, c) {
    claimStore.set(uid, { ...c });
  },
};
const deps = (over = {}) => ({
  db, FieldValue, identity: { email: ADMIN_EMAIL }, lookupUid: async (e) => AUTH[e] ?? null,
  claims, auth, storage, log: () => {}, ...over,
});
const go = (args, over) => run({ projectId: TARGET_PROJECT, ...args, deps: deps(over) });
const peek = async (path) => {
  const s = await db.doc(path).get();
  return s.exists ? s.data() : undefined;
};
const rejects = (p, code) => assert.rejects(p, (e) => {
  assert.ok(e instanceof AdminToolError || e?.code === code, String(e));
  assert.equal(e.code, code);
  return true;
});

beforeEach(async () => {
  await clear();
  claimStore = new Map();
  storage = memoryStorage();
  auth = memoryAuth({ tester: { email: 'tester@example.com' } });
  const t = Timestamp.now();
  await db.doc('config/counters').set({ approvedUsers: 2, seatFor: 'tester' });
  await db.doc('users/lars').set({ uid: 'lars', email: ADMIN_EMAIL, status: 'approved', createdAt: t, approvalGeneration: 1 });
  await db.doc('admins/lars').set({ role: 'writer' });
  await db.doc('users/tester').set({ uid: 'tester', email: 'tester@example.com', status: 'approved', createdAt: t });
  await db.doc('users/other').set({ uid: 'other', email: 'other@example.com', status: 'pending', createdAt: t, approvalGeneration: 3 });
});

// -------------------------------------------------------------------- guards

test('refuses the wrong project and the wrong identity before touching anything', async () => {
  for (const command of ['backfill-generation', 'grant-admin', 'revoke-admin', 'delete-account']) {
    await rejects(run({ command, target: 'tester@example.com', role: 'writer', projectId: 'optiqon-voice', apply: true, deps: deps() }), 'WRONG_PROJECT');
    await rejects(go({ command, target: 'tester@example.com', role: 'writer', apply: true }, { identity: { email: 'x@y.z' } }), 'WRONG_ADMIN');
  }
  assert.equal((await peek('users/tester')).approvalGeneration, undefined);
  assert.equal(await peek('admins/tester'), undefined);
  assert.ok(await peek('users/tester'));
  await rejects(go({ command: 'nope' }), 'UNKNOWN_COMMAND');
});

// --------------------------------------------------------- backfill-generation

test('backfill: dry run plans only the missing field and writes nothing', async () => {
  const r = await go({ command: 'backfill-generation' });
  assert.deepEqual(r.plan, [{ op: 'update', path: 'users/tester', data: { approvalGeneration: 0 } }]);
  assert.equal(r.changed, false);
  assert.equal((await peek('users/tester')).approvalGeneration, undefined);
});

test('backfill: apply sets 0 for missing, preserves existing, is idempotent, reports invalid values', async () => {
  await db.doc('users/weird').set({ uid: 'weird', email: 'w@example.com', status: 'revoked', approvalGeneration: 'two' });
  const r = await go({ command: 'backfill-generation', apply: true });
  assert.equal(r.written, 1);
  assert.deepEqual(r.invalid, [{ uid: 'weird', approvalGeneration: 'two' }]);
  assert.equal((await peek('users/tester')).approvalGeneration, 0);
  assert.equal((await peek('users/lars')).approvalGeneration, 1);
  assert.equal((await peek('users/other')).approvalGeneration, 3);
  assert.equal((await peek('users/weird')).approvalGeneration, 'two');
  assert.equal((await peek('users/tester')).status, 'approved');
  assert.deepEqual(await peek('config/counters'), { approvedUsers: 2, seatFor: 'tester' });
  const again = await go({ command: 'backfill-generation', apply: true });
  assert.equal(again.plan.length, 0);
  assert.equal(again.changed, false);
});

test('backfill: a generation that appears between scan and write is kept', async () => {
  // Simulate an approval landing after the scan: the per-document transaction re-reads.
  let injected = false;
  const racingDb = new Proxy(db, {
    get(target, prop) {
      if (prop === 'runTransaction' && !injected) {
        injected = true;
        return async (fn) => {
          await db.doc('users/tester').update({ approvalGeneration: 5 });
          return target.runTransaction(fn);
        };
      }
      const v = target[prop];
      return typeof v === 'function' ? v.bind(target) : v;
    },
  });
  const r = await go({ command: 'backfill-generation', apply: true }, { db: racingDb });
  assert.equal(r.written, 0);
  assert.equal((await peek('users/tester')).approvalGeneration, 5);
});

// ------------------------------------------------------------ grant / revoke

test('grant: dry run writes nothing; apply sets admins doc, mirror and claim; second run is a no-op', async () => {
  const dry = await go({ command: 'grant-admin', target: 'tester@example.com', role: 'reader' });
  assert.equal(dry.changed, false);
  assert.equal(dry.plan.length, 3);
  assert.equal(await peek('admins/tester'), undefined);
  assert.equal(claimStore.get('tester'), undefined);

  const r = await go({ command: 'grant-admin', target: 'tester@example.com', role: 'reader', apply: true });
  assert.equal(r.changed, true);
  const a = await peek('admins/tester');
  assert.equal(a.role, 'reader');
  assert.equal(a.grantedBy, 'lars');
  assert.equal(a.revokedAt, undefined);
  assert.equal((await peek('users/tester')).adminActive, true);
  assert.deepEqual(claimStore.get('tester'), { admin: true });

  const again = await go({ command: 'grant-admin', target: 'tester@example.com', role: 'reader', apply: true });
  assert.equal(again.changed, false);
  assert.equal(again.plan.length, 0);
});

test('grant: --until is mirrored to both documents; a past or bad time is refused', async () => {
  const until = new Date(Date.now() + 7 * 86400000).toISOString();
  await go({ command: 'grant-admin', target: 'tester@example.com', role: 'writer', until, apply: true });
  const ms = Date.parse(until);
  assert.equal((await peek('admins/tester')).activeUntil.toMillis(), ms);
  assert.equal((await peek('users/tester')).adminActiveUntil.toMillis(), ms);
  await rejects(go({ command: 'grant-admin', target: 'tester@example.com', role: 'writer', until: '2020-01-01T00:00:00Z', apply: true }), 'BAD_UNTIL');
  await rejects(go({ command: 'grant-admin', target: 'tester@example.com', role: 'writer', until: 'soon', apply: true }), 'BAD_UNTIL');
});

test('grant: refuses unapproved, unknown, deleting, mismatched and bad-role targets', async () => {
  await rejects(go({ command: 'grant-admin', target: 'other@example.com', role: 'writer', apply: true }), 'NOT_APPROVED');
  await rejects(go({ command: 'grant-admin', target: 'ghost@example.com', role: 'writer', apply: true }), 'NOT_IN_AUTH');
  await rejects(go({ command: 'grant-admin', target: 'tester@example.com', role: 'owner', apply: true }), 'BAD_ROLE');
  await db.doc('users/tester').update({ email: 'changed@example.com' });
  await rejects(go({ command: 'grant-admin', target: 'tester@example.com', role: 'writer', apply: true }), 'IDENTITY_MISMATCH');
  await db.doc('users/tester').update({ email: 'tester@example.com', deletionStartedAt: Timestamp.now() });
  await rejects(go({ command: 'grant-admin', target: 'tester@example.com', role: 'writer', apply: true }), 'ACCOUNT_DELETING');
  assert.equal(await peek('admins/tester'), undefined);
  assert.equal(claimStore.get('tester'), undefined);
});

test('grant: the admin can mirror itself (bootstrap made admins/lars but no mirror or claim)', async () => {
  const r = await go({ command: 'grant-admin', target: ADMIN_EMAIL, role: 'writer', apply: true });
  assert.equal(r.changed, true);
  assert.equal((await peek('users/lars')).adminActive, true);
  assert.deepEqual(claimStore.get('lars'), { admin: true });
});

test('revoke: mirror and admins/ go first and together, then the claim; other claims kept; idempotent', async () => {
  await go({ command: 'grant-admin', target: 'tester@example.com', role: 'writer', apply: true });
  claimStore.set('tester', { admin: true, other: 1 });
  const dry = await go({ command: 'revoke-admin', target: 'tester@example.com' });
  assert.equal(dry.changed, false);
  assert.equal((await peek('users/tester')).adminActive, true);

  // Claims failing must not undo the Firestore revocation, which already closed every path.
  const failing = { get: claims.get, set: async () => { throw new Error('claims down'); } };
  await assert.rejects(go({ command: 'revoke-admin', target: 'tester@example.com', apply: true }, { claims: failing }));
  assert.equal((await peek('users/tester')).adminActive, false);
  assert.ok((await peek('admins/tester')).revokedAt);

  const r = await go({ command: 'revoke-admin', target: 'tester@example.com', apply: true });
  assert.equal(r.changed, true);
  assert.deepEqual(claimStore.get('tester'), { other: 1 });
  const again = await go({ command: 'revoke-admin', target: 'tester@example.com', apply: true });
  assert.equal(again.changed, false);
  assert.equal((await peek('users/tester')).status, 'approved');
});

test('revoke: refuses to revoke the account running the tool', async () => {
  await rejects(go({ command: 'revoke-admin', target: ADMIN_EMAIL, apply: true }), 'SELF_REVOKE');
});

test('grant after revoke re-activates cleanly (revokedAt removed)', async () => {
  await go({ command: 'grant-admin', target: 'tester@example.com', role: 'writer', apply: true });
  await go({ command: 'revoke-admin', target: 'tester@example.com', apply: true });
  await go({ command: 'grant-admin', target: 'tester@example.com', role: 'writer', apply: true });
  assert.equal((await peek('admins/tester')).revokedAt, undefined);
  assert.equal((await peek('users/tester')).adminActive, true);
  assert.deepEqual(claimStore.get('tester'), { admin: true });
});

// -------------------------------------------------------------- delete-account

test('delete-account: dry run by default; apply by email deletes; self and mismatch refused', async () => {
  const dry = await go({ command: 'delete-account', target: 'tester@example.com' });
  assert.equal(dry.applied, false);
  assert.ok(await peek('users/tester'));

  await rejects(go({ command: 'delete-account', target: ADMIN_EMAIL, apply: true }), 'SELF_DELETION');
  await db.doc('users/tester').update({ email: 'changed@example.com' });
  await rejects(go({ command: 'delete-account', target: 'tester@example.com', apply: true }), 'IDENTITY_MISMATCH');
  await db.doc('users/tester').update({ email: 'tester@example.com' });

  const r = await go({ command: 'delete-account', target: 'tester@example.com', apply: true });
  assert.equal(r.applied, true);
  assert.equal(await peek('users/tester'), undefined);
  assert.equal(auth.store.has('tester'), false);
  assert.deepEqual(await peek('config/counters'), { approvedUsers: 1, seatFor: 'tester' });
  assert.ok(await peek('users/lars'));
});

test('delete-account: a uid target reaches leftovers whose Auth account is already gone', async () => {
  const r = await go({ command: 'delete-account', target: 'other', apply: true });
  assert.equal(r.applied, true);
  assert.equal(await peek('users/other'), undefined);
});
