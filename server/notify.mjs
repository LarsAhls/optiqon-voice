// FS-S468 (S6): whether and how a case owner is told that support answered.
//
// Persistent unread is the source of truth: `publicRev` on the case against the owner's
// users/{uid}/caseReads/{caseId}. A notification is only a nudge to look, so it is sent at most
// once per event and losing one loses nothing.
//
// Decisions, all taken here and none on the device:
//   * only a writer's public reply notifies. A status change makes the case unread but does not
//     notify — the product has not asked for it and this does not invent it. Internal notes,
//     the owner's own messages and screenshots, and anything retention or deletion does never
//     notify;
//   * the payload is neutral: no case title, no reply text, no status, no case id, nothing
//     private. It says that there is an answer and nothing else;
//   * only an approved owner is notified. A revoked or pending account gets nothing — the
//     notification would otherwise be a side channel into whether support wrote;
//   * a token is bound to the account that registered it. If the same device token has since
//     been registered under another account (account switch without a clean sign-out), the
//     newer registration wins and the older account's copy is removed, unsent;
//   * a token the provider reports as unregistered or invalid is removed.
//
// The send marker users/{owner}/notificationSends/{caseId}:{eventId} carries the case id and a
// time, nothing else; it is deleted with the case (retention) and with the account (M4).
// Nothing here is deployed: server/main.mjs `onCaseEventCreated` is the entrypoint FS-G wires to an Eventarc trigger on
// cases/{caseId}/events/{eventId} and provides a real FCM `messenger`.

import { millis } from './time.mjs';

export const NOTIFY = Object.freeze({
  SENT: 'sent',
  ALREADY: 'already_sent',
  NOT_NOTIFIABLE: 'not_notifiable',
  OWNER_NOT_APPROVED: 'owner_not_approved',
  NO_TOKENS: 'no_tokens',
  ABSENT: 'absent',
  DRY: 'would_send',
});

/** The whole payload. Changing a word here is a privacy review, not a copy edit. */
export function neutralPayload() {
  return Object.freeze({
    notification: Object.freeze({
      title: 'OPTIQON Voice',
      body: 'Du har fått svar på din feedback.',
    }),
    data: Object.freeze({ kind: 'feedback_reply' }),
    android: Object.freeze({ priority: 'normal', notification: Object.freeze({ tag: 'feedback_reply' }) }),
  });
}

/** null when the event should notify the case owner, otherwise the reason it should not. */
export function notReason(event, kase) {
  if (!event || !kase) return 'absent';
  if (event.state !== 'accepted') return 'not accepted';
  if (event.visibility !== 'public') return 'internal';
  if (event.type !== 'note') return event.type === 'status_change' ? 'status change' : 'not a reply';
  if (event.actorUid === kase.ownerUid) return 'own message';
  if (kase.state !== 'accepted' || kase.retentionState === 'purging' || kase.withdrawnAt != null) {
    return 'case not open';
  }
  return null;
}

export const sendMarkerId = (caseId, eventId) => `${caseId}:${eventId}`;

/**
 * Whether this account's copy of a token is still its own: no other account holds the same
 * token with a registration at least as recent.
 */
async function tokenStillOwn(db, uid, tokenDoc) {
  const { token } = tokenDoc.data();
  const mine = millis(tokenDoc.data().updatedAt) ?? 0;
  const same = await db.collectionGroup('notificationTokens').where('token', '==', token).get();
  return !same.docs.some((d) => {
    const owner = d.ref.parent.parent?.id;
    return owner !== uid && (millis(d.data().updatedAt) ?? 0) >= mine;
  });
}

/**
 * Notifies the owner of `caseId` about `eventId`, if the decision says so.
 *
 * deps: { db, FieldValue, messenger: { send({ token, ...payload }) → { ok } | { error: 'unregistered' | string } }, apply = true }
 */
export async function notifyForEvent(deps, caseId, eventId) {
  const { db, FieldValue, messenger, apply = true } = deps;
  const [cs, es] = await Promise.all([
    db.doc(`cases/${caseId}`).get(),
    db.doc(`cases/${caseId}/events/${eventId}`).get(),
  ]);
  if (!cs.exists || !es.exists) return { outcome: NOTIFY.ABSENT };
  const kase = cs.data();
  const reason = notReason(es.data(), kase);
  if (reason) return { outcome: NOTIFY.NOT_NOTIFIABLE, reason };
  const owner = kase.ownerUid;

  const user = await db.doc(`users/${owner}`).get();
  if (!user.exists || user.data().status !== 'approved') return { outcome: NOTIFY.OWNER_NOT_APPROVED };

  const tokens = await db.collection(`users/${owner}/notificationTokens`).get();
  if (tokens.empty) return { outcome: NOTIFY.NO_TOKENS };
  if (!apply) return { outcome: NOTIFY.DRY, tokens: tokens.size };

  // At most once per event: claim first, then send. A crash after the claim loses one nudge,
  // never the unread state.
  const marker = db.doc(`users/${owner}/notificationSends/${sendMarkerId(caseId, eventId)}`);
  try {
    await marker.create({ caseId, sentAt: FieldValue.serverTimestamp() });
  } catch (e) {
    if (e?.code === 6 || /ALREADY_EXISTS/i.test(String(e?.message))) return { outcome: NOTIFY.ALREADY };
    throw e;
  }

  const payload = neutralPayload();
  const result = { outcome: NOTIFY.SENT, sent: 0, removed: 0, skippedForeign: 0 };
  for (const t of tokens.docs) {
    if (!(await tokenStillOwn(db, owner, t))) {
      await t.ref.delete();
      result.skippedForeign += 1;
      continue;
    }
    const r = await messenger.send({ token: t.data().token, ...payload });
    if (r?.ok) result.sent += 1;
    else if (r?.error === 'unregistered') {
      await t.ref.delete();
      result.removed += 1;
    }
  }
  return result;
}

/** Event-driven entrypoint: an event document was created. */
export async function onEventCreated(deps, caseId, eventId) {
  return notifyForEvent(deps, caseId, eventId);
}

/** firebase-admin Messaging → messenger. Never logs the token or the payload. */
export function fcmMessenger(messaging) {
  const GONE = new Set(['messaging/registration-token-not-registered', 'messaging/invalid-registration-token']);
  return {
    async send(message) {
      try {
        await messaging.send(message);
        return { ok: true };
      } catch (e) {
        return { error: GONE.has(e?.code) ? 'unregistered' : 'failed' };
      }
    },
  };
}

/** In-memory messenger for tests. `gone` holds tokens the provider reports as unregistered. */
export function memoryMessenger({ gone = [] } = {}) {
  const sent = [];
  const dead = new Set(gone);
  return {
    sent,
    async send(message) {
      if (dead.has(message.token)) return { error: 'unregistered' };
      sent.push(message);
      return { ok: true };
    },
  };
}
