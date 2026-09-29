// M4 — deleting one account and everything linked to it, as a controlled admin operation.
//
// This is NOT app self-service. Self-service deletion is the product's stated future goal
// (docs/BACKEND_OPEN_CONTRACTS.md, FS-S468); until then an administrator runs this through
// `scripts/admin/voice-admin.mjs delete-account`, dry run by default, against exactly one project.
//
// What is deleted, for the account `uid`:
//   * every case it owns, in any state, with every event, attachment document and read or
//     withdrawal record for it — accepted history included, because the account is the owner
//     of that history and account deletion overrides retention;
//   * every Storage object under `case-attachments/{uid}/`, including orphans no attachment
//     document names;
//   * every document under users/{uid}: upload reservations, quota, withdrawal intents and
//     quota, case read markers, notification tokens and send markers — and users/{uid} itself;
//   * admins/{uid}, if the account was ever given one;
//   * the Firebase Auth user.
//
// What is kept, and why: a public reply or status change a writer account made in someone
// else's case belongs to that case owner's history and is not this account's content. It
// carries the writer's uid and nothing else about the writer. Only administrators are writers.
// Nothing is copied aside: no tombstone collection, no hash, no fingerprint of what was removed.
//
// Order, chosen so that every step can be interrupted and the next run finishes the job:
//
//   1. Lock out. One transaction: an approved account becomes `revoked` and its seat is
//      released (config/counters, exactly as a revoke); `deletionStartedAt` is stamped. From
//      here the rules refuse the account every read of its screenshots and every new case,
//      message, reservation or upload, and the admin tooling refuses to approve it again.
//      Auth is disabled and its refresh tokens revoked, so no new ID token is minted.
//   2. Unreadable. Every owned case is stamped `retentionState: 'purging'` (the rules and the
//      support operations refuse writes to it) and every screenshot gets its tombstone.
//   3. Physical. Every object under the owner prefix is deleted from Storage.
//   4. Documents. Each case is erased (retention.eraseCase: screenshots, children, then the
//      case document last), then everything under users/{uid}, then admins/{uid}.
//   5. The prefix is listed again, so an upload that was in flight in step 1 cannot survive.
//   6. users/{uid} is deleted, then the Auth user. Deleting users/{uid} after everything else
//      is what lets a resumed run still find the account and its seat state.

import { eraseCase } from './retention.mjs';
import { ownerPrefix } from './storage.mjs';

export const TARGET_PROJECT = 'optiqon-voice-47498';

export class AccountDeletionError extends Error {
  constructor(code, message) {
    super(`${code}: ${message}`);
    this.name = 'AccountDeletionError';
    this.code = code;
  }
}

const refuse = (code, message) => {
  throw new AccountDeletionError(code, message);
};

const UID = /^[A-Za-z0-9_-]{1,128}$/;

/** firebase-admin Auth → the adapter this module uses. A missing user is `null`, not an error. */
export function authAdapter(auth) {
  const missing = (e) => e?.code === 'auth/user-not-found';
  return {
    async getUser(uid) {
      try {
        const u = await auth.getUser(uid);
        return { uid: u.uid, email: u.email ?? null, emailVerified: u.emailVerified === true, disabled: u.disabled === true };
      } catch (e) {
        if (missing(e)) return null;
        throw e;
      }
    },
    async lockOut(uid) {
      try {
        await auth.updateUser(uid, { disabled: true });
        await auth.revokeRefreshTokens(uid);
      } catch (e) {
        if (!missing(e)) throw e;
      }
    },
    async deleteUser(uid) {
      try {
        await auth.deleteUser(uid);
      } catch (e) {
        if (!missing(e)) throw e;
      }
    },
  };
}

/** In-memory Auth adapter for tests. `failNext(op)` makes the next call of that op throw once. */
export function memoryAuth(users = {}) {
  const store = new Map(Object.entries(users).map(([uid, u]) => [uid, { uid, disabled: false, ...u }]));
  const failures = new Map();
  const maybeFail = (op) => {
    const n = failures.get(op) ?? 0;
    if (n > 0) {
      failures.set(op, n - 1);
      throw new Error(`injected ${op} failure`);
    }
  };
  return {
    store,
    failNext(op, times = 1) {
      failures.set(op, times);
    },
    async getUser(uid) {
      maybeFail('getUser');
      const u = store.get(uid);
      return u ? { ...u } : null;
    },
    async lockOut(uid) {
      maybeFail('lockOut');
      const u = store.get(uid);
      if (u) u.disabled = true;
    },
    async deleteUser(uid) {
      maybeFail('deleteUser');
      store.delete(uid);
    },
  };
}

async function count(query) {
  const agg = await query.count().get();
  return agg.data().count;
}

/**
 * What the account holds, read-only and in a stable order. Nothing here names content: ids and
 * counts only.
 */
export async function inventory(deps, uid) {
  const { db, storage, auth } = deps;
  const [authUser, user, admin, owned, objects] = await Promise.all([
    auth.getUser(uid),
    db.doc(`users/${uid}`).get(),
    db.doc(`admins/${uid}`).get(),
    db.collection('cases').where('ownerUid', '==', uid).get(),
    storage.list(ownerPrefix(uid)),
  ]);
  const userCollections = {};
  for (const col of await db.doc(`users/${uid}`).listCollections()) {
    const n = await count(col);
    if (n > 0) userCollections[col.id] = n;
  }
  const cases = [];
  for (const d of [...owned.docs].sort((a, b) => a.id.localeCompare(b.id))) {
    const entry = { caseId: d.id, state: d.get('state') ?? null };
    for (const col of await d.ref.listCollections()) entry[col.id] = await count(col);
    cases.push(entry);
  }
  const status = user.exists ? user.get('status') : null;
  return {
    uid,
    auth: authUser ? (authUser.disabled ? 'disabled' : 'active') : 'absent',
    user: user.exists ? { status, deletionStarted: user.get('deletionStartedAt') != null } : null,
    admin: admin.exists,
    seat: status === 'approved' ? 'release' : 'none',
    userCollections: Object.fromEntries(Object.entries(userCollections).sort(([a], [b]) => a.localeCompare(b))),
    cases,
    storageObjects: objects.length,
    empty: !authUser && !user.exists && !admin.exists && owned.empty && objects.length === 0
      && Object.keys(userCollections).length === 0,
  };
}

/** Step 1: revoke with seat release, and stamp the deletion. Idempotent. */
async function lockOut(deps, uid) {
  const { db, FieldValue } = deps;
  await db.runTransaction(async (tx) => {
    const userRef = db.doc(`users/${uid}`);
    const countersRef = db.doc('config/counters');
    const [user, counters] = await Promise.all([tx.get(userRef), tx.get(countersRef)]);
    if (!user.exists) return;
    const update = {};
    if (user.get('deletionStartedAt') == null) update.deletionStartedAt = FieldValue.serverTimestamp();
    if (user.get('status') === 'approved') {
      if (!counters.exists) refuse('NOT_INITIALISED', 'config/counters missing while an approved account holds a seat');
      const n = counters.get('approvedUsers');
      if (!(n > 0)) refuse('COUNTER_UNDERFLOW', `approvedUsers=${n} cannot release a seat`);
      update.status = 'revoked';
      update.decidedAt = FieldValue.serverTimestamp();
      if (deps.actorUid) update.decidedBy = deps.actorUid;
      tx.update(countersRef, { approvedUsers: n - 1, seatFor: uid });
    }
    if (Object.keys(update).length > 0) tx.update(userRef, update);
  });
  await deps.auth.lockOut(uid);
}

/** Step 2: every owned case goes into purge and every screenshot gets its tombstone. */
async function makeUnreadable(deps, caseDocs) {
  const { db, FieldValue } = deps;
  for (const d of caseDocs) {
    const batch = db.batch();
    let writes = 0;
    if (d.get('retentionState') !== 'purging') {
      batch.update(d.ref, { retentionState: 'purging', purgeStartedAt: FieldValue.serverTimestamp() });
      writes += 1;
    }
    const atts = await d.ref.collection('attachments').get();
    for (const a of atts.docs) {
      if (a.get('deleteRequestedAt') == null) {
        batch.update(a.ref, { deleteRequestedAt: FieldValue.serverTimestamp() });
        writes += 1;
      }
    }
    if (writes > 0) await batch.commit();
  }
}

async function removePrefix(storage, uid) {
  const paths = await storage.list(ownerPrefix(uid));
  for (const p of paths) await storage.remove(p);
  return paths.length;
}

/**
 * Deletes the account `uid`. Dry run (the default) returns the inventory and writes nothing.
 *
 * deps: { db, FieldValue, storage, auth, projectId, actorUid?, apply = false, hooks? }
 * `hooks.after(step)` runs after each numbered step (partial-failure tests).
 */
export async function deleteAccount(deps, uid) {
  const { db, storage, auth, apply = false, hooks = {} } = deps;
  if (deps.projectId !== TARGET_PROJECT) {
    refuse('WRONG_PROJECT', `refusing to run against '${deps.projectId}'; this tool only knows '${TARGET_PROJECT}'`);
  }
  if (typeof uid !== 'string' || !UID.test(uid)) refuse('BAD_UID', JSON.stringify(uid));
  if (deps.actorUid && deps.actorUid === uid) refuse('SELF_DELETION', 'an administrator cannot delete the account running this');
  if (!db || !storage || !auth) refuse('MISSING_DEPS', 'db, storage and auth are all required');

  const before = await inventory(deps, uid);
  if (!apply) return { applied: false, inventory: before };
  if (before.empty) return { applied: true, alreadyDeleted: true, inventory: before };

  await lockOut(deps, uid);
  await hooks.after?.(1);

  const owned = (await db.collection('cases').where('ownerUid', '==', uid).get()).docs
    .sort((a, b) => a.id.localeCompare(b.id));
  await makeUnreadable(deps, owned);
  await hooks.after?.(2);

  await removePrefix(storage, uid);
  await hooks.after?.(3);

  for (const d of owned) await eraseCase({ ...deps, apply: true }, d.id, uid, { tolerateInconsistent: true });
  for (const col of await db.doc(`users/${uid}`).listCollections()) await db.recursiveDelete(col);
  await db.doc(`admins/${uid}`).delete();
  await hooks.after?.(4);

  await removePrefix(storage, uid);
  await hooks.after?.(5);

  await db.doc(`users/${uid}`).delete();
  await auth.deleteUser(uid);
  await hooks.after?.(6);

  const afterwards = await inventory(deps, uid);
  if (!afterwards.empty) refuse('NOT_EMPTY', `account ${uid} still holds data: ${JSON.stringify(afterwards)}`);
  return { applied: true, inventory: before, after: afterwards };
}
