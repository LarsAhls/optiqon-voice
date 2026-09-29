// Retention — when an accepted case's screenshots and, once closed, its text are deleted.
//
// Anchors (FS-S468, locked):
//   * screenshots of an open case:   lastRelevantAt + 12 months
//   * screenshots of a closed case:  closedAt + 30 days, or the open deadline if earlier
//   * text, public replies and status history of a closed case: closedAt + 12 months
//   * an open case is never closed and its text never deleted by retention
//
// `lastRelevantAt` moves only with relevant activity: an owner message, an owner screenshot, a
// public support reply, a public status change. Internal notes, reads, notifications, system
// and retention events, tombstones and deletes never move it — none of the writers in this
// directory set it for those, and the rules refuse a client that tries. A case that has had no
// activity since it was accepted has no `lastRelevantAt`; its anchor is `acceptedAt`.
//
// Race discipline. The scan reads a snapshot; every decision is taken again inside a
// transaction that re-reads the case, so a public reply that moved the anchor after the scan
// wins and nothing is deleted. A case purge first stamps `retentionState: 'purging'`, which the
// rules and `support.mjs` both refuse writes against, and only then deletes — so no activity can
// land between the decision and the deletion. A crash anywhere leaves the stamp, and the next
// run finishes the job.
//
// Account deletion (M4, `account-deletion.mjs`) overrides all of this.

import { addMonths, DAY_MS, millis } from './time.mjs';
import { PURGE, purgeAttachment } from './purge.mjs';

export const ATTACHMENT_OPEN_MONTHS = 12;
export const ATTACHMENT_CLOSED_DAYS = 30;
export const CASE_CLOSED_MONTHS = 12;

export const RETENTION = Object.freeze({
  NOT_DUE: 'not_due',
  TOMBSTONED: 'tombstoned',
  NOTHING_ACTIVE: 'nothing_active',
  CASE_PURGED: 'case_purged',
  ABSENT: 'absent',
  NOT_ACCEPTED: 'not_accepted',
  DRY: 'would_act',
});

const nowOf = (now) => (typeof now === 'function' ? now() : now ?? Date.now());

/** The instant retention counts from while the case is open. */
export function retentionAnchor(c) {
  return millis(c.lastRelevantAt) ?? millis(c.acceptedAt) ?? millis(c.createdAt);
}

/** When the case's screenshots are due; the earliest applicable deadline wins. */
export function attachmentDeadline(c) {
  const anchor = retentionAnchor(c);
  if (anchor == null) return null;
  const open = addMonths(anchor, ATTACHMENT_OPEN_MONTHS);
  const closed = millis(c.closedAt);
  return closed == null ? open : Math.min(open, closed + ATTACHMENT_CLOSED_DAYS * DAY_MS);
}

/** When a closed case's text is due. An open case: never (null). */
export function caseDeadline(c) {
  const closed = millis(c.closedAt);
  return closed == null ? null : addMonths(closed, CASE_CLOSED_MONTHS);
}

/**
 * Tombstones every active screenshot of a case whose screenshot deadline has passed, and gives
 * the slots back. Decided inside the transaction on a fresh read: a moved anchor wins.
 */
export async function expireAttachments(deps, caseId) {
  const { db, FieldValue, apply = true } = deps;
  const nowMs = nowOf(deps.now);
  const caseRef = db.doc(`cases/${caseId}`);
  const result = await db.runTransaction(async (tx) => {
    const snap = await tx.get(caseRef);
    if (!snap.exists) return { outcome: RETENTION.ABSENT, aids: [] };
    const c = snap.data();
    if (c.state !== 'accepted') return { outcome: RETENTION.NOT_ACCEPTED, aids: [] };
    const due = attachmentDeadline(c);
    if (due == null || due > nowMs) return { outcome: RETENTION.NOT_DUE, aids: [] };
    const atts = await tx.get(caseRef.collection('attachments'));
    const active = atts.docs.filter((d) => d.data().deleteRequestedAt == null);
    if (active.length === 0) return { outcome: RETENTION.NOTHING_ACTIVE, aids: [] };
    if (!apply) return { outcome: RETENTION.DRY, aids: active.map((d) => d.id) };
    for (const d of active) tx.update(d.ref, { deleteRequestedAt: FieldValue.serverTimestamp() });
    // Not activity: no activityRev, no lastRelevantAt, no publicRev.
    tx.update(caseRef, {
      activeAttachmentCount: Math.max(0, (c.activeAttachmentCount ?? 0) - active.length),
    });
    return { outcome: RETENTION.TOMBSTONED, aids: active.map((d) => d.id) };
  });
  if (result.outcome === RETENTION.TOMBSTONED) {
    for (const aid of result.aids) await purgeAttachment({ ...deps, now: nowMs }, caseId, aid);
  }
  return result;
}

export async function deleteAll(db, query) {
  for (;;) {
    const page = await query.limit(200).get();
    if (page.empty) return;
    const batch = db.batch();
    for (const d of page.docs) batch.delete(d.ref);
    await batch.commit();
  }
}

/**
 * Deletes a case that is already stamped `purging` (or whose owner is being deleted), in the
 * one order that is safe to interrupt: screenshots first (tombstone → Storage delete), then
 * every child document, the owner's read marker, withdrawal intents and notification send
 * markers for the case, and the case document last — so an interrupted run is found again.
 * Shared by retention and account deletion (M4). Account deletion passes `tolerateInconsistent`:
 * it has already emptied the owner's whole Storage prefix, so an attachment document that does
 * not name its own case or owner is removed with the rest instead of blocking the deletion.
 */
export async function eraseCase(deps, caseId, ownerUid, { tolerateInconsistent = false } = {}) {
  const { db, FieldValue } = deps;
  const caseRef = db.doc(`cases/${caseId}`);
  const atts = await caseRef.collection('attachments').get();
  const active = atts.docs.filter((d) => d.data().deleteRequestedAt == null);
  if (active.length > 0) {
    const batch = db.batch();
    for (const d of active) batch.update(d.ref, { deleteRequestedAt: FieldValue.serverTimestamp() });
    batch.update(caseRef, { activeAttachmentCount: 0 });
    await batch.commit();
  }
  for (const d of atts.docs) {
    const { outcome } = await purgeAttachment(deps, caseId, d.id);
    const done = [PURGE.PURGED, PURGE.ALREADY, PURGE.ABSENT];
    if (tolerateInconsistent) done.push(PURGE.INCONSISTENT);
    if (!done.includes(outcome)) {
      throw new Error(`screenshot ${d.id} of ${caseId} not purged: ${outcome}`);
    }
  }

  // Text and history. Children first, the case document last.
  for (const sub of await caseRef.listCollections()) await deleteAll(db, sub);
  if (typeof ownerUid === 'string' && ownerUid.length > 0) {
    await db.doc(`users/${ownerUid}/caseReads/${caseId}`).delete();
    await deleteAll(db, db.collection(`users/${ownerUid}/withdrawals`).where('caseId', '==', caseId));
    await deleteAll(db, db.collection(`users/${ownerUid}/notificationSends`).where('caseId', '==', caseId));
  }
  await caseRef.delete();
}

/**
 * Deletes a closed case whose text deadline has passed: stamps it `purging` in a transaction
 * that re-reads the deadline, then erases it (eraseCase).
 *
 * `hooks.afterMark` runs between the stamp and the deletion (race tests).
 */
export async function purgeCase(deps, caseId, hooks = {}) {
  const { db, FieldValue, apply = true } = deps;
  const nowMs = nowOf(deps.now);
  const caseRef = db.doc(`cases/${caseId}`);
  const marked = await db.runTransaction(async (tx) => {
    const snap = await tx.get(caseRef);
    if (!snap.exists) return { outcome: RETENTION.ABSENT };
    const c = snap.data();
    if (c.state !== 'accepted') return { outcome: RETENTION.NOT_ACCEPTED };
    if (c.retentionState !== 'purging') {
      const due = caseDeadline(c);
      if (due == null || due > nowMs) return { outcome: RETENTION.NOT_DUE };
      if (!apply) return { outcome: RETENTION.DRY };
      tx.update(caseRef, { retentionState: 'purging', purgeStartedAt: FieldValue.serverTimestamp() });
    }
    return { outcome: 'marked', ownerUid: c.ownerUid };
  });
  if (marked.outcome !== 'marked') return marked;
  await hooks.afterMark?.();
  await eraseCase({ ...deps, now: nowMs }, caseId, marked.ownerUid);
  return { outcome: RETENTION.CASE_PURGED };
}

/**
 * The scheduled retention run. Scans accepted cases (the beta holds at most a few hundred) and
 * decides each one again inside its own transaction. `hooks.afterScan(caseId)` runs between the
 * scan and the decision (race tests).
 */
export async function sweepRetention(deps, { pageSize = 200, hooks = {} } = {}) {
  const { db } = deps;
  const nowMs = nowOf(deps.now);
  const summary = { scanned: 0, tombstoned: [], casesPurged: [], wouldAct: [], errors: [] };
  let last = null;
  for (;;) {
    let q = db.collection('cases').where('state', '==', 'accepted').orderBy('__name__').limit(pageSize);
    if (last) q = q.startAfter(last);
    const page = await q.get();
    if (page.empty) break;
    for (const doc of page.docs) {
      summary.scanned += 1;
      const c = doc.data();
      const caseDue = caseDeadline(c);
      const attDue = attachmentDeadline(c);
      try {
        await hooks.afterScan?.(doc.id);
        if (c.retentionState === 'purging' || (caseDue != null && caseDue <= nowMs)) {
          const r = await purgeCase({ ...deps, now: nowMs }, doc.id);
          if (r.outcome === RETENTION.CASE_PURGED) summary.casesPurged.push(doc.id);
          if (r.outcome === RETENTION.DRY) summary.wouldAct.push(doc.id);
        } else if (attDue != null && attDue <= nowMs) {
          const r = await expireAttachments({ ...deps, now: nowMs }, doc.id);
          if (r.outcome === RETENTION.TOMBSTONED) summary.tombstoned.push(...r.aids.map((aid) => `${doc.id}/${aid}`));
          if (r.outcome === RETENTION.DRY) summary.wouldAct.push(doc.id);
        }
      } catch (e) {
        summary.errors.push({ caseId: doc.id, error: String(e?.message ?? e) });
      }
    }
    last = page.docs[page.docs.length - 1];
    if (page.size < pageSize) break;
  }
  return summary;
}
