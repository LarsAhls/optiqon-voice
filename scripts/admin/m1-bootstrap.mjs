#!/usr/bin/env node
// OPTIQON Voice — Mission 1 bootstrap tool (privileged, local, dry-run by default).
//
// WHAT THIS IS
//   The one place where an administrator's decisions enter Firestore during Mission 1:
//     init             seed config/limits + config/counters, make the signed-in admin a writer,
//                      and approve the admin's own pending account (takes one seat)
//     approve <email>  pending -> approved for one tester (takes one seat)
//     revoke  <email>  approved -> revoked for one tester (releases one seat)
//     status           read-only summary of config/, admins/ and users/
//
// WHAT THIS IS NOT
//   It is not read-only and it is not a client. It runs with the Firebase Admin SDK, which
//   BYPASSES firestore.rules entirely, using the OAuth refresh token that `firebase login`
//   already stored on this machine. No new credential, no service account, no key file is
//   created — and none may be. Because the rules do not apply, the script re-implements the
//   rules' contract as preconditions and refuses anything outside it.
//
// SAFETY CONTRACT (Mission L1, plan rev. 4 §Mission A / R4.1)
//   - Exact project lock: refuses to run against anything but TARGET_PROJECT.
//   - Admin identity: the logged-in email must be ADMIN_EMAIL, resolve to an Auth user, and
//     (for approve/revoke) already be admins/{uid} with role 'writer'.
//   - Identity binding for testers: Auth `accounts:lookup` by verified email must yield a uid
//     whose users/{uid}.email equals that email. Anything else aborts.
//   - Every write is one transaction; the seat counter moves exactly once per real transition
//     and only alongside that transition (`seatFor` names the account, as the rules demand).
//   - Idempotent: repeating a command that already took effect is a no-op, reported as such.
//   - No undo, no delete, no counter reset, no field outside the rules' allow-lists.
//   - Dry run by default. `--apply` performs the writes; both modes print the plan first.
//
// USAGE
//   node scripts/admin/m1-bootstrap.mjs status  --project optiqon-voice-47498
//   node scripts/admin/m1-bootstrap.mjs init    --project optiqon-voice-47498 [--apply]
//   node scripts/admin/m1-bootstrap.mjs approve tester@example.com --project ... [--apply]
//   node scripts/admin/m1-bootstrap.mjs revoke  tester@example.com --project ... [--apply]
//
// Never prints tokens. Prints emails and uids, which is the point of a readback.

import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const TARGET_PROJECT = 'optiqon-voice-47498';
export const ADMIN_EMAIL = 'lars@optiqon.se';

/** Seeded once by `init`. Beta values; changing them is an edit here, i.e. a reviewable event. */
export const LIMITS = Object.freeze({ maxApprovedUsers: 10, uploadsEnabled: false });
export const INITIAL_COUNTERS = Object.freeze({ approvedUsers: 0, seatFor: '' });

export class BootstrapError extends Error {
  constructor(code, message) {
    super(`${code}: ${message}`);
    this.code = code;
  }
}

const abort = (code, message) => {
  throw new BootstrapError(code, message);
};

// ------------------------------------------------------------------ core (testable)

/**
 * Runs one command. Everything with a side effect or an environment dependency is injected:
 *
 *   deps.db            firebase-admin Firestore instance (emulator in tests)
 *   deps.FieldValue    firebase-admin FieldValue (for serverTimestamp)
 *   deps.identity      { email }  — who is running this, as the credential reports it
 *   deps.lookupUid     async (email) => uid | null — Firebase Auth accounts:lookup
 *   deps.log           (line) => void
 *
 * Returns { action, changed, plan, readback } and throws BootstrapError on any refusal.
 */
export async function run({ command, target, projectId, apply = false, deps }) {
  const { db, FieldValue, identity, lookupUid, log = () => {} } = deps;

  // 1. Project lock — before any read.
  if (projectId !== TARGET_PROJECT) {
    abort('WRONG_PROJECT', `refusing to run against '${projectId}'; this tool only knows '${TARGET_PROJECT}'`);
  }

  // 2. Admin identity — before any read.
  if (!identity || identity.email !== ADMIN_EMAIL) {
    abort('WRONG_ADMIN', `the credential belongs to '${identity?.email ?? 'nobody'}', not '${ADMIN_EMAIL}'`);
  }
  const adminUid = await lookupUid(ADMIN_EMAIL);
  if (!adminUid) abort('ADMIN_NOT_IN_AUTH', `${ADMIN_EMAIL} has no Firebase Auth account yet — sign in with the app first`);

  const ctx = { db, FieldValue, adminUid, adminEmail: ADMIN_EMAIL, log, apply };

  switch (command) {
    case 'status':
      return status(ctx);
    case 'init':
      return init(ctx);
    case 'approve':
      return decide(ctx, { email: target, to: 'approved', lookupUid });
    case 'revoke':
      return decide(ctx, { email: target, to: 'revoked', lookupUid });
    default:
      abort('UNKNOWN_COMMAND', `'${command}'`);
  }
}

async function status({ db, adminUid, log }) {
  const [limits, counters, admin, users] = await Promise.all([
    db.doc('config/limits').get(),
    db.doc('config/counters').get(),
    db.doc(`admins/${adminUid}`).get(),
    db.collection('users').get(),
  ]);
  const readback = {
    limits: limits.exists ? limits.data() : null,
    counters: counters.exists ? counters.data() : null,
    admin: admin.exists ? { uid: adminUid, ...admin.data() } : null,
    users: users.docs.map((d) => ({ uid: d.id, email: d.get('email'), status: d.get('status') })),
  };
  log(JSON.stringify(readback, null, 2));
  return { action: 'status', changed: false, plan: [], readback };
}

/** `init`: limits + counters + admins/{adminUid} writer + admin's own approval. */
async function init(ctx) {
  const { db, FieldValue, adminUid, adminEmail, log, apply } = ctx;

  const result = await db.runTransaction(async (tx) => {
    const [limits, counters, admin, me] = await Promise.all([
      tx.get(db.doc('config/limits')),
      tx.get(db.doc('config/counters')),
      tx.get(db.doc(`admins/${adminUid}`)),
      tx.get(db.doc(`users/${adminUid}`)),
    ]);

    // Preconditions (rules contract, re-stated because the Admin SDK will not check it).
    if (!me.exists) abort('ADMIN_NOT_REGISTERED', `users/${adminUid} does not exist — register in the app first`);
    if (me.get('email') !== adminEmail) {
      abort('IDENTITY_MISMATCH', `users/${adminUid}.email is '${me.get('email')}', expected '${adminEmail}'`);
    }
    if (limits.exists && !shallowEqual(limits.data(), LIMITS)) {
      abort('CONFIG_DIVERGED', `config/limits exists with different values ${JSON.stringify(limits.data())}`);
    }
    if (admin.exists && admin.get('role') !== 'writer') {
      abort('ADMIN_ROLE_DIVERGED', `admins/${adminUid}.role is '${admin.get('role')}', not 'writer'`);
    }
    const myStatus = me.get('status');
    if (!['pending', 'approved'].includes(myStatus)) {
      abort('ADMIN_STATUS_BLOCKED', `users/${adminUid}.status is '${myStatus}'; init only proceeds from pending or approved`);
    }

    const current = counters.exists ? counters.get('approvedUsers') : INITIAL_COUNTERS.approvedUsers;
    const maxSeats = limits.exists ? limits.get('maxApprovedUsers') : LIMITS.maxApprovedUsers;
    const needsApproval = myStatus === 'pending';

    const plan = [];
    if (!limits.exists) plan.push({ op: 'create', path: 'config/limits', data: { ...LIMITS } });
    if (!counters.exists) plan.push({ op: 'create', path: 'config/counters', data: { ...INITIAL_COUNTERS } });
    if (!admin.exists) plan.push({ op: 'create', path: `admins/${adminUid}`, data: { role: 'writer', grantedAt: '<serverTimestamp>' } });
    if (needsApproval) {
      if (current + 1 > maxSeats) abort('NO_SEAT', `approvedUsers=${current}, maxApprovedUsers=${maxSeats}`);
      plan.push({ op: 'update', path: `users/${adminUid}`, data: { status: 'approved', decidedBy: adminUid, decidedAt: '<serverTimestamp>' } });
      plan.push({ op: 'update', path: 'config/counters', data: { approvedUsers: current + 1, seatFor: adminUid } });
    }

    if (plan.length === 0 || !apply) return { plan, changed: false };

    if (!limits.exists) tx.create(db.doc('config/limits'), { ...LIMITS });
    if (!counters.exists) tx.create(db.doc('config/counters'), { ...INITIAL_COUNTERS });
    if (!admin.exists) tx.create(db.doc(`admins/${adminUid}`), { role: 'writer', grantedAt: FieldValue.serverTimestamp() });
    if (needsApproval) {
      tx.update(db.doc(`users/${adminUid}`), { status: 'approved', decidedBy: adminUid, decidedAt: FieldValue.serverTimestamp() });
      // `set` with merge rather than `update` so the fresh-counters case (created in this
      // same transaction) is also covered.
      tx.set(db.doc('config/counters'), { approvedUsers: current + 1, seatFor: adminUid }, { merge: true });
    }
    return { plan, changed: true };
  });

  printPlan(log, 'init', result.plan, apply, result.changed);
  const readback = apply ? await readbackFor(db, adminUid, adminUid) : null;
  if (readback) log(JSON.stringify(readback, null, 2));
  return { action: 'init', ...result, readback };
}

/** `approve` / `revoke` for one tester, identity-bound and seat-accounted. */
async function decide(ctx, { email, to, lookupUid }) {
  const { db, FieldValue, adminUid, log, apply } = ctx;
  if (!email || !email.includes('@')) abort('BAD_TARGET', `'${email}' is not an email address`);
  if (email === ctx.adminEmail) abort('SELF_DECISION', `use 'init' for the admin's own account; ${to} of the admin is not a bootstrap operation`);

  const uid = await lookupUid(email);
  if (!uid) abort('TESTER_NOT_IN_AUTH', `${email} has no Firebase Auth account (accounts:lookup returned nothing)`);

  const from = to === 'approved' ? 'pending' : 'approved';
  const delta = to === 'approved' ? +1 : -1;

  const result = await db.runTransaction(async (tx) => {
    const [admin, user, counters, limits] = await Promise.all([
      tx.get(db.doc(`admins/${adminUid}`)),
      tx.get(db.doc(`users/${uid}`)),
      tx.get(db.doc('config/counters')),
      tx.get(db.doc('config/limits')),
    ]);

    if (!admin.exists || admin.get('role') !== 'writer') {
      abort('NOT_A_WRITER', `admins/${adminUid} is not a writer — run 'init' first`);
    }
    if (!user.exists) abort('TESTER_NOT_REGISTERED', `users/${uid} does not exist — the tester must register in the app first`);
    if (user.get('email') !== email) {
      abort('IDENTITY_MISMATCH', `Auth resolved ${email} to ${uid}, but users/${uid}.email is '${user.get('email')}'`);
    }
    if (!counters.exists || !limits.exists) abort('NOT_INITIALISED', `config/counters or config/limits missing — run 'init' first`);

    const current = user.get('status');
    if (current === to) {
      return { plan: [], changed: false, uid, note: `users/${uid} is already '${to}'` };
    }
    if (current !== from) {
      abort('STATUS_BLOCKED', `users/${uid}.status is '${current}'; ${to} requires '${from}'`);
    }

    const count = counters.get('approvedUsers');
    const next = count + delta;
    if (next < 0) abort('COUNTER_UNDERFLOW', `approvedUsers=${count} cannot release a seat`);
    if (next > limits.get('maxApprovedUsers')) abort('NO_SEAT', `approvedUsers=${count}, maxApprovedUsers=${limits.get('maxApprovedUsers')}`);

    const plan = [
      { op: 'update', path: `users/${uid}`, data: { status: to, decidedBy: adminUid, decidedAt: '<serverTimestamp>' } },
      { op: 'update', path: 'config/counters', data: { approvedUsers: next, seatFor: uid } },
    ];
    if (!apply) return { plan, changed: false, uid };

    tx.update(db.doc(`users/${uid}`), { status: to, decidedBy: adminUid, decidedAt: FieldValue.serverTimestamp() });
    tx.update(db.doc('config/counters'), { approvedUsers: next, seatFor: uid });
    return { plan, changed: true, uid };
  });

  const action = to === 'approved' ? 'approve' : 'revoke';
  printPlan(log, `${action} ${email}`, result.plan, apply, result.changed, result.note);
  const readback = apply ? await readbackFor(db, adminUid, result.uid) : null;
  if (readback) log(JSON.stringify(readback, null, 2));
  return { action, ...result, readback };
}

async function readbackFor(db, adminUid, uid) {
  const [user, counters, admin] = await Promise.all([
    db.doc(`users/${uid}`).get(),
    db.doc('config/counters').get(),
    db.doc(`admins/${adminUid}`).get(),
  ]);
  return {
    user: {
      uid,
      status: user.get('status'),
      decidedBy: user.get('decidedBy') ?? null,
      decidedAt: user.get('decidedAt')?.toDate?.().toISOString() ?? null,
    },
    counters: counters.data() ?? null,
    admin: admin.exists ? { uid: adminUid, role: admin.get('role') } : null,
  };
}

function printPlan(log, title, plan, apply, changed, note) {
  log(`== m1-bootstrap: ${title} (${apply ? 'APPLY' : 'DRY RUN'})`);
  if (note) log(`   ${note}`);
  if (plan.length === 0) log('   nothing to do');
  for (const step of plan) log(`   ${step.op.padEnd(6)} ${step.path}  ${JSON.stringify(step.data)}`);
  log(changed ? '   -> written' : apply ? '   -> no change' : '   -> not written (add --apply)');
}

function shallowEqual(a, b) {
  const ka = Object.keys(a ?? {}).sort();
  const kb = Object.keys(b ?? {}).sort();
  return ka.length === kb.length && ka.every((k, i) => k === kb[i] && a[k] === b[k]);
}

// -------------------------------------------------------------------- CLI wiring

function parseArgs(argv) {
  const args = { apply: false, project: null, command: null, target: null };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--apply') args.apply = true;
    else if (a === '--project') args.project = argv[++i];
    else if (a.startsWith('--')) abort('BAD_FLAG', a);
    else if (!args.command) args.command = a;
    else if (!args.target) args.target = a;
    else abort('BAD_ARGS', `unexpected '${a}'`);
  }
  if (!args.command) abort('BAD_ARGS', 'usage: m1-bootstrap.mjs <status|init|approve|revoke> [email] --project <id> [--apply]');
  if (!args.project) abort('BAD_ARGS', '--project is required and must be given explicitly');
  return args;
}

/**
 * The credential `firebase login` left behind. Read, never printed, never copied anywhere.
 * The OAuth client id/secret are the Firebase CLI's own public constants, taken from the
 * installed firebase-tools so they are not duplicated here.
 */
async function cliCredential() {
  const store = JSON.parse(readFileSync(join(homedir(), '.config', 'configstore', 'firebase-tools.json'), 'utf8'));
  const refreshToken = store?.tokens?.refresh_token;
  const email = store?.user?.email;
  if (!refreshToken || !email) abort('NOT_LOGGED_IN', 'no `firebase login` session found on this machine');
  const api = await import('firebase-tools/lib/api.js');
  return { email, refreshToken, clientId: api.clientId(), clientSecret: api.clientSecret() };
}

/**
 * The Firestore client for a live run.
 *
 * `firebase-admin`'s Firestore accepts only a certificate credential or application default
 * credentials; the OAuth refresh token above is rejected with `firestore/invalid-credential`.
 * Every test passed anyway because `FIRESTORE_EMULATOR_HOST` short-circuits the credential
 * path entirely, so this branch had never run. The client underneath is the same
 * `@google-cloud/firestore` that firebase-admin re-exports (one hoisted instance, so
 * `FieldValue` sentinels are interchangeable), so building it directly with the CLI's own
 * refresh token keeps the credential, the project lock and the admin identity exactly as they
 * were. Nothing new is created: no service account, no key file, no gcloud, no changed scope.
 */
export async function liveFirestore(cred, projectId) {
  const { UserRefreshClient } = await import('google-auth-library');
  const { Firestore } = await import('@google-cloud/firestore');
  return new Firestore({
    projectId,
    authClient: new UserRefreshClient({
      clientId: cred.clientId,
      clientSecret: cred.clientSecret,
      refreshToken: cred.refreshToken,
    }),
  });
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const admin = await import('firebase-admin/app');
  const { getFirestore, FieldValue } = await import('firebase-admin/firestore');
  const { getAuth } = await import('firebase-admin/auth');

  const emulated = Boolean(process.env.FIRESTORE_EMULATOR_HOST);
  let identity;
  let db;
  if (emulated) {
    // Local rehearsal against the emulator: no credential needed, identity comes from env.
    identity = { email: process.env.M1_BOOTSTRAP_IDENTITY_EMAIL ?? '' };
    admin.initializeApp({ projectId: args.project });
    db = getFirestore();
  } else {
    const cred = await cliCredential();
    identity = { email: cred.email };
    // Auth accepts the refresh-token credential; Firestore does not — see liveFirestore().
    admin.initializeApp({
      credential: admin.refreshToken({
        type: 'authorized_user',
        client_id: cred.clientId,
        client_secret: cred.clientSecret,
        refresh_token: cred.refreshToken,
      }),
      projectId: args.project,
    });
    db = await liveFirestore(cred, args.project);
  }

  const auth = getAuth();
  const lookupUid = async (email) => {
    try {
      return (await auth.getUserByEmail(email)).uid;
    } catch (e) {
      if (e?.code === 'auth/user-not-found') return null;
      throw e;
    }
  };

  return run({
    command: args.command,
    target: args.target,
    projectId: args.project,
    apply: args.apply,
    deps: { db, FieldValue, identity, lookupUid, log: (l) => console.log(l) },
  });
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main().then(
    () => { process.exitCode = 0; },
    (e) => {
      console.error(e instanceof BootstrapError ? `ABORT ${e.message}` : e);
      process.exitCode = 1;
    }
  );
}
