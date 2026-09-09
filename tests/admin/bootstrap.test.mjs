// Emulator tests for the privileged Mission 1 bootstrap tool.
//
// The rules suites prove what a *client* can do. This tool bypasses the rules, so those
// suites prove nothing about it; these tests drive `run()` with the Admin SDK against the
// emulator and assert the tool's own preconditions, refusals, idempotence and seat
// accounting — including the negative cases the plan names: wrong project id, wrong admin
// identity, status != pending, repeated run, counter moving exactly once.
import { test, before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { initializeApp, deleteApp } from 'firebase-admin/app';
import { getFirestore, FieldValue, Timestamp } from 'firebase-admin/firestore';
import { run, BootstrapError, TARGET_PROJECT, ADMIN_EMAIL, LIMITS } from '../../scripts/admin/m1-bootstrap.mjs';

// emulators:exec sets FIRESTORE_EMULATOR_HOST; the Admin SDK follows it and needs no credential.
assert.ok(process.env.FIRESTORE_EMULATOR_HOST, 'these tests must run under `npm run test:rules`');

// The tool is locked to the real project id, so the emulator app has to carry that id too.
// Nothing leaves the machine: FIRESTORE_EMULATOR_HOST is set for the whole process.
const app = initializeApp({ projectId: TARGET_PROJECT }, 'bootstrap-test');
const db = getFirestore(app);

const AUTH = { [ADMIN_EMAIL]: 'lars', 'tester@example.com': 'tester', 'ghost@example.com': 'ghost' };
const lookupUid = async (email) => AUTH[email] ?? null;
const deps = (overrides = {}) => ({ db, FieldValue, identity: { email: ADMIN_EMAIL }, lookupUid, log: () => {}, ...overrides });
const go = (args) => run({ projectId: TARGET_PROJECT, deps: deps(), ...args });

async function clear() {
  const batch = db.batch();
  for (const col of ['users', 'admins', 'config']) {
    (await db.collection(col).get()).docs.forEach((d) => batch.delete(d.ref));
  }
  await batch.commit();
}

async function seedUser(uid, email, status) {
  await db.doc(`users/${uid}`).set({ uid, email, displayName: uid, status, createdAt: Timestamp.now() });
}

/** Post-init world: admin approved + writer, one pending tester, one seat taken. */
async function seedInitialised() {
  await seedUser('lars', ADMIN_EMAIL, 'pending');
  await seedUser('tester', 'tester@example.com', 'pending');
  await go({ command: 'init', apply: true });
}

const rejects = async (promise, code) => {
  await assert.rejects(promise, (e) => {
    assert.ok(e instanceof BootstrapError, `expected BootstrapError, got ${e}`);
    assert.equal(e.code, code);
    return true;
  });
};

before(async () => { await clear(); });
beforeEach(async () => { await clear(); });
after(async () => { await clear(); await deleteApp(app); });

// ----------------------------------------------------------------- refusals first

test('refuses any project id but the target, before touching Firestore', async () => {
  await seedUser('lars', ADMIN_EMAIL, 'pending');
  await rejects(run({ command: 'init', projectId: 'optiqon-voice', apply: true, deps: deps() }), 'WRONG_PROJECT');
  await rejects(run({ command: 'init', projectId: 'optioqon-voice', apply: true, deps: deps() }), 'WRONG_PROJECT');
  assert.equal((await db.doc('config/limits').get()).exists, false);
  assert.equal((await db.doc('users/lars').get()).get('status'), 'pending');
});

test('refuses a credential that is not the admin identity', async () => {
  await seedUser('lars', ADMIN_EMAIL, 'pending');
  await rejects(go({ command: 'init', apply: true, deps: deps({ identity: { email: 'someone@else.example' } }) }), 'WRONG_ADMIN');
  await rejects(go({ command: 'init', apply: true, deps: deps({ identity: null }) }), 'WRONG_ADMIN');
  assert.equal((await db.doc('admins/lars').get()).exists, false);
});

test('refuses when the admin email has no Auth account or no users/ document', async () => {
  await rejects(go({ command: 'init', apply: true, deps: deps({ lookupUid: async () => null }) }), 'ADMIN_NOT_IN_AUTH');
  await rejects(go({ command: 'init', apply: true }), 'ADMIN_NOT_REGISTERED');
});

test('refuses when the Auth uid and the users/ document disagree about the email', async () => {
  await seedUser('lars', 'not-lars@example.com', 'pending');
  await rejects(go({ command: 'init', apply: true }), 'IDENTITY_MISMATCH');
});

// ------------------------------------------------------------------------- init

test('dry run plans everything and writes nothing', async () => {
  await seedUser('lars', ADMIN_EMAIL, 'pending');
  const r = await go({ command: 'init' });
  assert.equal(r.changed, false);
  assert.deepEqual(r.plan.map((s) => s.path), ['config/limits', 'config/counters', 'admins/lars', 'users/lars', 'config/counters']);
  assert.equal((await db.doc('config/limits').get()).exists, false);
  assert.equal((await db.doc('users/lars').get()).get('status'), 'pending');
});

test('init seeds config, grants writer, approves the admin and takes exactly one seat', async () => {
  await seedUser('lars', ADMIN_EMAIL, 'pending');
  const r = await go({ command: 'init', apply: true });
  assert.equal(r.changed, true);

  assert.deepEqual((await db.doc('config/limits').get()).data(), LIMITS);
  assert.deepEqual((await db.doc('config/counters').get()).data(), { approvedUsers: 1, seatFor: 'lars' });
  const admin = (await db.doc('admins/lars').get()).data();
  assert.equal(admin.role, 'writer');
  assert.ok(admin.grantedAt instanceof Timestamp);
  const me = (await db.doc('users/lars').get()).data();
  assert.equal(me.status, 'approved');
  assert.equal(me.decidedBy, 'lars');
  assert.ok(me.decidedAt instanceof Timestamp);
  assert.deepEqual(Object.keys(me).sort(), ['createdAt', 'decidedAt', 'decidedBy', 'displayName', 'email', 'status', 'uid']);
  assert.equal(r.readback.counters.approvedUsers, 1);
});

test('repeating init is a no-op: the counter does not move again', async () => {
  await seedUser('lars', ADMIN_EMAIL, 'pending');
  await go({ command: 'init', apply: true });
  const again = await go({ command: 'init', apply: true });
  assert.equal(again.changed, false);
  assert.deepEqual(again.plan, []);
  assert.deepEqual((await db.doc('config/counters').get()).data(), { approvedUsers: 1, seatFor: 'lars' });
});

test('init refuses when config/limits exists with other values instead of overwriting it', async () => {
  await seedUser('lars', ADMIN_EMAIL, 'pending');
  await db.doc('config/limits').set({ maxApprovedUsers: 99, uploadsEnabled: true });
  await rejects(go({ command: 'init', apply: true }), 'CONFIG_DIVERGED');
  assert.equal((await db.doc('config/limits').get()).get('maxApprovedUsers'), 99);
  assert.equal((await db.doc('admins/lars').get()).exists, false);
});

test('init refuses when the admin account is revoked or rejected', async () => {
  await seedUser('lars', ADMIN_EMAIL, 'revoked');
  await rejects(go({ command: 'init', apply: true }), 'ADMIN_STATUS_BLOCKED');
});

// --------------------------------------------------------------- approve / revoke

test('approve moves a pending tester to approved and the counter up by exactly one', async () => {
  await seedInitialised();
  const r = await go({ command: 'approve', target: 'tester@example.com', apply: true });
  assert.equal(r.changed, true);
  const user = (await db.doc('users/tester').get()).data();
  assert.equal(user.status, 'approved');
  assert.equal(user.decidedBy, 'lars');
  assert.ok(user.decidedAt instanceof Timestamp);
  assert.deepEqual((await db.doc('config/counters').get()).data(), { approvedUsers: 2, seatFor: 'tester' });
});

test('approve dry run plans the two writes and changes nothing', async () => {
  await seedInitialised();
  const r = await go({ command: 'approve', target: 'tester@example.com' });
  assert.equal(r.changed, false);
  assert.deepEqual(r.plan.map((s) => s.path), ['users/tester', 'config/counters']);
  assert.equal((await db.doc('users/tester').get()).get('status'), 'pending');
  assert.equal((await db.doc('config/counters').get()).get('approvedUsers'), 1);
});

test('approving twice is a no-op and the counter stays put', async () => {
  await seedInitialised();
  await go({ command: 'approve', target: 'tester@example.com', apply: true });
  const again = await go({ command: 'approve', target: 'tester@example.com', apply: true });
  assert.equal(again.changed, false);
  assert.equal((await db.doc('config/counters').get()).get('approvedUsers'), 2);
});

test('approve refuses a tester whose status is not pending', async () => {
  await seedInitialised();
  await db.doc('users/tester').update({ status: 'revoked' });
  await rejects(go({ command: 'approve', target: 'tester@example.com', apply: true }), 'STATUS_BLOCKED');
  assert.equal((await db.doc('config/counters').get()).get('approvedUsers'), 1);
});

test('approve refuses when the Auth uid does not match the users/ email (plus-alias trap)', async () => {
  await seedInitialised();
  // Auth says tester@example.com -> 'tester', but the document under that uid carries a
  // different address: someone else registered under it, or the alias resolved elsewhere.
  await db.doc('users/tester').update({ email: 'tester+alias@example.com' });
  await rejects(go({ command: 'approve', target: 'tester@example.com', apply: true }), 'IDENTITY_MISMATCH');
});

test('approve refuses an email with no Auth account, and one with Auth but no registration', async () => {
  await seedInitialised();
  await rejects(go({ command: 'approve', target: 'nobody@example.com', apply: true }), 'TESTER_NOT_IN_AUTH');
  await rejects(go({ command: 'approve', target: 'ghost@example.com', apply: true }), 'TESTER_NOT_REGISTERED');
});

test('approve refuses when the admin is not (yet) a writer', async () => {
  await seedUser('lars', ADMIN_EMAIL, 'approved');
  await seedUser('tester', 'tester@example.com', 'pending');
  await db.doc('config/limits').set({ ...LIMITS });
  await db.doc('config/counters').set({ approvedUsers: 1, seatFor: 'lars' });
  await rejects(go({ command: 'approve', target: 'tester@example.com', apply: true }), 'NOT_A_WRITER');
  await db.doc('admins/lars').set({ role: 'reader', grantedAt: Timestamp.now() });
  await rejects(go({ command: 'approve', target: 'tester@example.com', apply: true }), 'NOT_A_WRITER');
});

test('approve refuses when every seat is taken', async () => {
  await seedInitialised();
  await db.doc('config/counters').update({ approvedUsers: LIMITS.maxApprovedUsers });
  await rejects(go({ command: 'approve', target: 'tester@example.com', apply: true }), 'NO_SEAT');
  assert.equal((await db.doc('users/tester').get()).get('status'), 'pending');
});

test('revoke releases the seat, and revoking again is a no-op', async () => {
  await seedInitialised();
  await go({ command: 'approve', target: 'tester@example.com', apply: true });
  const r = await go({ command: 'revoke', target: 'tester@example.com', apply: true });
  assert.equal(r.changed, true);
  assert.equal((await db.doc('users/tester').get()).get('status'), 'revoked');
  assert.deepEqual((await db.doc('config/counters').get()).data(), { approvedUsers: 1, seatFor: 'tester' });
  const again = await go({ command: 'revoke', target: 'tester@example.com', apply: true });
  assert.equal(again.changed, false);
  assert.equal((await db.doc('config/counters').get()).get('approvedUsers'), 1);
});

test('revoke refuses a pending tester (there is no seat to release) and never resets the counter', async () => {
  await seedInitialised();
  await rejects(go({ command: 'revoke', target: 'tester@example.com', apply: true }), 'STATUS_BLOCKED');
  assert.equal((await db.doc('config/counters').get()).get('approvedUsers'), 1);
});

test('the admin cannot approve or revoke their own account through decide', async () => {
  await seedInitialised();
  await rejects(go({ command: 'revoke', target: ADMIN_EMAIL, apply: true }), 'SELF_DECISION');
  assert.equal((await db.doc('users/lars').get()).get('status'), 'approved');
});

test('there is no undo, delete or reset command', async () => {
  await seedInitialised();
  for (const command of ['undo', 'delete', 'reset', 'reset-counter']) {
    await rejects(go({ command, target: 'tester@example.com', apply: true }), 'UNKNOWN_COMMAND');
  }
});

test('status is read-only', async () => {
  await seedInitialised();
  const r = await go({ command: 'status' });
  assert.equal(r.changed, false);
  assert.equal(r.readback.counters.approvedUsers, 1);
  assert.equal(r.readback.admin.role, 'writer');
  assert.deepEqual(r.readback.users.map((u) => u.uid).sort(), ['lars', 'tester']);
});
