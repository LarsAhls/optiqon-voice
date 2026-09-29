// FS-S468: support operations — a public reply, a public status change, an internal note and
// closing a case — written through the Admin SDK.
//
// The Admin SDK bypasses firestore.rules, so every invariant the rules hold for a writer's
// client is held here again, in code, inside one transaction per operation:
//
//   * the actor is a writer: Auth user with a verified email equal to users/{uid}.email,
//     users/{uid}.status == 'approved', and admins/{uid} live (not revoked, not expired) with
//     role 'writer' — the claim and its mirrored live status, never one without the other;
//   * the case is open for relevant writes: accepted, not withdrawn, not being purged;
//   * a public reply or a status change moves `activityRev` and `publicRev` by exactly one and
//     stamps `lastRelevantAt` and `lastActivityAt`, in the same commit as the event it names;
//   * an internal note moves nothing on the case; closing moves only `closedAt`;
//   * a status change follows the same transition table as the rules, and `Levererat` carries
//     delivery evidence;
//   * every operation is keyed by a caller-chosen event id. A retry with the same id and the
//     same content is reported as already done and changes nothing; the same id with other
//     content is refused, so a retry can never bump twice or overwrite accepted history.
//
// Open/closed is separate from the public status: closing does not change `statusCache`, and a
// status change does not close. A public reply on a closed case is accepted exactly as the
// rules accept it: it counts as relevant activity, which can only postpone the screenshot
// deadline up to `closedAt + 30 days`, never the text deadline (`closedAt + 12 months`).

import { millis } from './time.mjs';

export const STATUSES = Object.freeze([
  'Mottaget', 'Under granskning', 'Planerat', 'Pågår', 'Levererat', 'Parkerat', 'Inte planerat',
]);

/** Mirrors allowedTransition() in firestore.rules. Kept equal to it by a test. */
export const TRANSITIONS = Object.freeze({
  'Mottaget': ['Under granskning', 'Parkerat', 'Inte planerat'],
  'Under granskning': ['Planerat', 'Parkerat', 'Inte planerat'],
  'Planerat': ['Pågår', 'Parkerat', 'Inte planerat'],
  'Pågår': ['Levererat', 'Parkerat'],
  'Parkerat': ['Under granskning', 'Planerat', 'Inte planerat'],
  'Inte planerat': ['Under granskning'],
});

export const MAX_BODY = 20000;

export const SUPPORT = Object.freeze({
  WRITTEN: 'written',
  ALREADY: 'already_done',
});

export class SupportError extends Error {
  constructor(code, message) {
    super(`${code}: ${message}`);
    this.name = 'SupportError';
    this.code = code;
  }
}

const refuse = (code, message) => {
  throw new SupportError(code, message);
};

const ID = /^[A-Za-z0-9_-]{1,128}$/;
const nowOf = (now) => (typeof now === 'function' ? now() : now ?? Date.now());

function validId(kind, id) {
  if (typeof id !== 'string' || !ID.test(id)) refuse('BAD_ID', `${kind} ${JSON.stringify(id)}`);
}

function validBody(body) {
  if (typeof body !== 'string' || body.length === 0 || body.length > MAX_BODY) {
    refuse('BAD_BODY', `body must be 1..${MAX_BODY} characters`);
  }
}

/** Mirrors caseOpen() in firestore.rules. */
export function caseOpen(c) {
  return c.state === 'accepted' && c.withdrawnAt == null && c.retentionState !== 'purging';
}

/**
 * The actor is a writer, the same way isWriter() decides it: Auth (the claim) and users/ plus
 * admins/ (the mirrored live status). Read inside the operation's transaction, so a revoke that
 * lands first wins.
 *
 * deps.auth: { getUser(uid) → { email, emailVerified, disabled } | null }
 */
async function assertWriter(deps, tx, actorUid) {
  validId('actor', actorUid);
  const { db, auth } = deps;
  if (!auth?.getUser) refuse('NO_AUTH', 'an Auth adapter is required');
  const user = await auth.getUser(actorUid);
  if (!user || user.disabled || user.emailVerified !== true) refuse('NOT_WRITER', 'no verified Auth user');
  const [u, a] = await Promise.all([
    tx.get(db.doc(`users/${actorUid}`)),
    tx.get(db.doc(`admins/${actorUid}`)),
  ]);
  const ud = u.exists ? u.data() : null;
  if (!ud || ud.status !== 'approved' || ud.email !== user.email) refuse('NOT_WRITER', 'account not approved');
  const ad = a.exists ? a.data() : null;
  const nowMs = nowOf(deps.now);
  const until = millis(ad?.activeUntil);
  if (!ad || ad.revokedAt != null || (until != null && until <= nowMs) || ad.role !== 'writer') {
    refuse('NOT_WRITER', 'admin status not live');
  }
}

/** Reads the case for a relevant write, or refuses. */
async function openCase(db, tx, caseId) {
  validId('case', caseId);
  const ref = db.doc(`cases/${caseId}`);
  const snap = await tx.get(ref);
  if (!snap.exists) refuse('NO_CASE', caseId);
  const c = snap.data();
  if (c.retentionState === 'purging') refuse('PURGING', caseId);
  if (!caseOpen(c)) refuse('CASE_NOT_OPEN', caseId);
  return { ref, c };
}

/** An existing event under the same id: the same content is a retry, anything else a conflict. */
function sameEvent(existing, expected) {
  return Object.entries(expected).every(([k, v]) => {
    if (k === 'createdAt') return true;
    return JSON.stringify(existing[k]) === JSON.stringify(v);
  });
}

async function retryOrFresh(tx, eventRef, expected) {
  const snap = await tx.get(eventRef);
  if (!snap.exists) return false;
  if (!sameEvent(snap.data(), expected)) refuse('EVENT_ID_TAKEN', eventRef.id);
  return true;
}

/** The bump a public reply and a status change carry, exactly once. */
function publicBump(c, FieldValue) {
  return {
    activityRev: (c.activityRev ?? 0) + 1,
    publicRev: (c.publicRev ?? 0) + 1,
    lastRelevantAt: FieldValue.serverTimestamp(),
    lastActivityAt: FieldValue.serverTimestamp(),
  };
}

/**
 * A writer's public reply. The owner sees it, it is relevant activity, and it makes the case
 * unread for the owner.
 */
export async function publicReply(deps, { caseId, eventId, actorUid, body }) {
  const { db, FieldValue } = deps;
  validId('case', caseId);
  validId('event', eventId);
  validBody(body);
  return db.runTransaction(async (tx) => {
    await assertWriter(deps, tx, actorUid);
    const eventRef = db.doc(`cases/${caseId}/events/${eventId}`);
    const event = { type: 'note', visibility: 'public', actorUid, body, state: 'accepted' };
    // The retry check comes first: a retry after the case was closed or went into purge still
    // reports what happened rather than failing on a state the first attempt did not see.
    if (await retryOrFresh(tx, eventRef, event)) return { outcome: SUPPORT.ALREADY };
    const { ref, c } = await openCase(db, tx, caseId);
    tx.create(eventRef, { ...event, createdAt: FieldValue.serverTimestamp() });
    tx.update(ref, { ...publicBump(c, FieldValue), activityFor: eventId });
    return { outcome: SUPPORT.WRITTEN, ownerUid: c.ownerUid };
  });
}

/** Delivery evidence, as deliveryEvidence() in the rules demands it. */
function validEvidence(evidence, actorUid, nowMs) {
  const e = evidence ?? {};
  const at = millis(e.distributedAt);
  if (typeof e.releaseTag !== 'string' || e.releaseTag.length === 0
    || !Number.isInteger(e.versionCode) || at == null || at > nowMs || e.verifiedBy !== actorUid) {
    refuse('BAD_EVIDENCE', 'Levererat needs releaseTag, versionCode, distributedAt ≤ now, verifiedBy = actor');
  }
}

/**
 * A public status change. One event, the status cache follows it, one bump. `evidence` is
 * required for `Levererat` and refused otherwise.
 */
export async function statusChange(deps, { caseId, eventId, actorUid, toStatus, evidence }) {
  const { db, FieldValue } = deps;
  validId('case', caseId);
  validId('event', eventId);
  if (!STATUSES.includes(toStatus)) refuse('BAD_STATUS', toStatus);
  if (toStatus === 'Levererat') validEvidence(evidence, actorUid, nowOf(deps.now));
  else if (evidence != null) refuse('BAD_EVIDENCE', 'evidence belongs to Levererat only');
  return db.runTransaction(async (tx) => {
    await assertWriter(deps, tx, actorUid);
    const eventRef = db.doc(`cases/${caseId}/events/${eventId}`);
    const existing = await tx.get(eventRef);
    if (existing.exists) {
      const e = existing.data();
      if (e.type !== 'status_change' || e.toStatus !== toStatus || e.actorUid !== actorUid) {
        refuse('EVENT_ID_TAKEN', eventId);
      }
      return { outcome: SUPPORT.ALREADY };
    }
    const { ref, c } = await openCase(db, tx, caseId);
    const from = c.statusCache;
    if (!(TRANSITIONS[from] ?? []).includes(toStatus)) refuse('BAD_TRANSITION', `${from} → ${toStatus}`);
    const event = {
      type: 'status_change', visibility: 'public', actorUid, fromStatus: from, toStatus,
      state: 'accepted', createdAt: FieldValue.serverTimestamp(),
    };
    if (evidence != null) event.evidence = { ...evidence };
    tx.create(eventRef, event);
    tx.update(ref, {
      ...publicBump(c, FieldValue),
      statusCache: toStatus,
      lastStatusEventId: eventId,
      updatedAt: FieldValue.serverTimestamp(),
    });
    return { outcome: SUPPORT.WRITTEN, ownerUid: c.ownerUid, fromStatus: from };
  });
}

/** An internal note. Invisible to the owner, not activity, no notification, no unread. */
export async function internalNote(deps, { caseId, eventId, actorUid, body }) {
  const { db, FieldValue } = deps;
  validId('case', caseId);
  validId('event', eventId);
  validBody(body);
  return db.runTransaction(async (tx) => {
    await assertWriter(deps, tx, actorUid);
    const eventRef = db.doc(`cases/${caseId}/events/${eventId}`);
    const event = { type: 'note', visibility: 'internal', actorUid, body, state: 'accepted' };
    if (await retryOrFresh(tx, eventRef, event)) return { outcome: SUPPORT.ALREADY };
    await openCase(db, tx, caseId);
    tx.create(eventRef, { ...event, createdAt: FieldValue.serverTimestamp() });
    return { outcome: SUPPORT.WRITTEN };
  });
}

/**
 * Closing: one way, starts the closed-case retention clock, is not activity and does not change
 * the public status. Refused on a case being purged; a second close is a no-op.
 */
export async function closeCase(deps, { caseId, actorUid }) {
  const { db, FieldValue } = deps;
  validId('case', caseId);
  return db.runTransaction(async (tx) => {
    await assertWriter(deps, tx, actorUid);
    const ref = db.doc(`cases/${caseId}`);
    const snap = await tx.get(ref);
    if (!snap.exists) refuse('NO_CASE', caseId);
    const c = snap.data();
    if (c.closedAt != null) return { outcome: SUPPORT.ALREADY };
    if (c.retentionState === 'purging') refuse('PURGING', caseId);
    if (c.state !== 'accepted') refuse('CASE_NOT_OPEN', caseId);
    tx.update(ref, { closedAt: FieldValue.serverTimestamp() });
    return { outcome: SUPPORT.WRITTEN };
  });
}
