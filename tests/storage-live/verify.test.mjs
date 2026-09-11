// F-a — live Storage Rules verification harness.
//
// Runs against the ephemeral verification project `optiqon-rules-verify-20260911` and
// nothing else. It is not reachable from CI: `tests/storage-live/` matches no glob in
// .github/workflows/test.yml, and no npm script points at it. It is started by hand:
//
//   FIREBASE_VERIFY_PROJECT=optiqon-rules-verify-20260911 \
//   FIREBASE_VERIFY_API_KEY=<web api key> \
//   node --test --test-concurrency=1 tests/storage-live/verify.test.mjs
//
// Credentials: none are created. The `firebase login` refresh token already on this
// machine is reused exactly as scripts/admin/m1-bootstrap.mjs does. No service-account
// key file is created, read or downloaded — needing one is a hard stop. The Web API key
// is read from env, never logged, never committed.
//
// What it measures (plan section 6): C1-C16 contract probes against the verbatim
// contract block, then probe A (2 distinct documents, 8 calls, expect PASS), probe B
// (3 distinct documents, expect DENY) and probe B-prime (5 distinct documents, expect
// DENY), three runs each. C1 (known PASS) and C8 (known DENY) are the negative method
// control: if they do not hold, A and B may not be interpreted at all.

import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { randomBytes } from 'node:crypto';

// ---------------------------------------------------------------- hard locks
//
// These run at import time, before a single network call. They are the mechanical
// reason this file cannot touch production.

const PROJECT = process.env.FIREBASE_VERIFY_PROJECT;
const EXPECTED_PROJECT = 'optiqon-rules-verify-20260911';
const PRODUCTION_PROJECT = 'optiqon-voice-47498';

if (PROJECT !== EXPECTED_PROJECT) {
  throw new Error(
    `LOCK 1: FIREBASE_VERIFY_PROJECT must be exactly "${EXPECTED_PROJECT}" (got ${JSON.stringify(PROJECT)})`
  );
}
if (PROJECT === PRODUCTION_PROJECT || JSON.stringify(process.env).includes(PRODUCTION_PROJECT)) {
  throw new Error('LOCK 2: the production project id appears in the environment — refusing to run');
}

const IDENTITIES = {
  ownerApproved: 'owner.approved@verify.invalid',
  ownerRevoked: 'owner.revoked@verify.invalid',
  adminLive: 'admin.live@verify.invalid',
  adminStale: 'admin.stale@verify.invalid',
  stranger: 'stranger@verify.invalid',
};
for (const email of Object.values(IDENTITIES)) {
  if (!/@verify\.invalid$/.test(email)) {
    throw new Error(`LOCK 3: test identity ${email} is not a synthetic @verify.invalid address`);
  }
}

const API_KEY = process.env.FIREBASE_VERIFY_API_KEY;
if (!API_KEY) throw new Error('FIREBASE_VERIFY_API_KEY is not set (read from env, never committed)');
const mask = (s) => (typeof s === 'string' && s.length > 8 ? `${s.slice(0, 4)}...${s.slice(-2)}` : '***');

const BUCKET = process.env.FIREBASE_VERIFY_BUCKET ?? `${PROJECT}.firebasestorage.app`;
const CASE_ID = 'case-v';

// ---------------------------------------------------------------- call budget
//
// Plan section 3: at most 500 storage operations and 2000 Firestore reads in a run. The
// harness counts its own calls and aborts itself at the ceiling — this is not an
// after-the-fact check.

const budget = { storageOps: 0, firestoreReads: 0, firestoreWrites: 0, signIns: 0 };
const MAX_STORAGE_OPS = 500;
const MAX_FIRESTORE_READS = 2000;

function spendStorage() {
  if (++budget.storageOps > MAX_STORAGE_OPS) {
    throw new Error(`BUDGET: storage operations exceeded ${MAX_STORAGE_OPS} — aborting run`);
  }
}
function spendRead(n = 1) {
  budget.firestoreReads += n;
  if (budget.firestoreReads > MAX_FIRESTORE_READS) {
    throw new Error(`BUDGET: firestore reads exceeded ${MAX_FIRESTORE_READS} — aborting run`);
  }
}

// ---------------------------------------------------------------- credentials
//
// Same keyless pattern as scripts/admin/m1-bootstrap.mjs:300-331.

async function cliCredential() {
  const store = JSON.parse(
    readFileSync(join(homedir(), '.config', 'configstore', 'firebase-tools.json'), 'utf8')
  );
  const refreshToken = store?.tokens?.refresh_token;
  const email = store?.user?.email;
  if (!refreshToken || !email) throw new Error('NOT_LOGGED_IN: no `firebase login` session on this machine');
  const api = await import('firebase-tools/lib/api.js');
  return { email, refreshToken, clientId: api.clientId(), clientSecret: api.clientSecret() };
}

let admin, auth, db, gcs;
const uids = {};
const tokens = {};
const passwords = {};

async function connect() {
  const cred = await cliCredential();
  const { UserRefreshClient } = await import('google-auth-library');
  const authClient = new UserRefreshClient({
    clientId: cred.clientId,
    clientSecret: cred.clientSecret,
    refreshToken: cred.refreshToken,
  });

  admin = await import('firebase-admin/app');
  const { getAuth } = await import('firebase-admin/auth');
  // Auth accepts the refresh-token credential; Firestore does not — see m1-bootstrap.
  admin.initializeApp({
    credential: admin.refreshToken({
      type: 'authorized_user',
      client_id: cred.clientId,
      client_secret: cred.clientSecret,
      refresh_token: cred.refreshToken,
    }),
    projectId: PROJECT,
  });
  auth = getAuth();

  const { Firestore } = await import('@google-cloud/firestore');
  db = new Firestore({ projectId: PROJECT, authClient });

  const { Storage } = await import('@google-cloud/storage');
  gcs = new Storage({ projectId: PROJECT, authClient });

  return cred;
}

// ---------------------------------------------------------------- seeding
//
// Every byte is generated here. Nothing is read from disk, nothing comes from a real
// user, no real screenshot, case or feedback item is involved.

function pngBytes(size) {
  const header = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  const out = new Uint8Array(Math.max(size, header.length));
  out.set(header, 0);
  return out;
}

async function ensureUser(email) {
  const password = randomBytes(18).toString('base64url'); // in memory only, never logged
  passwords[email] = password;
  let user;
  try {
    user = await auth.getUserByEmail(email);
    await auth.updateUser(user.uid, { password, emailVerified: true });
  } catch (e) {
    if (e?.code !== 'auth/user-not-found') throw e;
    user = await auth.createUser({ email, password, emailVerified: true });
  }
  return user.uid;
}

async function signIn(email) {
  budget.signIns += 1;
  const res = await fetch(
    `https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${API_KEY}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password: passwords[email], returnSecureToken: true }),
    }
  );
  if (!res.ok) throw new Error(`signIn failed for ${email}: HTTP ${res.status}`);
  const body = await res.json();
  return body.idToken;
}

async function setDoc(path, data) {
  budget.firestoreWrites += 1;
  await db.doc(path).set(data, { merge: false });
}
async function mergeDoc(path, data) {
  budget.firestoreWrites += 1;
  await db.doc(path).set(data, { merge: true });
}

async function seed() {
  for (const [role, email] of Object.entries(IDENTITIES)) {
    uids[role] = await ensureUser(email);
  }

  await auth.setCustomUserClaims(uids.adminLive, { admin: true });
  await auth.setCustomUserClaims(uids.adminStale, { admin: true });
  await auth.setCustomUserClaims(uids.ownerApproved, {});
  await auth.setCustomUserClaims(uids.ownerRevoked, {});
  await auth.setCustomUserClaims(uids.stranger, {});

  const userDoc = (role, extra = {}) => ({
    uid: uids[role],
    email: IDENTITIES[role],
    displayName: `synthetic ${role}`,
    status: 'approved',
    ...extra,
  });

  await setDoc(`users/${uids.ownerApproved}`, userDoc('ownerApproved'));
  await setDoc(`users/${uids.ownerRevoked}`, userDoc('ownerRevoked', { status: 'revoked' }));
  await setDoc(`users/${uids.adminLive}`, userDoc('adminLive', { adminActive: true }));
  await setDoc(
    `users/${uids.adminStale}`,
    userDoc('adminStale', { adminActive: true, adminActiveUntil: new Date(Date.now() - 3600_000) })
  );
  await setDoc(`users/${uids.stranger}`, userDoc('stranger'));

  const att = (ownerRole, extra = {}) => ({
    ownerUid: uids[ownerRole],
    caseId: CASE_ID,
    sha256: 'synthetic',
    maxBytes: 2097152,
    createdAt: new Date(),
    ...extra,
  });

  await setDoc(`cases/${CASE_ID}/attachments/a-ok`, att('ownerApproved'));
  await setDoc(`cases/${CASE_ID}/attachments/a-new`, att('ownerApproved'));
  await setDoc(`cases/${CASE_ID}/attachments/a-small`, att('ownerApproved', { maxBytes: 100 }));
  await setDoc(
    `cases/${CASE_ID}/attachments/a-tomb`,
    att('ownerApproved', { deleteRequestedAt: new Date() })
  );
  await setDoc(`cases/${CASE_ID}/attachments/a-rev`, att('ownerRevoked'));

  await setDoc('config/limits', { uploadsEnabled: true, maxApprovedUsers: 10 });
  await setDoc('config/counters', { approvedUsers: 1 });

  // Objects seeded server-side (bypasses rules) so a read probe measures the rule, not
  // object existence.
  const bucket = gcs.bucket(BUCKET);
  const seedObject = async (path) => {
    await bucket.file(path).save(Buffer.from(pngBytes(64)), { contentType: 'image/png' });
  };
  await seedObject(objPath(uids.ownerApproved, 'a-ok'));
  await seedObject(objPath(uids.ownerApproved, 'a-small'));
  await seedObject(objPath(uids.ownerApproved, 'a-tomb'));
  await seedObject(objPath(uids.ownerRevoked, 'a-rev'));
  await seedObject(`probe-2doc-manycalls/${uids.ownerApproved}/${CASE_ID}/a-ok`);
  await seedObject(`probe-3doc/${uids.ownerApproved}/${CASE_ID}/a-ok`);
  await seedObject(`probe-5doc/${uids.ownerApproved}/${CASE_ID}/a-ok`);

  for (const role of Object.keys(IDENTITIES)) {
    tokens[role] = await signIn(IDENTITIES[role]);
  }
}

// ---------------------------------------------------------------- storage REST
//
// Raw REST, deliberately: a 403 maps cleanly onto DENY, and getDownloadUrl() — which
// would mint a permanent token that bypasses the rules — is never called.

function objPath(ownerUid, aid, caseId = CASE_ID) {
  return `case-attachments/${ownerUid}/${caseId}/${aid}`;
}
const objUrl = (path) => `https://firebasestorage.googleapis.com/v0/b/${BUCKET}/o/${encodeURIComponent(path)}`;

async function stUpload(idToken, path, bytes, contentType = 'image/png') {
  spendStorage();
  const res = await fetch(
    `https://firebasestorage.googleapis.com/v0/b/${BUCKET}/o?uploadType=media&name=${encodeURIComponent(path)}`,
    { method: 'POST', headers: { Authorization: `Firebase ${idToken}`, 'Content-Type': contentType }, body: bytes }
  );
  return res.status;
}
async function stRead(idToken, path) {
  spendStorage();
  const res = await fetch(`${objUrl(path)}?alt=media`, {
    headers: { Authorization: `Firebase ${idToken}` },
  });
  return res.status;
}
async function stDelete(idToken, path) {
  spendStorage();
  const res = await fetch(objUrl(path), { method: 'DELETE', headers: { Authorization: `Firebase ${idToken}` } });
  return res.status;
}

// A rules DENY is 403. A 401 means the token was not accepted at all, which is a broken
// measurement rather than a denial, so it is called out separately.
function verdict(status) {
  if (status === 200 || status === 204) return 'PASS';
  if (status === 403) return 'DENY';
  if (status === 404) return 'ALLOWED_MISSING';
  return `UNEXPECTED_${status}`;
}

const results = [];
function record(id, description, got, expected) {
  results.push({ id, description, got, expected, ok: got === expected });
}
function expect(id, description, got, expected) {
  record(id, description, got, expected);
  assert.equal(got, expected, `${id} ${description}: expected ${expected}, got ${got}`);
}

// ---------------------------------------------------------------- lifecycle

before(async () => {
  const cred = await connect();
  console.log(`F-a harness: project=${PROJECT} bucket=${BUCKET} cli-identity=${cred.email} apiKey=${mask(API_KEY)}`);
  await seed();
});

after(() => {
  console.log('\n--- F-a probe results ---');
  for (const r of results) {
    console.log(`${r.ok ? 'OK  ' : 'FAIL'} ${r.id.padEnd(6)} ${r.got.padEnd(16)} (expected ${r.expected})  ${r.description}`);
  }
  console.log(
    `budget: storageOps=${budget.storageOps}/${MAX_STORAGE_OPS} firestoreWrites=${budget.firestoreWrites} signIns=${budget.signIns}`
  );
});

// ---------------------------------------------------------------- C1 / C8
//
// The negative method control. These two run first and must hold before anything else
// is interpreted.

test('C1 approved owner uploads against a valid reservation — PASS', async () => {
  const status = await stUpload(tokens.ownerApproved, objPath(uids.ownerApproved, 'a-new'), pngBytes(256));
  expect('C1', 'approved owner upload', verdict(status), 'PASS');
});

test('C8 stranger reads the owner attachment — DENY', async () => {
  const status = await stRead(tokens.stranger, objPath(uids.ownerApproved, 'a-ok'));
  expect('C8', 'cross-account read', verdict(status), 'DENY');
});

// ---------------------------------------------------------------- contract probes

test('C2 approved owner reads own attachment — PASS', async () => {
  const status = await stRead(tokens.ownerApproved, objPath(uids.ownerApproved, 'a-ok'));
  expect('C2', 'approved owner read', verdict(status), 'PASS');
});

test('C3 revoked owner uploads on an old, valid reservation — DENY', async () => {
  const status = await stUpload(tokens.ownerRevoked, objPath(uids.ownerRevoked, 'a-rev-2'), pngBytes(256));
  expect('C3', 'revoked owner upload', verdict(status), 'DENY');
});

test('C4 revoked owner reads an existing attachment — DENY', async () => {
  const status = await stRead(tokens.ownerRevoked, objPath(uids.ownerRevoked, 'a-rev'));
  expect('C4', 'revoked owner read', verdict(status), 'DENY');
});

test('C5 pending owner uploads and reads — DENY', async () => {
  await mergeDoc(`users/${uids.ownerRevoked}`, { status: 'pending' });
  const read = await stRead(tokens.ownerRevoked, objPath(uids.ownerRevoked, 'a-rev'));
  const up = await stUpload(tokens.ownerRevoked, objPath(uids.ownerRevoked, 'a-rev-3'), pngBytes(256));
  await mergeDoc(`users/${uids.ownerRevoked}`, { status: 'revoked' });
  expect('C5r', 'pending owner read', verdict(read), 'DENY');
  expect('C5u', 'pending owner upload', verdict(up), 'DENY');
});

test('C6 tombstoned attachment read — DENY although the object exists', async () => {
  const status = await stRead(tokens.ownerApproved, objPath(uids.ownerApproved, 'a-tomb'));
  expect('C6', 'tombstoned read', verdict(status), 'DENY');
});

test('C7 tombstoned attachment upload retry — DENY', async () => {
  const status = await stUpload(tokens.ownerApproved, objPath(uids.ownerApproved, 'a-tomb-2'), pngBytes(256));
  expect('C7', 'tombstoned upload retry', verdict(status), 'DENY');
});

test('C9 live admin reads the owner attachment — PASS', async () => {
  const status = await stRead(tokens.adminLive, objPath(uids.ownerApproved, 'a-ok'));
  expect('C9', 'live admin read', verdict(status), 'PASS');
});

test('C10 stale admin (adminActiveUntil passed) reads — DENY', async () => {
  const status = await stRead(tokens.adminStale, objPath(uids.ownerApproved, 'a-ok'));
  expect('C10', 'stale admin read', verdict(status), 'DENY');
});

test('C11 adminActive missing entirely (incomplete mirror) — DENY', async () => {
  const { FieldValue } = await import('@google-cloud/firestore');
  budget.firestoreWrites += 1;
  await db.doc(`users/${uids.adminLive}`).update({ adminActive: FieldValue.delete() });
  const status = await stRead(tokens.adminLive, objPath(uids.ownerApproved, 'a-ok'));
  await mergeDoc(`users/${uids.adminLive}`, { adminActive: true });
  expect('C11', 'missing mirror field', verdict(status), 'DENY');
});

test('C12 live admin whose account is revoked — DENY', async () => {
  await mergeDoc(`users/${uids.adminLive}`, { status: 'revoked' });
  const status = await stRead(tokens.adminLive, objPath(uids.ownerApproved, 'a-ok'));
  await mergeDoc(`users/${uids.adminLive}`, { status: 'approved' });
  expect('C12', 'revoked account with live admin mirror', verdict(status), 'DENY');
});

test('C13 claim without mirror, and mirror without claim — DENY both', async () => {
  await mergeDoc(`users/${uids.adminLive}`, { adminActive: false });
  const claimOnly = await stRead(tokens.adminLive, objPath(uids.ownerApproved, 'a-ok'));
  await mergeDoc(`users/${uids.adminLive}`, { adminActive: true });

  await mergeDoc(`users/${uids.stranger}`, { adminActive: true });
  const mirrorOnly = await stRead(tokens.stranger, objPath(uids.ownerApproved, 'a-ok'));
  const { FieldValue } = await import('@google-cloud/firestore');
  budget.firestoreWrites += 1;
  await db.doc(`users/${uids.stranger}`).update({ adminActive: FieldValue.delete() });

  expect('C13a', 'claim present, mirror false', verdict(claimOnly), 'DENY');
  expect('C13b', 'mirror present, claim absent', verdict(mirrorOnly), 'DENY');
});

test('C14 wrong MIME, over 2 MiB, over att().maxBytes — DENY each', async () => {
  const mime = await stUpload(
    tokens.ownerApproved,
    objPath(uids.ownerApproved, 'a-new-mime'),
    pngBytes(256),
    'text/plain'
  );
  const tooBig = await stUpload(tokens.ownerApproved, objPath(uids.ownerApproved, 'a-new-big'), pngBytes(3_000_000));
  const overMax = await stUpload(tokens.ownerApproved, objPath(uids.ownerApproved, 'a-small'), pngBytes(200));
  expect('C14a', 'wrong MIME', verdict(mime), 'DENY');
  expect('C14b', 'over 2 MiB', verdict(tooBig), 'DENY');
  expect('C14c', 'over att().maxBytes', verdict(overMax), 'DENY');
});

test('C15 overwriting an existing object — DENY', async () => {
  const status = await stUpload(tokens.ownerApproved, objPath(uids.ownerApproved, 'a-ok'), pngBytes(256));
  expect('C15', 'overwrite existing object', verdict(status), 'DENY');
});

test('C16 client delete — DENY', async () => {
  const status = await stDelete(tokens.ownerApproved, objPath(uids.ownerApproved, 'a-ok'));
  expect('C16', 'client delete', verdict(status), 'DENY');
});

// ---------------------------------------------------------------- A / B / B-prime
//
// The decisive measurement. Same user, same object, same token — the only variable that
// differs between A and B is the number of DISTINCT Firestore documents the rule reads.
// Three runs each, because a single observation cannot rule out a one-off.

test('probe A — 2 distinct documents, 8 calls — PASS x3', async () => {
  const path = `probe-2doc-manycalls/${uids.ownerApproved}/${CASE_ID}/a-ok`;
  for (let i = 1; i <= 3; i++) {
    const status = await stRead(tokens.ownerApproved, path);
    expect(`A${i}`, '2 distinct documents / 8 calls', verdict(status), 'PASS');
  }
});

test('probe B — 3 distinct documents — DENY x3', async () => {
  const path = `probe-3doc/${uids.ownerApproved}/${CASE_ID}/a-ok`;
  for (let i = 1; i <= 3; i++) {
    const status = await stRead(tokens.ownerApproved, path);
    expect(`B${i}`, '3 distinct documents', verdict(status), 'DENY');
  }
});

test('probe B-prime — 5 distinct documents — DENY x3', async () => {
  // Read as adminLive so users/{ownerUid} and users/{request.auth.uid} are genuinely two
  // different documents; owner-as-reader would collapse them and make it four, not five.
  const path = `probe-5doc/${uids.ownerApproved}/${CASE_ID}/a-ok`;
  for (let i = 1; i <= 3; i++) {
    const status = await stRead(tokens.adminLive, path);
    expect(`Bp${i}`, '5 distinct documents', verdict(status), 'DENY');
  }
});
