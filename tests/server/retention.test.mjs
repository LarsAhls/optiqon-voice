// FS-S468 (M5 + retention): the purge core and the retention sweep against the emulator, with an
// in-memory Storage. Each test pins one of the invariants in the headers of server/purge.mjs and
// server/retention.mjs:
//
//   * nothing is physically deleted without a tombstone, and the tombstone is written first;
//   * a retry, a missing object and an already-final purge are all quiet successes;
//   * a late upload inside the 72 h window is deleted again by the backstop, until final;
//   * the backstop reports anything past the 24 h promise;
//   * deadlines: open +12 months, closed +30 days, the earliest wins, text closedAt +12 months;
//   * only relevant activity postpones; internal notes and reads do not;
//   * a public reply that moves the anchor after the scan wins; no double purge; no leftovers.
import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, test } from 'node:test';
import { deleteApp, initializeApp } from 'firebase-admin/app';
import { FieldValue, getFirestore, Timestamp } from 'firebase-admin/firestore';
import { makeM1Env, PROJECT_ID, seed } from '../rules/helpers.mjs';
import { attachmentPath, memoryStorage } from '../../server/storage.mjs';
import { addMonths, DAY_MS, HOUR_MS } from '../../server/time.mjs';
import {
  onAttachmentChanged, PURGE, PURGE_SLA_MS, purgeAttachment, slaState, sweepPurges, UPLOAD_WINDOW_MS,
} from '../../server/purge.mjs';
import {
  attachmentDeadline, caseDeadline, expireAttachments, purgeCase, RETENTION, sweepRetention,
} from '../../server/retention.mjs';

let env;
let app;
let db;
before(async () => {
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST, 'run through `npm run test:rules`');
  env = await makeM1Env();
  app = initializeApp({ projectId: PROJECT_ID }, 'server-retention');
  db = getFirestore(app);
});
after(async () => {
  await env.cleanup();
  await deleteApp(app);
});
beforeEach(async () => {
  await env.clearFirestore();
  await seed(env);
});

const T = (ms) => Timestamp.fromMillis(ms);
const NOW = Date.now();
const deps = (storage, over = {}) => ({ db, FieldValue, storage, now: NOW, ...over });

async function peek(path) {
  const snap = await db.doc(path).get();
  return snap.exists ? snap.data() : undefined;
}

async function putCase(caseId, over = {}) {
  await db.doc(`cases/${caseId}`).set({
    ownerUid: 'alice', title: 't', body: 'b', statusCache: 'Mottaget', lastStatusEventId: null,
    attachmentCount: 0, activeAttachmentCount: 0, createdAt: T(NOW - 400 * DAY_MS),
    updatedAt: T(NOW - 400 * DAY_MS), lastActivityAt: T(NOW - 400 * DAY_MS), state: 'accepted',
    acceptedAt: T(NOW - 400 * DAY_MS), activityRev: 0, approvalGeneration: 0, ...over,
  });
}

async function putAtt(caseId, aid, over = {}, storage) {
  await db.doc(`cases/${caseId}/attachments/${aid}`).set({
    ownerUid: 'alice', caseId, messageId: null, maxBytes: 1000, createdAt: T(NOW - 10 * DAY_MS),
    approvalGeneration: 0, ...over,
  });
  storage?.put(attachmentPath('alice', caseId, aid));
}

// ------------------------------------------------------------------ pure deadlines

describe('deadlines', () => {
  const base = Date.UTC(2026, 0, 31);

  test('open case: lastRelevantAt + 12 months, falling back to acceptedAt', () => {
    assert.equal(attachmentDeadline({ lastRelevantAt: base }), addMonths(base, 12));
    assert.equal(attachmentDeadline({ acceptedAt: base }), addMonths(base, 12));
    assert.equal(caseDeadline({ lastRelevantAt: base }), null, 'an open case never expires as text');
  });

  test('closed case: closedAt + 30 days, unless the open deadline is earlier', () => {
    const closed = addMonths(base, 3);
    assert.equal(attachmentDeadline({ lastRelevantAt: base, closedAt: closed }), closed + 30 * DAY_MS);
    const lateClose = addMonths(base, 12) - 5 * DAY_MS;
    assert.equal(attachmentDeadline({ lastRelevantAt: base, closedAt: lateClose }), addMonths(base, 12),
      'the earliest applicable deadline wins');
    assert.equal(caseDeadline({ closedAt: closed }), addMonths(closed, 12));
  });

  test('month arithmetic clamps rather than overflowing', () => {
    assert.equal(new Date(addMonths(Date.UTC(2027, 0, 31), 1)).toISOString().slice(0, 10), '2027-02-28');
    assert.equal(new Date(addMonths(Date.UTC(2027, 1, 29 - 1), 12)).toISOString().slice(0, 10), '2028-02-28');
  });
});

// ------------------------------------------------------------------ M5 purge

describe('purge (M5)', () => {
  test('nothing is deleted without a tombstone', async () => {
    const s = memoryStorage();
    await putCase('c1');
    await putAtt('c1', 'a1', {}, s);
    assert.equal((await purgeAttachment(deps(s), 'c1', 'a1')).outcome, PURGE.NOT_TOMBSTONED);
    assert.equal((await onAttachmentChanged(deps(s), 'c1', 'a1', await peek('cases/c1/attachments/a1'))).outcome,
      PURGE.NOT_TOMBSTONED);
    assert.equal(s.calls.length, 0);
    assert.ok(s.objects.has(attachmentPath('alice', 'c1', 'a1')));
  });

  test('tombstone → Storage delete → purge recorded, no fingerprint, then quiet', async () => {
    const s = memoryStorage();
    await putCase('c1');
    await putAtt('c1', 'a1', { deleteRequestedAt: T(NOW - HOUR_MS) }, s);
    const r = await onAttachmentChanged(deps(s), 'c1', 'a1', await peek('cases/c1/attachments/a1'));
    assert.deepEqual(r, { outcome: PURGE.PURGED, final: true });
    assert.equal(s.objects.size, 0);
    const att = await peek('cases/c1/attachments/a1');
    assert.ok(att.purgedAt && att.purgeFinalAt);
    assert.deepEqual(Object.keys(att).sort(), ['approvalGeneration', 'caseId', 'createdAt', 'deleteRequestedAt',
      'maxBytes', 'messageId', 'ownerUid', 'purgeFinalAt', 'purgedAt'], 'no size, hash or name recorded');
    assert.equal((await purgeAttachment(deps(s), 'c1', 'a1')).outcome, PURGE.ALREADY);
    assert.equal(s.calls.length, 1, 'a final purge never touches Storage again');
  });

  test('a missing object is success; a Storage failure leaves it for the retry', async () => {
    const s = memoryStorage();
    await putCase('c1');
    await putAtt('c1', 'gone', { deleteRequestedAt: T(NOW - HOUR_MS) });
    assert.equal((await purgeAttachment(deps(s), 'c1', 'gone')).outcome, PURGE.PURGED);

    await putAtt('c1', 'a2', { deleteRequestedAt: T(NOW - HOUR_MS) }, s);
    s.failNext('remove');
    await assert.rejects(purgeAttachment(deps(s), 'c1', 'a2'), /injected/);
    assert.equal((await peek('cases/c1/attachments/a2')).purgedAt, undefined, 'recorded only after the delete');
    assert.equal((await purgeAttachment(deps(s), 'c1', 'a2')).outcome, PURGE.PURGED);
    assert.equal(s.objects.size, 0);
  });

  test('absent or inconsistent documents are never acted on; dry run only reports', async () => {
    const s = memoryStorage();
    assert.equal((await purgeAttachment(deps(s), 'c1', 'nope')).outcome, PURGE.ABSENT);
    await putCase('c1');
    await putAtt('c1', 'x', { caseId: 'other', deleteRequestedAt: T(NOW) }, s);
    assert.equal((await purgeAttachment(deps(s), 'c1', 'x')).outcome, PURGE.INCONSISTENT);
    await putAtt('c1', 'y', { deleteRequestedAt: T(NOW) }, s);
    assert.equal((await purgeAttachment(deps(s, { apply: false }), 'c1', 'y')).outcome, PURGE.DRY);
    assert.equal(s.calls.length, 0);
  });

  test('a late upload inside the window is deleted again by the backstop until final', async () => {
    const s = memoryStorage();
    await putCase('c1');
    const created = NOW - HOUR_MS;
    await putAtt('c1', 'a1', { createdAt: T(created), deleteRequestedAt: T(NOW - 30 * 60 * 1000) }, s);
    const first = await purgeAttachment(deps(s), 'c1', 'a1');
    assert.deepEqual(first, { outcome: PURGE.PURGED, final: false });
    assert.ok((await peek('cases/c1/attachments/a1')).purgedAt);

    s.put(attachmentPath('alice', 'c1', 'a1')); // an upload that was already in flight lands
    const mid = await sweepPurges(deps(s, { now: NOW + 2 * HOUR_MS }));
    assert.equal(mid.purged, 1);
    assert.equal(s.objects.size, 0, 'resurrected bytes deleted again');

    const late = created + UPLOAD_WINDOW_MS + 2 * HOUR_MS;
    const fin = await sweepPurges(deps(s, { now: late }));
    assert.equal(fin.purged, 1);
    assert.ok((await peek('cases/c1/attachments/a1')).purgeFinalAt);
    const quiet = await sweepPurges(deps(s, { now: late + HOUR_MS }));
    assert.equal(quiet.purged, 0);
    assert.equal(quiet.alreadyFinal, 1);
  });

  test('the backstop finds a missed event and reports the 24 h breach', async () => {
    const s = memoryStorage();
    await putCase('c1');
    await putAtt('c1', 'old', { deleteRequestedAt: T(NOW - 2 * DAY_MS) }, s);
    await putAtt('c1', 'new', { deleteRequestedAt: T(NOW - HOUR_MS) }, s);
    await putAtt('c1', 'live', {}, s);
    const r = await sweepPurges(deps(s));
    assert.equal(r.purged, 2);
    assert.deepEqual(r.slaBreaches, [{ caseId: 'c1', aid: 'old' }]);
    assert.ok(s.objects.has(attachmentPath('alice', 'c1', 'live')), 'an untombstoned image is never touched');
  });

  test('sla state', () => {
    assert.equal(slaState({ deleteRequestedAt: 0 }, PURGE_SLA_MS - 1), 'pending');
    assert.equal(slaState({ deleteRequestedAt: 0 }, PURGE_SLA_MS), 'breached');
    assert.equal(slaState({ deleteRequestedAt: 0, purgedAt: PURGE_SLA_MS }, 10 * PURGE_SLA_MS), 'met');
  });
});

// ------------------------------------------------------------------ retention

describe('retention', () => {
  test('open case past 12 months: screenshots tombstoned and purged, text kept, no bump', async () => {
    const s = memoryStorage();
    await putCase('c1', { lastRelevantAt: T(addMonths(NOW, -12) - DAY_MS), activeAttachmentCount: 2, activityRev: 3 });
    await putAtt('c1', 'a1', {}, s);
    await putAtt('c1', 'a2', {}, s);
    await db.doc('cases/c1/events/m1').set({ type: 'message', visibility: 'public', state: 'accepted', body: 'x' });
    const r = await sweepRetention(deps(s));
    assert.deepEqual(r.tombstoned.sort(), ['c1/a1', 'c1/a2']);
    assert.equal(s.objects.size, 0);
    const c = await peek('cases/c1');
    assert.equal(c.activeAttachmentCount, 0);
    assert.equal(c.activityRev, 3, 'retention is not activity');
    assert.ok(await peek('cases/c1/events/m1'), 'text of an open case is never deleted');
    assert.ok((await peek('cases/c1/attachments/a1')).purgeFinalAt);
  });

  test('open case inside 12 months, and relevant activity postpones', async () => {
    const s = memoryStorage();
    await putCase('c1', { lastRelevantAt: T(addMonths(NOW, -11)), acceptedAt: T(NOW - 800 * DAY_MS), activeAttachmentCount: 1 });
    await putAtt('c1', 'a1', {}, s);
    const r = await sweepRetention(deps(s));
    assert.equal(r.tombstoned.length, 0);
    assert.equal(s.objects.size, 1);
  });

  test('internal notes, reads and notifications do not postpone', async () => {
    const s = memoryStorage();
    const old = T(addMonths(NOW, -13));
    await putCase('c1', { lastRelevantAt: old, lastActivityAt: T(NOW), activeAttachmentCount: 1 });
    await putAtt('c1', 'a1', {}, s);
    await db.doc('cases/c1/events/n1').set({ type: 'note', visibility: 'internal', state: 'accepted', createdAt: T(NOW) });
    await db.doc('users/alice/caseReads/c1').set({ seenPublicRev: 0, readAt: T(NOW) });
    const r = await sweepRetention(deps(s));
    assert.deepEqual(r.tombstoned, ['c1/a1']);
  });

  test('closed case: screenshots at +30 days, text at +12 months, deleted completely', async () => {
    const s = memoryStorage();
    await putCase('c1', { lastRelevantAt: T(NOW - 60 * DAY_MS), closedAt: T(NOW - 31 * DAY_MS), activeAttachmentCount: 1 });
    await putAtt('c1', 'a1', {}, s);
    let r = await sweepRetention(deps(s));
    assert.deepEqual(r.tombstoned, ['c1/a1']);
    assert.ok(await peek('cases/c1'), 'text survives until closedAt + 12 months');

    await putCase('c2', { closedAt: T(addMonths(NOW, -12) - DAY_MS), lastRelevantAt: T(addMonths(NOW, -13)), activeAttachmentCount: 1 });
    await putAtt('c2', 'b1', {}, s);
    await db.doc('cases/c2/events/m1').set({ type: 'message', visibility: 'public', state: 'accepted' });
    await db.doc('cases/c2/events/s1').set({ type: 'status_change', visibility: 'public', state: 'accepted' });
    await db.doc('users/alice/caseReads/c2').set({ seenPublicRev: 1, readAt: T(NOW) });
    await db.doc('users/alice/notificationSends/c2:r1').set({ caseId: 'c2', sentAt: T(NOW) });
    await db.doc('users/alice/notificationSends/c1:r1').set({ caseId: 'c1', sentAt: T(NOW) });
    r = await sweepRetention(deps(s));
    assert.deepEqual(r.casesPurged, ['c2']);
    assert.equal(await peek('cases/c2'), undefined);
    assert.equal((await db.collection('cases/c2/events').get()).size, 0);
    assert.equal((await db.collection('cases/c2/attachments').get()).size, 0);
    assert.equal(await peek('users/alice/caseReads/c2'), undefined);
    assert.equal(await peek('users/alice/notificationSends/c2:r1'), undefined);
    assert.ok(await peek('users/alice/notificationSends/c1:r1'), 'another case keeps its marker');
    assert.equal(s.objects.size, 0);
  });

  test('closed case, earliest wins: an old anchor expires screenshots before closedAt + 30', async () => {
    const s = memoryStorage();
    await putCase('c1', { lastRelevantAt: T(addMonths(NOW, -12) - DAY_MS), closedAt: T(NOW - 2 * DAY_MS), activeAttachmentCount: 1 });
    await putAtt('c1', 'a1', {}, s);
    assert.deepEqual((await sweepRetention(deps(s))).tombstoned, ['c1/a1']);
  });

  test('a public reply that lands after the scan moves the anchor and wins', async () => {
    const s = memoryStorage();
    await putCase('c1', { lastRelevantAt: T(addMonths(NOW, -13)), activeAttachmentCount: 1 });
    await putAtt('c1', 'a1', {}, s);
    const r = await sweepRetention(deps(s), {
      hooks: { afterScan: async () => db.doc('cases/c1').update({ lastRelevantAt: T(NOW), activityRev: 1 }) },
    });
    assert.deepEqual(r.tombstoned, []);
    assert.equal((await peek('cases/c1/attachments/a1')).deleteRequestedAt, undefined);
    assert.equal(s.objects.size, 1);
  });

  test('a purge interrupted after the stamp is finished by the next run; a second run is quiet', async () => {
    const s = memoryStorage();
    await putCase('c1', { closedAt: T(addMonths(NOW, -13)), activeAttachmentCount: 1 });
    await putAtt('c1', 'a1', {}, s);
    s.failNext('remove');
    const first = await sweepRetention(deps(s));
    assert.equal(first.errors.length, 1);
    assert.equal((await peek('cases/c1')).retentionState, 'purging');
    const second = await sweepRetention(deps(s));
    assert.deepEqual(second.casesPurged, ['c1']);
    assert.equal(await peek('cases/c1'), undefined);
    const third = await sweepRetention(deps(s));
    assert.deepEqual(third, { scanned: 0, tombstoned: [], casesPurged: [], wouldAct: [], errors: [] });
  });

  test('a purging case does not come back even if a closedAt is moved later', async () => {
    const s = memoryStorage();
    await putCase('c1', { closedAt: T(addMonths(NOW, -13)) });
    const r = await purgeCase(deps(s), 'c1', {
      afterMark: async () => db.doc('cases/c1').update({ closedAt: T(NOW) }),
    });
    assert.equal(r.outcome, RETENTION.CASE_PURGED, 'the stamp is the decision');
    assert.equal(await peek('cases/c1'), undefined);
  });

  test('dry run touches nothing', async () => {
    const s = memoryStorage();
    await putCase('c1', { lastRelevantAt: T(addMonths(NOW, -13)), activeAttachmentCount: 1 });
    await putAtt('c1', 'a1', {}, s);
    await putCase('c2', { closedAt: T(addMonths(NOW, -13)) });
    const r = await sweepRetention(deps(s, { apply: false }));
    assert.deepEqual(r.wouldAct.sort(), ['c1', 'c2']);
    assert.equal(s.calls.length, 0);
    assert.ok(await peek('cases/c2'));
    assert.equal((await peek('cases/c1/attachments/a1')).deleteRequestedAt, undefined);
  });

  test('a submitted case is never touched by retention', async () => {
    const s = memoryStorage();
    await putCase('c1', { state: 'submitted', closedAt: T(addMonths(NOW, -13)) });
    assert.equal((await expireAttachments(deps(s), 'c1')).outcome, RETENTION.NOT_ACCEPTED);
    assert.equal((await sweepRetention(deps(s))).scanned, 0);
  });
});
