// S4: the reconciliation server against the emulator, with the deployed rules in front of the
// clients. The server writes through the Admin SDK, which the rules never see, so each test
// here pins an invariant the server has to hold on its own:
//
//   * only a `submitted` case or owner message is ever removed; accepted history never is;
//   * someone else's document, or a non-message event, is never touched;
//   * an outcome is written once and read back on every retry, duplicate or restart;
//   * the server's stamp refuses a client finalize that races the removal, and a state that
//     changed between the two phases is re-read and respected;
//   * the sweep removes only old `submitted` leftovers, writes no record, and the same id may
//     be sent again afterwards.
import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, test } from 'node:test';
import { deleteApp, initializeApp } from 'firebase-admin/app';
import { FieldValue, getFirestore, Timestamp } from 'firebase-admin/firestore';
import { doc, serverTimestamp, setDoc, updateDoc, writeBatch } from 'firebase/firestore';
import { as, assertFails, assertSucceeds, makeM1Env, PROJECT_ID, seed } from '../rules/helpers.mjs';
import { OUTCOME, reconcilePending, reconcileWithdrawal, sweepSubmitted } from '../../server/withdrawal.mjs';

let env;
let app;
let db;
before(async () => {
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST, 'run through `npm run test:rules`');
  env = await makeM1Env();
  app = initializeApp({ projectId: PROJECT_ID }, 'server-withdrawal');
  db = getFirestore(app);
});
after(async () => {
  await env.cleanup();
  await deleteApp(app);
});
beforeEach(async () => {
  await env.clearFirestore();
  await seed(env);
  // An accepted case each for alice and bob, for messages to hang off.
  for (const [caseId, ownerUid] of [['case-alice', 'alice'], ['case-bob', 'bob']]) {
    await db.doc(`cases/${caseId}`).set({
      ownerUid, title: `${ownerUid} case`, body: '', statusCache: 'Mottaget', lastStatusEventId: null,
      attachmentCount: 0, activeAttachmentCount: 0, createdAt: Timestamp.now(), updatedAt: Timestamp.now(),
      lastActivityAt: Timestamp.now(), state: 'accepted', acceptedAt: Timestamp.now(), activityRev: 0,
      approvalGeneration: 0,
    });
  }
});

const deps = (over = {}) => ({ db, FieldValue, ...over });
const reconcile = (uid, targetId, over) => reconcileWithdrawal(deps(over), uid, targetId);

async function peek(path) {
  const snap = await db.doc(path).get();
  return snap.exists ? snap.data() : undefined;
}

// ------------------------------------------------------------------ client side, through the rules

const newCase = (uid, over = {}) => ({
  ownerUid: uid, title: 'Knappen fungerar inte', body: 'Detaljer', statusCache: 'Mottaget',
  lastStatusEventId: null, attachmentCount: 0, activeAttachmentCount: 0,
  createdAt: serverTimestamp(), updatedAt: serverTimestamp(), lastActivityAt: serverTimestamp(),
  state: 'submitted', activityRev: 0, approvalGeneration: 0, ...over,
});

const newMessage = (uid, over = {}) => ({
  type: 'message', visibility: 'public', actorUid: uid, body: 'Ett till problem',
  attachmentCount: 0, createdAt: serverTimestamp(), state: 'submitted', approvalGeneration: 0, ...over,
});

const clientDb = (uid) => as(env, uid).firestore();
const createCase = (uid, caseId, over) => setDoc(doc(clientDb(uid), `cases/${caseId}`), newCase(uid, over));
const createMessage = (uid, caseId, id, over) =>
  setDoc(doc(clientDb(uid), `cases/${caseId}/events/${id}`), newMessage(uid, over));

const finalizeCase = (uid, caseId) => updateDoc(doc(clientDb(uid), `cases/${caseId}`), {
  state: 'accepted', acceptedAt: serverTimestamp(), approvalGeneration: 0,
});

async function finalizeMessage(uid, caseId, eventId) {
  const c = await peek(`cases/${caseId}`);
  const cdb = clientDb(uid);
  const b = writeBatch(cdb);
  b.update(doc(cdb, `cases/${caseId}/events/${eventId}`), {
    state: 'accepted', acceptedAt: serverTimestamp(), approvalGeneration: 0,
  });
  b.update(doc(cdb, `cases/${caseId}`), {
    activityRev: c.activityRev + 1, lastRelevantAt: serverTimestamp(), lastActivityAt: serverTimestamp(),
    activityFor: eventId,
  });
  return b.commit();
}

/** The intent plus its rate-limit step, exactly as S3 sends it. */
async function withdraw(uid, targetId, { kind = 'case', caseId = targetId } = {}) {
  const q = await peek(`users/${uid}/quota/withdrawals`);
  const cdb = clientDb(uid);
  const b = writeBatch(cdb);
  b.set(doc(cdb, `users/${uid}/withdrawals/${targetId}`), { kind, caseId, createdAt: serverTimestamp() });
  const ref = doc(cdb, `users/${uid}/quota/withdrawals`);
  if (q) b.update(ref, { windowCount: q.windowCount + 1, lastWithdrawalId: targetId });
  else b.set(ref, { windowStart: serverTimestamp(), windowCount: 1, lastWithdrawalId: targetId });
  return b.commit();
}

const intent = (uid, targetId) => peek(`users/${uid}/withdrawals/${targetId}`);

// ------------------------------------------------------------------ cases

describe('case withdrawal', () => {
  test('a submitted case is removed, stamped first, and the verdict recorded', async () => {
    await createCase('alice', 'c1');
    await withdraw('alice', 'c1');
    let stamped;
    const r = await reconcile('alice', 'c1', {
      hooks: { afterMark: async () => { stamped = await peek('cases/c1'); } },
    });
    assert.equal(r.outcome, OUTCOME.WITHDRAWN);
    assert.equal(stamped.state, 'submitted');
    assert.ok(stamped.withdrawnAt, 'the server-owned stamp is set before removal');
    assert.equal(await peek('cases/c1'), undefined);
    const w = await intent('alice', 'c1');
    assert.equal(w.outcome, OUTCOME.WITHDRAWN);
    assert.ok(w.reconciledAt);
  });

  test('an accepted case is never removed', async () => {
    await createCase('alice', 'c1');
    await finalizeCase('alice', 'c1');
    await withdraw('alice', 'c1');
    assert.equal((await reconcile('alice', 'c1')).outcome, OUTCOME.IGNORED_ACCEPTED);
    const c = await peek('cases/c1');
    assert.equal(c.state, 'accepted');
    assert.ok(!('withdrawnAt' in c), 'not even stamped');
  });

  test('a legacy case without a state counts as received', async () => {
    await db.doc('cases/old').set({ ownerUid: 'alice', title: 'gammal', createdAt: Timestamp.now() });
    await withdraw('alice', 'old');
    assert.equal((await reconcile('alice', 'old')).outcome, OUTCOME.IGNORED_ACCEPTED);
    assert.ok(await peek('cases/old'));
  });

  test("someone else's case under the id is left alone", async () => {
    await createCase('bob', 'c1');
    await withdraw('alice', 'c1');
    assert.equal((await reconcile('alice', 'c1')).outcome, OUTCOME.IGNORED_FOREIGN);
    assert.equal((await peek('cases/c1')).state, 'submitted');
    assert.ok(!('withdrawnAt' in (await peek('cases/c1'))));
  });

  test('a missing case is absent', async () => {
    await withdraw('alice', 'never-sent');
    assert.equal((await reconcile('alice', 'never-sent')).outcome, OUTCOME.ABSENT);
  });

  test('a submitted case that somehow has children is left for a person', async () => {
    await createCase('alice', 'c1');
    await db.doc('cases/c1/events/e1').set({ type: 'note', actorUid: 'lars', state: 'accepted' });
    await withdraw('alice', 'c1');
    assert.equal((await reconcile('alice', 'c1')).outcome, OUTCOME.IGNORED_INCONSISTENT);
    assert.ok(await peek('cases/c1'));
    assert.ok(await peek('cases/c1/events/e1'));
  });
});

// ------------------------------------------------------------------ messages

describe('message withdrawal', () => {
  test('an own submitted message is removed, and nothing else is', async () => {
    await createMessage('alice', 'case-alice', 'm1');
    await createMessage('alice', 'case-alice', 'm2');
    await withdraw('alice', 'm1', { kind: 'message', caseId: 'case-alice' });
    assert.equal((await reconcile('alice', 'm1')).outcome, OUTCOME.WITHDRAWN);
    assert.equal(await peek('cases/case-alice/events/m1'), undefined);
    assert.ok(await peek('cases/case-alice/events/m2'), 'other events stay');
    const parent = await peek('cases/case-alice');
    assert.equal(parent.state, 'accepted', 'the parent case stays');
    assert.equal(parent.activityRev, 0, 'and is not bumped');
  });

  test('an accepted message is never removed', async () => {
    await createMessage('alice', 'case-alice', 'm1');
    await finalizeMessage('alice', 'case-alice', 'm1');
    await withdraw('alice', 'm1', { kind: 'message', caseId: 'case-alice' });
    assert.equal((await reconcile('alice', 'm1')).outcome, OUTCOME.IGNORED_ACCEPTED);
    assert.equal((await peek('cases/case-alice/events/m1')).state, 'accepted');
  });

  test("someone else's message, or an event that is not a message, is foreign", async () => {
    await createMessage('bob', 'case-bob', 'm1');
    await withdraw('alice', 'm1', { kind: 'message', caseId: 'case-bob' });
    assert.equal((await reconcile('alice', 'm1')).outcome, OUTCOME.IGNORED_FOREIGN);
    assert.equal((await peek('cases/case-bob/events/m1')).state, 'submitted');

    await db.doc('cases/case-alice/events/s1').set({ type: 'status', actorUid: 'alice', state: 'submitted' });
    await withdraw('alice', 's1', { kind: 'message', caseId: 'case-alice' });
    assert.equal((await reconcile('alice', 's1')).outcome, OUTCOME.IGNORED_FOREIGN);
    assert.ok(await peek('cases/case-alice/events/s1'));
  });

  test('a missing message is absent', async () => {
    await withdraw('alice', 'm9', { kind: 'message', caseId: 'case-alice' });
    assert.equal((await reconcile('alice', 'm9')).outcome, OUTCOME.ABSENT);
  });

  test('a message carrying a screenshot is left alone', async () => {
    await db.doc('cases/case-alice/events/m1').set({
      type: 'message', actorUid: 'alice', state: 'submitted', attachmentCount: 0,
    });
    await db.doc('cases/case-alice/attachments/a1').set({ messageId: 'm1', ownerUid: 'alice' });
    await withdraw('alice', 'm1', { kind: 'message', caseId: 'case-alice' });
    assert.equal((await reconcile('alice', 'm1')).outcome, OUTCOME.IGNORED_INCONSISTENT);
    assert.ok(await peek('cases/case-alice/events/m1'));
  });

  test('attachment intents keep their own path and touch nothing', async () => {
    await db.doc('cases/case-alice/attachments/a1').set({ ownerUid: 'alice' });
    await withdraw('alice', 'a1', { kind: 'attachment', caseId: 'case-alice' });
    assert.equal((await reconcile('alice', 'a1')).outcome, OUTCOME.NOT_APPLICABLE);
    assert.ok(await peek('cases/case-alice/attachments/a1'));
  });

  test('a malformed intent, which the rules would never write, touches nothing', async () => {
    await createCase('alice', 'c1');
    await db.doc('users/alice/withdrawals/c1').set({ kind: 'case', caseId: 'c2' });
    assert.equal((await reconcile('alice', 'c1')).outcome, OUTCOME.IGNORED_INVALID);
    assert.ok(await peek('cases/c1'));
  });
});

// ------------------------------------------------------------------ retries and races

describe('idempotency and races', () => {
  test('a duplicate, a retry and a restart all read the first verdict back', async () => {
    await createCase('alice', 'c1');
    await withdraw('alice', 'c1');
    assert.equal((await reconcile('alice', 'c1')).outcome, OUTCOME.WITHDRAWN);
    const first = await intent('alice', 'c1');

    // The id is free again server-side only as far as the case goes: the intent stays and
    // still blocks a late create under that id.
    await assertFails(createCase('alice', 'c1'));
    for (let i = 0; i < 3; i++) assert.equal((await reconcile('alice', 'c1')).outcome, OUTCOME.WITHDRAWN);
    assert.deepEqual(await intent('alice', 'c1'), first, 'reconciledAt never moves');
  });

  test('a second intent for the same id is refused by the rules', async () => {
    await createCase('alice', 'c1');
    await assertSucceeds(withdraw('alice', 'c1'));
    await assertFails(withdraw('alice', 'c1'));
  });

  test('a crash between the phases is finished by the next run', async () => {
    await createCase('alice', 'c1');
    await withdraw('alice', 'c1');
    await assert.rejects(reconcile('alice', 'c1', { hooks: { afterMark: () => { throw new Error('crash'); } } }));
    assert.ok((await peek('cases/c1')).withdrawnAt, 'left stamped');
    assert.equal((await intent('alice', 'c1')).outcome, undefined);
    await assertFails(finalizeCase('alice', 'c1'));

    const [r] = await reconcilePending(deps());
    assert.deepEqual(r, { uid: 'alice', targetId: 'c1', outcome: OUTCOME.WITHDRAWN });
    assert.equal(await peek('cases/c1'), undefined);
  });

  test('finalize before withdrawal: the case is received and stays', async () => {
    await createCase('alice', 'c1');
    await assertSucceeds(finalizeCase('alice', 'c1'));
    await withdraw('alice', 'c1');
    assert.equal((await reconcile('alice', 'c1')).outcome, OUTCOME.IGNORED_ACCEPTED);
  });

  test('withdrawal before finalize: the late finalize is refused, before and after the stamp', async () => {
    await createCase('alice', 'c1');
    await withdraw('alice', 'c1');
    await assertFails(finalizeCase('alice', 'c1'));
    const r = await reconcile('alice', 'c1', {
      hooks: { afterMark: () => assertFails(finalizeCase('alice', 'c1')) },
    });
    assert.equal(r.outcome, OUTCOME.WITHDRAWN);
  });

  test('a state that changes between the phases is read again and respected', async () => {
    // Only a rule-bypassing writer could do this; the server must still never delete it.
    await createCase('alice', 'c1');
    await withdraw('alice', 'c1');
    const r = await reconcile('alice', 'c1', {
      hooks: { afterMark: () => db.doc('cases/c1').update({ state: 'accepted' }) },
    });
    assert.equal(r.outcome, OUTCOME.IGNORED_ACCEPTED);
    assert.equal((await peek('cases/c1')).state, 'accepted');
  });

  test('a message finalize racing its withdrawal is refused', async () => {
    await createMessage('alice', 'case-alice', 'm1');
    await withdraw('alice', 'm1', { kind: 'message', caseId: 'case-alice' });
    await assertFails(finalizeMessage('alice', 'case-alice', 'm1'));
    assert.equal((await reconcile('alice', 'm1')).outcome, OUTCOME.WITHDRAWN);
    assert.equal((await peek('cases/case-alice')).activityRev, 0);
  });

  test('a stale client retrying its create after the withdrawal is refused', async () => {
    await withdraw('alice', 'c1');
    await reconcile('alice', 'c1');
    await assertFails(createCase('alice', 'c1'));
    await assertFails(createMessage('alice', 'case-alice', 'c1'));
  });

  test('pending reconciliation only picks up intents without a verdict, and repeats as a no-op', async () => {
    await createCase('alice', 'c1');
    await createCase('bob', 'c2');
    await withdraw('alice', 'c1');
    await withdraw('bob', 'c2');
    await withdraw('bob', 'gone');
    await reconcile('bob', 'gone');

    const first = await reconcilePending(deps(), { pageSize: 1 });
    assert.deepEqual(first.map((r) => `${r.uid}/${r.targetId}:${r.outcome}`).sort(),
      ['alice/c1:withdrawn', 'bob/c2:withdrawn']);
    assert.deepEqual(await reconcilePending(deps()), []);
  });

  test('a dry run decides but writes nothing', async () => {
    await createCase('alice', 'c1');
    await withdraw('alice', 'c1');
    assert.deepEqual(await reconcile('alice', 'c1', { apply: false }),
      { outcome: OUTCOME.WITHDRAWN, planned: true });
    const c = await peek('cases/c1');
    assert.equal(c.state, 'submitted');
    assert.ok(!('withdrawnAt' in c));
    assert.equal((await intent('alice', 'c1')).outcome, undefined);
  });

  test('no intent, no action', async () => {
    await createCase('alice', 'c1');
    assert.deepEqual(await reconcile('alice', 'c1'), { outcome: null });
    assert.ok(await peek('cases/c1'));
  });
});

// ------------------------------------------------------------------ the 30-day sweep

describe('submitted sweep', () => {
  const DAY = 86_400_000;
  const later = (days) => new Date(Date.now() + days * DAY);
  const sweep = (over = {}) => sweepSubmitted({ ...deps(), now: later(31), ...over });

  test('old submitted leftovers go; accepted, fresh and non-messages stay', async () => {
    await createCase('alice', 'old-sub');
    await createCase('alice', 'old-acc');
    await finalizeCase('alice', 'old-acc');
    await createMessage('alice', 'case-alice', 'm-sub');
    await createMessage('alice', 'case-alice', 'm-acc');
    await finalizeMessage('alice', 'case-alice', 'm-acc');
    await db.doc('cases/case-alice/events/s1').set({ type: 'status', state: 'submitted', createdAt: Timestamp.now() });

    const r = await sweep();
    assert.deepEqual(r.removed.sort(), ['cases/case-alice/events/m-sub', 'cases/old-sub']);
    assert.ok(await peek('cases/old-acc'));
    assert.ok(await peek('cases/case-alice/events/m-acc'));
    assert.ok(await peek('cases/case-alice/events/s1'));
    assert.ok(await peek('cases/case-alice'));
    assert.ok(await peek('cases/case-bob'));
  });

  test('nothing younger than the TTL is touched, and the TTL cannot be shortened', async () => {
    await createCase('alice', 'c1');
    assert.deepEqual((await sweep({ now: later(29) })).removed, []);
    assert.ok(await peek('cases/c1'));
    await assert.rejects(sweep({ ttlDays: 7 }), /ttlDays/);
  });

  test('the sweep keeps no record, so a held Send may recreate the same id', async () => {
    await createCase('alice', 'c1');
    await sweep();
    assert.equal(await peek('cases/c1'), undefined);
    assert.equal(await intent('alice', 'c1'), undefined, 'no intent, no fingerprint');
    await assertSucceeds(createCase('alice', 'c1'));
    await assertSucceeds(finalizeCase('alice', 'c1'));
  });

  test('sweep then finalize: the finalize after the stamp is refused', async () => {
    await createCase('alice', 'c1');
    const r = await sweep({ hooks: { afterMark: () => assertFails(finalizeCase('alice', 'c1')) } });
    assert.deepEqual(r.removed, ['cases/c1']);
  });

  test('finalize then sweep: the received case stays', async () => {
    await createCase('alice', 'c1');
    await finalizeCase('alice', 'c1');
    assert.deepEqual((await sweep()).removed, []);
    assert.equal((await peek('cases/c1')).state, 'accepted');
  });

  test('a state that changes between the phases is re-read and kept', async () => {
    await createCase('alice', 'c1');
    const r = await sweep({ hooks: { afterMark: () => db.doc('cases/c1').update({ state: 'accepted' }) } });
    assert.deepEqual(r.removed, []);
    assert.ok(await peek('cases/c1'));
  });

  test('sweep and withdrawal agree in either order', async () => {
    await createCase('alice', 'c1');
    await withdraw('alice', 'c1');
    await sweep();
    assert.equal((await reconcile('alice', 'c1')).outcome, OUTCOME.ABSENT);

    await createCase('bob', 'c2');
    await withdraw('bob', 'c2');
    assert.equal((await reconcile('bob', 'c2')).outcome, OUTCOME.WITHDRAWN);
    assert.deepEqual((await sweep()).removed, []);
  });

  test('a repeated sweep and a dry run remove nothing more', async () => {
    await createCase('alice', 'c1');
    await createCase('alice', 'c2');
    const dry = await sweep({ apply: false });
    assert.deepEqual(dry.removed.sort(), ['cases/c1', 'cases/c2']);
    assert.ok(await peek('cases/c1'), 'dry run wrote nothing');
    assert.ok(!('withdrawnAt' in (await peek('cases/c1'))));
    assert.equal((await sweep()).removed.length, 2);
    assert.deepEqual((await sweep()).removed, []);
  });
});
