// S4: server reconciliation of withdrawal intents, and the 30-day sweep of `submitted`
// leftovers. Repository code only -- nothing here is deployed, scheduled or triggered yet.
// Wiring it to the provider (Cloud Run / Eventarc / Scheduler, IAM, indexes) is FS-G.
//
// This runs with the Admin SDK, which bypasses firestore.rules completely. Every invariant the
// rules would have held is therefore held here, in code, and re-verified inside the
// transaction that deletes:
//
//   * only a `submitted` document is ever removed. `accepted` -- or a legacy document with no
//     state at all -- is received history and is NEVER withdrawal-deleted or swept;
//   * a case is removed only when the intent's author owns it and nothing hangs off it (a
//     submitted case cannot have children; if it does, something is wrong and it is left);
//   * a message is removed only when it is `type == message`, written by the intent's author,
//     and carries no screenshot. Its parent case and every other event are never touched;
//   * removal is two-phase. Phase one stamps a server-owned `withdrawnAt` on the target, which
//     the rules read as "finalize refused"; phase two re-reads everything and deletes. A crash
//     between the two leaves a stamped `submitted` document that the next run finishes;
//   * the intent is the only record kept, and its outcome is written once. A retry, a
//     duplicate event or a restart reads the outcome back instead of deciding again;
//   * the sweep keeps no record at all: no intent, no tombstone, no fingerprint. A document it
//     removed may be sent again under the same id later (a held Send); nothing is resurrected
//     by the sweep itself, which only ever deletes.

/** The verdicts written to `users/{uid}/withdrawals/{targetId}.outcome`. */
export const OUTCOME = Object.freeze({
  /** The `submitted` target was removed. */
  WITHDRAWN: 'withdrawn',
  /** The target was already received (accepted, or legacy without a state) and stays. */
  IGNORED_ACCEPTED: 'ignored_accepted',
  /** Under that id there is someone else's document, or not a message; nothing was touched. */
  IGNORED_FOREIGN: 'ignored_foreign',
  /** Nothing under that id: it never reached the server, or is already gone. */
  ABSENT: 'absent',
  /** The intent itself does not have the shape the rules write; nothing was touched. */
  IGNORED_INVALID: 'ignored_invalid',
  /** A `submitted` target with children -- impossible by the rules; left for a person. */
  IGNORED_INCONSISTENT: 'ignored_inconsistent',
  /** An attachment intent: attachments keep their own removal path (tombstones). */
  NOT_APPLICABLE: 'not_applicable',
});

const KINDS = ['case', 'message', 'attachment'];
const SEGMENT = /^[^/]{1,128}$/;
const INTENT_PATH = /^users\/([^/]+)\/withdrawals\/([^/]+)$/;

/** Parses `users/{uid}/withdrawals/{targetId}`; null for anything else. */
export function intentPath(path) {
  const m = INTENT_PATH.exec(path);
  return m ? { uid: m[1], targetId: m[2] } : null;
}

const isSubmitted = (data) => data?.state === 'submitted';

function targetRef(db, intent, targetId) {
  return intent.kind === 'case'
    ? db.doc(`cases/${targetId}`)
    : db.doc(`cases/${intent.caseId}/events/${targetId}`);
}

function validIntent(intent, targetId) {
  if (!intent || !KINDS.includes(intent.kind)) return false;
  if (typeof intent.caseId !== 'string' || !SEGMENT.test(intent.caseId)) return false;
  if (!SEGMENT.test(targetId)) return false;
  return intent.kind !== 'case' || intent.caseId === targetId;
}

/**
 * What the target's state says, read inside [tx]. Returns an outcome to record now, or null
 * when the target is a removable `submitted` leftover of [uid].
 */
async function judge(tx, db, uid, intent, targetId, target) {
  if (!target.exists) return OUTCOME.ABSENT;
  const data = target.data();
  if (intent.kind === 'case') {
    if (data.ownerUid !== uid) return OUTCOME.IGNORED_FOREIGN;
    if (!isSubmitted(data)) return OUTCOME.IGNORED_ACCEPTED;
    return (await hasCaseChildren(tx, db, targetId)) ? OUTCOME.IGNORED_INCONSISTENT : null;
  }
  if (data.type !== 'message' || data.actorUid !== uid) return OUTCOME.IGNORED_FOREIGN;
  if (!isSubmitted(data)) return OUTCOME.IGNORED_ACCEPTED;
  return (await hasMessageChildren(tx, db, intent.caseId, targetId, data))
    ? OUTCOME.IGNORED_INCONSISTENT : null;
}

async function hasCaseChildren(tx, db, caseId) {
  const [events, attachments] = await Promise.all([
    tx.get(db.collection(`cases/${caseId}/events`).limit(1)),
    tx.get(db.collection(`cases/${caseId}/attachments`).limit(1)),
  ]);
  return !events.empty || !attachments.empty;
}

async function hasMessageChildren(tx, db, caseId, messageId, data) {
  if ((data.attachmentCount ?? 0) !== 0) return true;
  const shots = await tx.get(
    db.collection(`cases/${caseId}/attachments`).where('messageId', '==', messageId).limit(1),
  );
  return !shots.empty;
}

/**
 * Reconciles one intent. Idempotent: an intent with an outcome is answered from it; a missing
 * intent is a no-op. With `apply: false` nothing is written and the planned outcome is returned.
 *
 * Returns `{ outcome, planned?: true }`, or `{ outcome: null }` when there is no intent.
 */
export async function reconcileWithdrawal({ db, FieldValue, hooks = {}, apply = true }, uid, targetId) {
  const intentRef = db.doc(`users/${uid}/withdrawals/${targetId}`);
  const record = (tx, outcome) =>
    tx.update(intentRef, { outcome, reconciledAt: FieldValue.serverTimestamp() });

  // Phase one: decide, and either record a terminal verdict or stamp the target.
  const first = await db.runTransaction(async (tx) => {
    const snap = await tx.get(intentRef);
    if (!snap.exists) return { outcome: null };
    const intent = snap.data();
    if (intent.outcome) return { outcome: intent.outcome };

    let outcome;
    let ref = null;
    let stamped = false;
    if (!validIntent(intent, targetId)) outcome = OUTCOME.IGNORED_INVALID;
    else if (intent.kind === 'attachment') outcome = OUTCOME.NOT_APPLICABLE;
    else {
      ref = targetRef(db, intent, targetId);
      const target = await tx.get(ref);
      outcome = await judge(tx, db, uid, intent, targetId, target);
      stamped = outcome === null && 'withdrawnAt' in target.data();
    }
    if (!apply) return { outcome: outcome ?? OUTCOME.WITHDRAWN, planned: true };
    if (outcome) {
      record(tx, outcome);
      return { outcome };
    }
    if (!stamped) tx.update(ref, { withdrawnAt: FieldValue.serverTimestamp() });
    return { outcome: null, pending: true, intent };
  });
  if (!first.pending) return first;

  await hooks.afterMark?.({ uid, targetId });

  // Phase two: everything is read again; only a still-submitted, still-stamped leftover goes.
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(intentRef);
    if (!snap.exists) return { outcome: null };
    const intent = snap.data();
    if (intent.outcome) return { outcome: intent.outcome };
    const ref = targetRef(db, intent, targetId);
    const target = await tx.get(ref);
    let outcome = await judge(tx, db, uid, intent, targetId, target);
    if (outcome === null) {
      if (!('withdrawnAt' in target.data())) {
        // Someone removed the stamp between the phases; nothing here writes that. Start over
        // on the next run rather than delete an unstamped document.
        return { outcome: null, retry: true };
      }
      tx.delete(ref);
      outcome = OUTCOME.WITHDRAWN;
    }
    record(tx, outcome);
    return { outcome };
  });
}

/**
 * Every intent still without an outcome, reconciled one by one. The backstop for a trigger
 * that never fired or failed; the trigger is the primary path. Intents are read in pages of
 * [pageSize] and filtered here, because Firestore cannot query for a missing field.
 */
export async function reconcilePending(deps, { pageSize = 200, limit = Infinity } = {}) {
  const { db } = deps;
  const results = [];
  let last = null;
  for (;;) {
    let q = db.collectionGroup('withdrawals').orderBy('__name__').limit(pageSize);
    if (last) q = q.startAfter(last);
    const page = await q.get();
    for (const doc of page.docs) {
      const where = intentPath(doc.ref.path);
      if (!where || doc.get('outcome')) continue;
      if (results.length >= limit) return results;
      results.push({ ...where, ...(await reconcileWithdrawal(deps, where.uid, where.targetId)) });
    }
    if (page.size < pageSize) return results;
    last = page.docs[page.docs.length - 1];
  }
}

/** A `submitted` case or owner message older than [cutoff] that nothing else should remove. */
async function sweepable(tx, db, ref, kind) {
  const snap = await tx.get(ref);
  if (!snap.exists) return false;
  const data = snap.data();
  if (!isSubmitted(data)) return false;
  if (kind === 'case') return !(await hasCaseChildren(tx, db, ref.id));
  if (data.type !== 'message') return false;
  return !(await hasMessageChildren(tx, db, ref.parent.parent.id, ref.id, data));
}

async function sweepOne({ db, FieldValue, hooks = {} }, ref, kind) {
  const marked = await db.runTransaction(async (tx) => {
    if (!(await sweepable(tx, db, ref, kind))) return false;
    tx.update(ref, { withdrawnAt: FieldValue.serverTimestamp() });
    return true;
  });
  if (!marked) return false;
  await hooks.afterMark?.({ path: ref.path });
  return db.runTransaction(async (tx) => {
    if (!(await sweepable(tx, db, ref, kind))) return false;
    tx.delete(ref);
    return true;
  });
}

/**
 * Removes `submitted` cases and owner messages created more than [ttlDays] ago: the leftovers
 * of a first commit whose second never came, and of a discard that raced a first send. Never
 * `accepted`; writes no record of what it removed.
 *
 * Needs, in production, a single-field index exemption or composite index on
 * (`state`, `createdAt`) for `cases` and for the `events` collection group -- an FS-G item.
 */
export async function sweepSubmitted({ db, FieldValue, now = new Date(), ttlDays = 30, hooks = {}, apply = true }) {
  if (!Number.isInteger(ttlDays) || ttlDays < 30) throw new Error(`ttlDays must be an integer >= 30, got ${ttlDays}`);
  const cutoff = new Date(now.getTime() - ttlDays * 86_400_000);
  const cases = await db.collection('cases')
    .where('state', '==', 'submitted').where('createdAt', '<', cutoff).get();
  const events = await db.collectionGroup('events')
    .where('state', '==', 'submitted').where('createdAt', '<', cutoff).get();

  const candidates = [
    ...cases.docs.map((d) => ({ ref: d.ref, kind: 'case' })),
    ...events.docs
      .filter((d) => /^cases\/[^/]+\/events\/[^/]+$/.test(d.ref.path))
      .map((d) => ({ ref: d.ref, kind: 'message' })),
  ];
  const removed = [];
  for (const { ref, kind } of candidates) {
    if (!apply) {
      const would = await db.runTransaction((tx) => sweepable(tx, db, ref, kind));
      if (would) removed.push(ref.path);
      continue;
    }
    if (await sweepOne({ db, FieldValue, hooks }, ref, kind)) removed.push(ref.path);
  }
  return { cutoff, removed, planned: !apply };
}
