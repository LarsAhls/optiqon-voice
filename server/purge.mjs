// M5 — physical deletion of a screenshot after its tombstone.
//
// Order, and why each step is where it is:
//
//   1. A tombstone exists: `deleteRequestedAt` on cases/{caseId}/attachments/{aid}, written by
//      the owner, a writer, retention or account deletion. This module never writes one.
//   2. The tombstone is what makes the image unreadable. `storage.rules` denies every get and
//      create once `deleteRequestedAt` is on the attachment document — two documents per
//      evaluation, no third read. So the image is unreadable before this code runs at all.
//   3. The server deletes the Storage object. A missing object is success, not an error.
//   4. The server records `purgedAt`, and `purgeFinalAt` once no upload can be accepted any
//      more. Neither carries size, checksum, name or any other fingerprint of the bytes.
//   5. Retrying is idempotent: a final purge is a no-op, a non-final one deletes again.
//   6. A late upload cannot resurrect bytes. The rules refuse a create on a tombstoned
//      attachment; an upload already in flight when the tombstone landed can finish at most
//      until the 72 h upload window closes, and until then the purge is not final and every
//      backstop run deletes the object again.
//
// Event-driven purge (on the attachment document's update) is the primary path; `sweepPurges`
// is the scheduled backstop and reports anything past the 24 h promise.

import { DAY_MS, HOUR_MS, millis } from './time.mjs';
import { attachmentPath } from './storage.mjs';

export const PURGE = Object.freeze({
  PURGED: 'purged',
  ALREADY: 'already_purged',
  NOT_TOMBSTONED: 'not_tombstoned',
  ABSENT: 'absent',
  INCONSISTENT: 'ignored_inconsistent',
  DRY: 'would_purge',
});

/** Mirrors storage.rules: `request.time < att().createdAt + duration.value(72, 'h')`. */
export const UPLOAD_WINDOW_MS = 72 * HOUR_MS;
/** Clock skew between this process and the rules engine; the window is held open this much longer. */
export const SKEW_MS = HOUR_MS;
/** M5: physically deleted within 24 h of a valid tombstone reaching the server. */
export const PURGE_SLA_MS = DAY_MS;

const nowOf = (now) => (typeof now === 'function' ? now() : now ?? Date.now());

/** True once no upload to this attachment can be accepted by the rules any more. */
export function uploadWindowClosed(att, nowMs) {
  const created = millis(att.createdAt);
  return created != null && nowMs >= created + UPLOAD_WINDOW_MS + SKEW_MS;
}

/** 'met' | 'pending' | 'breached' against the 24 h promise. */
export function slaState(att, nowMs) {
  const requested = millis(att.deleteRequestedAt);
  if (requested == null) return 'pending';
  const deadline = requested + PURGE_SLA_MS;
  const purged = millis(att.purgedAt);
  if (purged != null) return purged <= deadline ? 'met' : 'breached';
  return nowMs < deadline ? 'pending' : 'breached';
}

/**
 * Purges one tombstoned attachment. Refuses (does nothing) unless the tombstone is there.
 *
 * deps: { db, FieldValue, storage, now?, apply = true }
 */
export async function purgeAttachment(deps, caseId, aid) {
  const { db, FieldValue, storage, apply = true } = deps;
  const ref = db.doc(`cases/${caseId}/attachments/${aid}`);
  const snap = await ref.get();
  if (!snap.exists) return { outcome: PURGE.ABSENT };
  const att = snap.data();
  if (att.caseId !== caseId || typeof att.ownerUid !== 'string' || att.ownerUid.length === 0) {
    return { outcome: PURGE.INCONSISTENT };
  }
  if (att.deleteRequestedAt == null) return { outcome: PURGE.NOT_TOMBSTONED };
  if (att.purgeFinalAt != null) return { outcome: PURGE.ALREADY };
  if (!apply) return { outcome: PURGE.DRY };

  const nowMs = nowOf(deps.now);
  const final = uploadWindowClosed(att, nowMs);
  await storage.remove(attachmentPath(att.ownerUid, caseId, aid));

  // Recorded after the delete, never before: a crash in between leaves a tombstone without
  // purgedAt, which the backstop finds and deletes again.
  await db.runTransaction(async (tx) => {
    const again = await tx.get(ref);
    if (!again.exists || again.data().deleteRequestedAt == null) return;
    const update = {};
    if (again.data().purgedAt == null) update.purgedAt = FieldValue.serverTimestamp();
    if (final) update.purgeFinalAt = FieldValue.serverTimestamp();
    if (Object.keys(update).length > 0) tx.update(ref, update);
  });
  return { outcome: PURGE.PURGED, final };
}

/** Event-driven path: the attachment document changed. Anything but a tombstone is ignored. */
export async function onAttachmentChanged(deps, caseId, aid, after) {
  if (!after || after.deleteRequestedAt == null) return { outcome: PURGE.NOT_TOMBSTONED };
  return purgeAttachment(deps, caseId, aid);
}

/**
 * Scheduled backstop: every tombstoned attachment whose purge is not final. Also reports the
 * ones past the 24 h promise, so a missed event is visible rather than silently late.
 */
export async function sweepPurges(deps, { pageSize = 200, limit = Infinity } = {}) {
  const { db } = deps;
  const nowMs = nowOf(deps.now);
  const summary = { scanned: 0, purged: 0, alreadyFinal: 0, wouldPurge: 0, errors: [], slaBreaches: [] };
  let last = null;
  for (;;) {
    let q = db.collectionGroup('attachments')
      .where('deleteRequestedAt', '<=', new Date(nowMs))
      .orderBy('deleteRequestedAt')
      .limit(pageSize);
    if (last) q = q.startAfter(last);
    const page = await q.get();
    if (page.empty) break;
    for (const doc of page.docs) {
      if (summary.scanned >= limit) return summary;
      summary.scanned += 1;
      const att = doc.data();
      if (att.purgeFinalAt != null) {
        summary.alreadyFinal += 1;
        continue;
      }
      const caseId = doc.ref.parent.parent.id;
      if (slaState(att, nowMs) === 'breached') summary.slaBreaches.push({ caseId, aid: doc.id });
      try {
        const { outcome } = await purgeAttachment({ ...deps, now: nowMs }, caseId, doc.id);
        if (outcome === PURGE.PURGED) summary.purged += 1;
        if (outcome === PURGE.DRY) summary.wouldPurge += 1;
      } catch (e) {
        summary.errors.push({ caseId, aid: doc.id, error: String(e?.message ?? e) });
      }
    }
    last = page.docs[page.docs.length - 1];
    if (page.size < pageSize) break;
  }
  return summary;
}
