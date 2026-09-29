// FS-S468 (S6): the notification decision core, against the emulator, with a fake messenger.
// Neutral payload; only a public writer reply notifies; revoked owners get nothing; tokens are
// bound to the account that registered them; dead tokens are removed; at most one send per event.
import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, test } from 'node:test';
import { deleteApp, initializeApp } from 'firebase-admin/app';
import { FieldValue, getFirestore, Timestamp } from 'firebase-admin/firestore';
import { makeM1Env, PROJECT_ID, seed } from '../rules/helpers.mjs';
import { fcmMessenger, memoryMessenger, neutralPayload, notifyForEvent, NOTIFY, notReason } from '../../server/notify.mjs';
import { internalNote, publicReply, statusChange } from '../../server/support.mjs';
import { isUnread } from '../../server/unread.mjs';

let env;
let app;
let db;
before(async () => {
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST, 'run through `npm run test:rules`');
  env = await makeM1Env();
  app = initializeApp({ projectId: PROJECT_ID }, 'server-notify');
  db = getFirestore(app);
});
after(async () => {
  await env.cleanup();
  await deleteApp(app);
});

const auth = { async getUser(uid) { return uid === 'lars' ? { email: 'lars@example.com', emailVerified: true } : null; } };
let messenger;
const deps = (over = {}) => ({ db, FieldValue, auth, messenger, ...over });

beforeEach(async () => {
  await env.clearFirestore();
  await seed(env);
  messenger = memoryMessenger();
  await db.doc('cases/c1').set({
    ownerUid: 'alice', title: 'Hemlig titel', body: 'Hemlig text', statusCache: 'Mottaget',
    lastStatusEventId: null, attachmentCount: 0, activeAttachmentCount: 0, createdAt: Timestamp.now(),
    updatedAt: Timestamp.now(), lastActivityAt: Timestamp.now(), state: 'accepted',
    acceptedAt: Timestamp.now(), activityRev: 0, approvalGeneration: 0,
  });
  await db.doc('users/alice/notificationTokens/phone').set({ token: 'tok-alice', platform: 'android', updatedAt: Timestamp.fromMillis(1000) });
});

const peek = async (path) => {
  const s = await db.doc(path).get();
  return s.exists ? s.data() : undefined;
};
const reply = (eventId = 'r1', body = 'Privat svar') =>
  publicReply(deps(), { caseId: 'c1', eventId, actorUid: 'lars', body });

describe('decision', () => {
  test('a public writer reply notifies once, with the neutral payload only', async () => {
    await reply();
    const r = await notifyForEvent(deps(), 'c1', 'r1');
    assert.equal(r.outcome, NOTIFY.SENT);
    assert.equal(messenger.sent.length, 1);
    const msg = messenger.sent[0];
    assert.equal(msg.token, 'tok-alice');
    const text = JSON.stringify({ ...msg, token: undefined });
    for (const secret of ['Privat svar', 'Hemlig', 'c1', 'r1', 'alice', 'Mottaget']) {
      assert.ok(!text.includes(secret), `payload leaks ${secret}`);
    }
    assert.deepEqual({ ...msg, token: undefined }, { ...neutralPayload(), token: undefined });
    assert.equal((await notifyForEvent(deps(), 'c1', 'r1')).outcome, NOTIFY.ALREADY);
    assert.equal(messenger.sent.length, 1);
    const marker = await peek('users/alice/notificationSends/c1:r1');
    assert.deepEqual(Object.keys(marker).sort(), ['caseId', 'sentAt']);
  });

  test('concurrent triggers for one event send once', async () => {
    await reply();
    await Promise.all([1, 2, 3].map(() => notifyForEvent(deps(), 'c1', 'r1')));
    assert.equal(messenger.sent.length, 1);
  });

  test('status changes, internal notes and own messages never notify', async () => {
    await statusChange(deps(), { caseId: 'c1', eventId: 's1', actorUid: 'lars', toStatus: 'Under granskning' });
    await internalNote(deps(), { caseId: 'c1', eventId: 'n1', actorUid: 'lars', body: 'x' });
    await db.doc('cases/c1/events/m1').set({ type: 'message', visibility: 'public', actorUid: 'alice', body: 'x', state: 'accepted', createdAt: Timestamp.now() });
    await db.doc('cases/c1/events/m2').set({ type: 'note', visibility: 'public', actorUid: 'alice', body: 'x', state: 'accepted', createdAt: Timestamp.now() });
    for (const id of ['s1', 'n1', 'm1', 'm2']) {
      assert.equal((await notifyForEvent(deps(), 'c1', id)).outcome, NOTIFY.NOT_NOTIFIABLE, id);
    }
    assert.equal((await notifyForEvent(deps(), 'c1', 'missing')).outcome, NOTIFY.ABSENT);
    assert.equal(messenger.sent.length, 0);
  });

  test('pure decision table', () => {
    const c = { ownerUid: 'o', state: 'accepted' };
    const reply = { type: 'note', visibility: 'public', actorUid: 'w', state: 'accepted' };
    assert.equal(notReason(reply, c), null);
    assert.equal(notReason({ ...reply, state: 'submitted' }, c), 'not accepted');
    assert.equal(notReason({ ...reply, visibility: 'internal' }, c), 'internal');
    assert.equal(notReason({ ...reply, type: 'status_change' }, c), 'status change');
    assert.equal(notReason({ ...reply, actorUid: 'o' }, c), 'own message');
    assert.equal(notReason(reply, { ...c, retentionState: 'purging' }), 'case not open');
  });

  test('a revoked or pending owner gets nothing', async () => {
    await reply();
    await db.doc('users/alice').update({ status: 'revoked' });
    assert.equal((await notifyForEvent(deps(), 'c1', 'r1')).outcome, NOTIFY.OWNER_NOT_APPROVED);
    await db.doc('users/alice').update({ status: 'pending' });
    assert.equal((await notifyForEvent(deps(), 'c1', 'r1')).outcome, NOTIFY.OWNER_NOT_APPROVED);
    assert.equal(messenger.sent.length, 0);
    assert.equal(await peek('users/alice/notificationSends/c1:r1'), undefined);
  });

  test('no permission, no token: nothing sent and nothing breaks', async () => {
    await db.doc('users/alice/notificationTokens/phone').delete();
    await reply();
    assert.equal((await notifyForEvent(deps(), 'c1', 'r1')).outcome, NOTIFY.NO_TOKENS);
  });

  test('dry run sends and records nothing', async () => {
    await reply();
    assert.equal((await notifyForEvent(deps({ apply: false }), 'c1', 'r1')).outcome, NOTIFY.DRY);
    assert.equal(messenger.sent.length, 0);
    assert.equal(await peek('users/alice/notificationSends/c1:r1'), undefined);
  });
});

describe('token isolation', () => {
  test('a device token re-registered under another account is not used for the old one', async () => {
    await db.doc('users/bob/notificationTokens/phone').set({ token: 'tok-alice', platform: 'android', updatedAt: Timestamp.fromMillis(2000) });
    await reply();
    const r = await notifyForEvent(deps(), 'c1', 'r1');
    assert.equal(r.skippedForeign, 1);
    assert.equal(messenger.sent.length, 0);
    assert.equal(await peek('users/alice/notificationTokens/phone'), undefined);
    assert.ok(await peek('users/bob/notificationTokens/phone'));
  });

  test('an older copy under another account does not block the current owner', async () => {
    await db.doc('users/bob/notificationTokens/old').set({ token: 'tok-alice', platform: 'android', updatedAt: Timestamp.fromMillis(10) });
    await reply();
    await notifyForEvent(deps(), 'c1', 'r1');
    assert.equal(messenger.sent.length, 1);
  });

  test('an unregistered token is removed; rotation sends to the new one', async () => {
    messenger = memoryMessenger({ gone: ['tok-alice'] });
    await db.doc('users/alice/notificationTokens/tablet').set({ token: 'tok-new', platform: 'android', updatedAt: Timestamp.fromMillis(3000) });
    await reply();
    const r = await notifyForEvent(deps(), 'c1', 'r1');
    assert.equal(r.removed, 1);
    assert.deepEqual(messenger.sent.map((m) => m.token), ['tok-new']);
    assert.equal(await peek('users/alice/notificationTokens/phone'), undefined);
  });

  test('the FCM adapter maps provider errors without leaking them', async () => {
    const ok = fcmMessenger({ async send() { return 'id'; } });
    assert.deepEqual(await ok.send({ token: 't' }), { ok: true });
    const gone = fcmMessenger({ async send() { throw Object.assign(new Error('x'), { code: 'messaging/registration-token-not-registered' }); } });
    assert.deepEqual(await gone.send({ token: 't' }), { error: 'unregistered' });
    const other = fcmMessenger({ async send() { throw Object.assign(new Error('x'), { code: 'messaging/internal-error' }); } });
    assert.deepEqual(await other.send({ token: 't' }), { error: 'failed' });
  });
});

describe('unread', () => {
  test('public reply and status make unread; marker clears it; internal and own never', async () => {
    const c = async () => peek('cases/c1');
    assert.equal(isUnread(await c(), undefined), false);
    await internalNote(deps(), { caseId: 'c1', eventId: 'n1', actorUid: 'lars', body: 'x' });
    assert.equal(isUnread(await c(), undefined), false);
    await reply();
    assert.equal(isUnread(await c(), undefined), true);
    assert.equal(isUnread(await c(), { seenPublicRev: 1 }), false);
    await reply(); // duplicate of the same event
    assert.equal(isUnread(await c(), { seenPublicRev: 1 }), false);
    await statusChange(deps(), { caseId: 'c1', eventId: 's1', actorUid: 'lars', toStatus: 'Parkerat' });
    assert.equal(isUnread(await c(), { seenPublicRev: 1 }), true);
  });
});
