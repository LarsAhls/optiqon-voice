#!/usr/bin/env node
// OPTIQON Voice — FS-S468 admin tool (privileged, local, dry run by default).
//
// COMMANDS
//   backfill-generation            users/ without `approvalGeneration` get an explicit 0 — the
//                                  value the rules already read for a missing field — so every
//                                  account carries it. An existing value is never changed, and a
//                                  value that is not a non-negative integer is reported, not fixed.
//   grant-admin <email> --role writer|reader [--until <ISO time>]
//                                  makes an approved account an administrator: the Auth `admin`
//                                  claim, admins/{uid} (Firestore rules) and the mirrored
//                                  users/{uid}.adminActive (Storage rules) — both documents in
//                                  one transaction, so the two liveness sources cannot disagree.
//   revoke-admin <email>           the reverse: mirror and admins/ first, in one transaction
//                                  (effective at once), then the claim (effective at the next
//                                  token refresh; the mirror has already closed every path).
//   delete-account <email|uid>     M4: deletes the account and everything linked to it
//                                  (server/account-deletion.mjs). No self-deletion.
//   reply <caseId> --event <id> --body-file <path>
//                                  a public support reply (server/support.mjs): one event, one
//                                  publicRev/activityRev bump, makes the case unread for the owner.
//   set-status <caseId> --event <id> --status <S> [--release-tag T --version-code N --distributed-at ISO]
//                                  a public status change; the evidence flags are Levererat's
//                                  delivery evidence and are refused for any other status.
//   note <caseId> --event <id> --body-file <path>
//                                  an internal note: invisible to the owner, no bump, no unread,
//                                  never a notification.
//   close <caseId>                 closes the case (one way; starts the closed-case retention clock).
//
// Support commands act as ADMIN_EMAIL and go through the same writer check the rules apply
// (verified Auth user + approved users/ + live writer admins/). The Admin SDK bypasses the rules,
// so every invariant lives in server/support.mjs. `--event` is the idempotency key: re-running the
// same command is reported as already done and bumps nothing; the same id with other content is
// refused.
//
// SAFETY CONTRACT — the same as scripts/admin/m1-bootstrap.mjs:
//   - Exact project lock, before any read: TARGET_PROJECT only.
//   - The credential must be ADMIN_EMAIL, and that account must exist in Auth.
//   - Uses the refresh token `firebase login` already stored. No service account, no key file.
//   - Dry run by default; `--apply` writes. Both print the plan first.
//   - Idempotent: a command that already took effect is reported and changes nothing.
//
// NOT RUN against production as part of FS-S468. Running it live is FS-G section E, with its
// own approval.
//
// USAGE
//   node scripts/admin/voice-admin.mjs backfill-generation --project optiqon-voice-47498 [--apply]
//   node scripts/admin/voice-admin.mjs grant-admin lars@optiqon.se --role writer --project ... [--apply]
//   node scripts/admin/voice-admin.mjs revoke-admin someone@example.com --project ... [--apply]
//   node scripts/admin/voice-admin.mjs delete-account tester@example.com --project ... [--apply]
//   node scripts/admin/voice-admin.mjs reply <caseId> --event r-1 --body-file reply.txt --project ... [--apply]
//   node scripts/admin/voice-admin.mjs set-status <caseId> --event s-1 --status Planerat --project ... [--apply]
//   node scripts/admin/voice-admin.mjs close <caseId> --project ... [--apply]

import { fileURLToPath } from 'node:url';
import { ADMIN_EMAIL, cliCredential, liveFirestore, TARGET_PROJECT } from './m1-bootstrap.mjs';
import { authAdapter, deleteAccount } from '../../server/account-deletion.mjs';
import { caseOpen, closeCase, internalNote, publicReply, statusChange } from '../../server/support.mjs';
import { TARGET_BUCKET } from '../../server/config.mjs';

export { TARGET_PROJECT };

export class AdminToolError extends Error {
  constructor(code, message) {
    super(`${code}: ${message}`);
    this.name = 'AdminToolError';
    this.code = code;
  }
}

const abort = (code, message) => {
  throw new AdminToolError(code, message);
};

const ROLES = ['writer', 'reader'];

/**
 * deps: {
 *   db, FieldValue, identity: { email }, log,
 *   lookupUid(email) → uid | null,
 *   claims: { get(uid) → object, set(uid, claims) },     // grant/revoke
 *   auth,                                                // delete-account (see account-deletion.mjs)
 *   openStorage() → storage adapter                      // delete-account only, called on demand
 * }
 *
 * Storage is opened only by the command that needs it. Every other command runs without a
 * Cloud Storage client ever being constructed, so a Storage credential problem cannot stop a
 * Firestore-only command (FS-G C.4 was stopped exactly that way).
 */
export async function run({
  command, target, role, until, eventId, body, toStatus, evidence, projectId, apply = false, deps,
}) {
  const { identity, lookupUid, log = () => {} } = deps;
  if (projectId !== TARGET_PROJECT) {
    abort('WRONG_PROJECT', `refusing to run against '${projectId}'; this tool only knows '${TARGET_PROJECT}'`);
  }
  if (!identity || identity.email !== ADMIN_EMAIL) {
    abort('WRONG_ADMIN', `the credential belongs to '${identity?.email ?? 'nobody'}', not '${ADMIN_EMAIL}'`);
  }
  const adminUid = await lookupUid(ADMIN_EMAIL);
  if (!adminUid) abort('ADMIN_NOT_IN_AUTH', `${ADMIN_EMAIL} has no Firebase Auth account`);
  const ctx = { ...deps, log, apply, adminUid, projectId };

  switch (command) {
    case 'backfill-generation':
      return backfillGeneration(ctx);
    case 'grant-admin':
      return grantAdmin(ctx, { email: target, role, until });
    case 'revoke-admin':
      return revokeAdmin(ctx, { email: target });
    case 'delete-account':
      return deleteAccountCommand(ctx, { target });
    case 'reply':
    case 'set-status':
    case 'note':
    case 'close':
      return supportCommand(ctx, command, { caseId: target, eventId, body, toStatus, evidence });
    default:
      abort('UNKNOWN_COMMAND', `'${command}'`);
  }
}

function report(log, title, plan, apply, changed) {
  log(`== voice-admin: ${title} (${apply ? 'APPLY' : 'DRY RUN'})`);
  if (plan.length === 0) log('   nothing to do');
  for (const step of plan) log(`   ${JSON.stringify(step)}`);
  log(changed ? '   -> written' : apply ? '   -> no change' : '   -> not written (add --apply)');
}

// ------------------------------------------------------------- backfill-generation

async function backfillGeneration({ db, log, apply }) {
  const users = await db.collection('users').orderBy('__name__').get();
  const plan = [];
  const invalid = [];
  for (const d of users.docs) {
    if (!d.data().hasOwnProperty('approvalGeneration')) {
      plan.push({ op: 'update', path: `users/${d.id}`, data: { approvalGeneration: 0 } });
      continue;
    }
    const g = d.get('approvalGeneration');
    if (!Number.isInteger(g) || g < 0) invalid.push({ uid: d.id, approvalGeneration: g });
  }
  let written = 0;
  if (apply) {
    // Each write re-reads its document: a field that appeared since the scan (an approval
    // that landed in between) is kept, never overwritten.
    for (const step of plan) {
      const changed = await db.runTransaction(async (tx) => {
        const ref = db.doc(step.path);
        const snap = await tx.get(ref);
        if (!snap.exists || snap.data().hasOwnProperty('approvalGeneration')) return false;
        tx.update(ref, { approvalGeneration: 0 });
        return true;
      });
      if (changed) written += 1;
    }
  }
  report(log, 'backfill-generation', plan, apply, written > 0);
  if (invalid.length > 0) log(`   INVALID (not changed): ${JSON.stringify(invalid)}`);
  return { action: 'backfill-generation', plan, invalid, written, changed: written > 0 };
}

// ------------------------------------------------------------ grant / revoke admin

async function resolveApproved(ctx, email) {
  if (typeof email !== 'string' || !email.includes('@')) abort('BAD_TARGET', `'${email}' is not an email address`);
  const uid = await ctx.lookupUid(email);
  if (!uid) abort('NOT_IN_AUTH', `${email} has no Firebase Auth account`);
  return uid;
}

function parseUntil(until) {
  if (until == null) return null;
  const ms = Date.parse(until);
  if (!Number.isFinite(ms)) abort('BAD_UNTIL', `'${until}' is not a time`);
  if (ms <= Date.now()) abort('BAD_UNTIL', `'${until}' is not in the future`);
  return new Date(ms);
}

async function grantAdmin(ctx, { email, role, until }) {
  const { db, FieldValue, claims, log, apply } = ctx;
  if (!ROLES.includes(role)) abort('BAD_ROLE', `--role must be one of ${ROLES.join(', ')}`);
  const untilDate = parseUntil(until);
  const uid = await resolveApproved(ctx, email);

  const result = await db.runTransaction(async (tx) => {
    const [user, admin] = await Promise.all([tx.get(db.doc(`users/${uid}`)), tx.get(db.doc(`admins/${uid}`))]);
    if (!user.exists || user.get('email') !== email) abort('IDENTITY_MISMATCH', `users/${uid} does not carry ${email}`);
    if (user.get('status') !== 'approved') abort('NOT_APPROVED', `users/${uid}.status is '${user.get('status')}'`);
    if (user.get('deletionStartedAt') != null) abort('ACCOUNT_DELETING', `users/${uid} is being deleted`);

    const ad = admin.exists ? admin.data() : null;
    const untilMs = untilDate?.getTime() ?? null;
    const same = ad && ad.revokedAt == null && ad.role === role
      && (ad.activeUntil?.toMillis?.() ?? null) === untilMs
      && user.get('adminActive') === true
      && (user.get('adminActiveUntil')?.toMillis?.() ?? null) === untilMs;
    if (same) return { plan: [], changed: false };

    const adminData = { role, grantedAt: FieldValue.serverTimestamp(), grantedBy: ctx.adminUid, revokedAt: FieldValue.delete() };
    adminData.activeUntil = untilDate ?? FieldValue.delete();
    const mirror = { adminActive: true, adminActiveUntil: untilDate ?? FieldValue.delete() };
    const plan = [
      { op: 'set-merge', path: `admins/${uid}`, data: { role, activeUntil: untilDate?.toISOString() ?? null, revokedAt: null } },
      { op: 'update', path: `users/${uid}`, data: { adminActive: true, adminActiveUntil: untilDate?.toISOString() ?? null } },
    ];
    if (!apply) return { plan, changed: false };
    tx.set(db.doc(`admins/${uid}`), adminData, { merge: true });
    tx.update(db.doc(`users/${uid}`), mirror);
    return { plan, changed: true };
  });

  const current = (await claims.get(uid)) ?? {};
  const claimPlan = current.admin === true ? [] : [{ op: 'claim', uid, data: { admin: true } }];
  if (apply && claimPlan.length > 0) await claims.set(uid, { ...current, admin: true });
  const plan = [...result.plan, ...claimPlan];
  const changed = result.changed || (apply && claimPlan.length > 0);
  report(log, `grant-admin ${email} (${role})`, plan, apply, changed);
  return { action: 'grant-admin', uid, plan, changed };
}

async function revokeAdmin(ctx, { email }) {
  const { db, FieldValue, claims, log, apply } = ctx;
  const uid = await resolveApproved(ctx, email);
  if (uid === ctx.adminUid) abort('SELF_REVOKE', 'the account running this cannot revoke itself');

  const result = await db.runTransaction(async (tx) => {
    const [user, admin] = await Promise.all([tx.get(db.doc(`users/${uid}`)), tx.get(db.doc(`admins/${uid}`))]);
    const plan = [];
    if (admin.exists && admin.get('revokedAt') == null) plan.push({ op: 'update', path: `admins/${uid}`, data: { revokedAt: '<serverTimestamp>' } });
    if (user.exists && user.get('adminActive') !== false) plan.push({ op: 'update', path: `users/${uid}`, data: { adminActive: false } });
    if (!apply || plan.length === 0) return { plan, changed: false };
    if (admin.exists && admin.get('revokedAt') == null) tx.update(db.doc(`admins/${uid}`), { revokedAt: FieldValue.serverTimestamp() });
    if (user.exists && user.get('adminActive') !== false) tx.update(db.doc(`users/${uid}`), { adminActive: false, adminActiveUntil: FieldValue.delete() });
    return { plan, changed: true };
  });

  const current = (await claims.get(uid)) ?? {};
  const claimPlan = current.admin === undefined ? [] : [{ op: 'claim', uid, data: { admin: '<removed>' } }];
  if (apply && claimPlan.length > 0) {
    const { admin: _drop, ...rest } = current;
    await claims.set(uid, rest);
  }
  const plan = [...result.plan, ...claimPlan];
  const changed = result.changed || (apply && claimPlan.length > 0);
  report(log, `revoke-admin ${email}`, plan, apply, changed);
  return { action: 'revoke-admin', uid, plan, changed };
}

// ------------------------------------------------------------------ delete-account

async function deleteAccountCommand(ctx, { target }) {
  const { log, apply } = ctx;
  if (typeof target !== 'string' || target.length === 0) abort('BAD_TARGET', 'an email or uid is required');
  let uid = target;
  if (target.includes('@')) {
    uid = await ctx.lookupUid(target);
    if (!uid) abort('NOT_IN_AUTH', `${target} has no Firebase Auth account; pass the uid to delete what is left`);
    const user = await ctx.db.doc(`users/${uid}`).get();
    if (user.exists && user.get('email') !== target) abort('IDENTITY_MISMATCH', `users/${uid} does not carry ${target}`);
  }
  const storage = await ctx.openStorage();
  const r = await deleteAccount({ ...ctx, storage, actorUid: ctx.adminUid, apply }, uid);
  log(`== voice-admin: delete-account ${uid} (${apply ? 'APPLY' : 'DRY RUN'})`);
  log(JSON.stringify(r.inventory, null, 2));
  log(r.applied ? (r.alreadyDeleted ? '   -> nothing left to delete' : '   -> deleted') : '   -> not written (add --apply)');
  return { action: 'delete-account', uid, ...r };
}

// ------------------------------------------------------------------------ support ops

const SUPPORT_OPS = {
  reply: publicReply,
  'set-status': statusChange,
  note: internalNote,
  close: closeCase,
};

/**
 * Dry run: reads the case and the event id and prints what would be written; the support core is
 * not called. Apply: the core decides everything inside its transaction (writer check, case
 * state, transition, idempotency), so a dry run that looked fine can still be refused.
 */
async function supportCommand(ctx, command, { caseId, eventId, body, toStatus, evidence }) {
  const { db, log, apply, adminUid } = ctx;
  if (typeof caseId !== 'string' || caseId.length === 0) abort('BAD_TARGET', 'a caseId is required');
  if (command !== 'close' && !eventId) abort('BAD_ARGS', '--event <id> is required (it is the idempotency key)');
  if ((command === 'reply' || command === 'note') && typeof body !== 'string') abort('BAD_ARGS', '--body-file is required');
  if (command === 'set-status' && !toStatus) abort('BAD_ARGS', '--status is required');
  const ev = evidence == null ? undefined : { ...evidence, verifiedBy: adminUid };
  const args = { caseId, eventId, actorUid: adminUid, body, toStatus, evidence: ev };

  const snap = await db.doc(`cases/${caseId}`).get();
  const c = snap.exists ? snap.data() : null;
  const existing = eventId ? await db.doc(`cases/${caseId}/events/${eventId}`).get() : null;
  const step = {
    op: command, caseId, eventId: eventId ?? null, actorUid: adminUid,
    case: c ? { state: c.state, status: c.statusCache ?? null, open: caseOpen(c), closed: c.closedAt != null } : 'missing',
    eventExists: existing?.exists === true,
  };
  if (command === 'set-status') step.toStatus = toStatus;
  if (body != null) step.bodyChars = body.length;
  if (ev) step.evidence = { ...ev, distributedAt: String(ev.distributedAt) };

  if (!apply) {
    report(log, `${command} ${caseId}`, [step], false, false);
    return { action: command, caseId, applied: false, plan: step };
  }
  const r = await SUPPORT_OPS[command]({ db, FieldValue: ctx.FieldValue, auth: ctx.auth, now: ctx.now }, args);
  report(log, `${command} ${caseId}`, [step], true, r.outcome === 'written');
  return { action: command, caseId, applied: true, outcome: r.outcome };
}

// ----------------------------------------------------------------------- CLI wiring

function parseArgs(argv) {
  const args = {
    apply: false, project: null, command: null, target: null, role: null, until: null,
    event: null, bodyFile: null, status: null, releaseTag: null, versionCode: null, distributedAt: null,
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--apply') args.apply = true;
    else if (a === '--project') args.project = argv[++i];
    else if (a === '--role') args.role = argv[++i];
    else if (a === '--until') args.until = argv[++i];
    else if (a === '--event') args.event = argv[++i];
    else if (a === '--body-file') args.bodyFile = argv[++i];
    else if (a === '--status') args.status = argv[++i];
    else if (a === '--release-tag') args.releaseTag = argv[++i];
    else if (a === '--version-code') args.versionCode = argv[++i];
    else if (a === '--distributed-at') args.distributedAt = argv[++i];
    else if (a.startsWith('--')) abort('BAD_FLAG', a);
    else if (!args.command) args.command = a;
    else if (!args.target) args.target = a;
    else abort('BAD_ARGS', `unexpected '${a}'`);
  }
  if (!args.command) abort('BAD_ARGS', 'usage: voice-admin.mjs <command> [target] --project <id> [--apply]');
  if (!args.project) abort('BAD_ARGS', '--project is required and must be given explicitly');
  return args;
}

/** Levererat evidence from the flags, or undefined when none is given. */
export function evidenceFromArgs({ releaseTag, versionCode, distributedAt }) {
  if (releaseTag == null && versionCode == null && distributedAt == null) return undefined;
  const at = new Date(distributedAt ?? '');
  if (!releaseTag || !/^\d+$/.test(String(versionCode ?? '')) || Number.isNaN(at.getTime())) {
    abort('BAD_EVIDENCE', '--release-tag, --version-code <int> and --distributed-at <ISO time> go together');
  }
  return { releaseTag, versionCode: Number(versionCode), distributedAt: at };
}

/**
 * The Storage adapter for a live delete-account run.
 *
 * `firebase-admin`'s Storage, like its Firestore, accepts only a certificate credential or
 * application default credentials; the `firebase login` refresh token is rejected ("Failed to
 * initialize Google Cloud Storage client with the available credential"). So, exactly as
 * liveFirestore() does, the client underneath — the `@google-cloud/storage` firebase-admin
 * already installs — is built directly with the CLI's own refresh token. No ADC, no key file,
 * no service account, no GOOGLE_APPLICATION_CREDENTIALS. The adapter only lists, checks and
 * deletes (server/storage.mjs); it never reads bytes.
 */
export async function liveBucket(cred, projectId) {
  if (projectId !== TARGET_PROJECT) abort('WRONG_PROJECT', `'${projectId}'`);
  const { UserRefreshClient } = await import('google-auth-library');
  const { Storage } = await import('@google-cloud/storage');
  const client = new Storage({
    projectId,
    authClient: new UserRefreshClient({
      clientId: cred.clientId,
      clientSecret: cred.clientSecret,
      refreshToken: cred.refreshToken,
    }),
  });
  return client.bucket(TARGET_BUCKET);
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.project !== TARGET_PROJECT) abort('WRONG_PROJECT', `'${args.project}'`);
  const evidence = evidenceFromArgs(args);
  const body = args.bodyFile ? (await import('node:fs')).readFileSync(args.bodyFile, 'utf8') : undefined;
  const admin = await import('firebase-admin/app');
  const { FieldValue } = await import('firebase-admin/firestore');
  const { getAuth } = await import('firebase-admin/auth');
  const { bucketStorage } = await import('../../server/storage.mjs');

  const cred = await cliCredential();
  // Auth accepts the refresh-token credential; Firestore and Storage do not — see
  // liveFirestore() and liveBucket().
  admin.initializeApp({
    credential: admin.refreshToken({
      type: 'authorized_user',
      client_id: cred.clientId,
      client_secret: cred.clientSecret,
      refresh_token: cred.refreshToken,
    }),
    projectId: args.project,
  });
  const db = await liveFirestore(cred, args.project);
  const fbAuth = getAuth();
  const lookupUid = async (email) => {
    try {
      return (await fbAuth.getUserByEmail(email)).uid;
    } catch (e) {
      if (e?.code === 'auth/user-not-found') return null;
      throw e;
    }
  };
  const claims = {
    async get(uid) {
      return (await fbAuth.getUser(uid)).customClaims ?? {};
    },
    async set(uid, c) {
      await fbAuth.setCustomUserClaims(uid, c);
    },
  };
  return run({
    command: args.command,
    target: args.target,
    role: args.role,
    until: args.until,
    eventId: args.event,
    body,
    toStatus: args.status,
    evidence,
    projectId: args.project,
    apply: args.apply,
    deps: {
      db, FieldValue, identity: { email: cred.email }, lookupUid, claims,
      auth: authAdapter(fbAuth),
      openStorage: async () => bucketStorage(await liveBucket(cred, args.project)),
      log: (l) => console.log(l),
    },
  });
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main().then(
    () => { process.exitCode = 0; },
    (e) => {
      console.error(e?.code ? `ABORT ${e.message}` : e);
      process.exitCode = 1;
    }
  );
}
