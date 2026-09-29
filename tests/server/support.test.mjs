// FS-S468: support operations through the Admin SDK, against the emulator. The rules never see
// these writes, so each test pins an invariant the server holds on its own: writer identity,
// open case, one bump per public event and none for anything else, the transition table, and
// idempotent retries that can neither bump twice nor overwrite history.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { after, before, beforeEach, describe, test } from 'node:test';
import { deleteApp, initializeApp } from 'firebase-admin/app';
import { FieldValue, getFirestore, Timestamp } from 'firebase-admin/firestore';
import { makeM1Env, PROJECT_ID, seed } from '../rules/helpers.mjs';
import {
  closeCase, internalNote, publicReply, statusChange, STATUSES, SUPPORT, TRANSITIONS,
} from '../../server/support.mjs';

let env;
let app;
let db;
before(async () => {
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST, 'run through `npm run test:rules`');
  env = await makeM1Env();
  app = initializeApp({ projectId: PROJECT_ID }, 'server-support');
  db = getFirestore(app);
});
after(async () => {
  await env.cleanup();
  await deleteApp(app);
});

const AUTH = new Map([
  ['lars', { email: 'lars@example.com', emailVerified: true }],
  ['reader', { email: 'reader@example.com', emailVerified: true }],
  ['alice', { email: 'alice@example.com', emailVerified: true }],
]);
const fakeAuth = (users = AUTH) => ({ async getUser(uid) { return users.get(uid) ?? null; } });
const deps = (over = {}) => ({ db, FieldValue, auth: fakeAuth(), ...over });

beforeEach(async () => {
  await env.clearFirestore();
  await seed(env);
  await db.doc('cases/c1').set({
    ownerUid: 'alice', title: 't', body: '', statusCache: 'Mottaget', lastStatusEventId: null,
    attachmentCount: 0, activeAttachmentCount: 0, createdAt: Timestamp.now(), updatedAt: Timestamp.now(),
    lastActivityAt: Timestamp.now(), state: 'accepted', acceptedAt: Timestamp.now(), activityRev: 3,
    approvalGeneration: 0,
  });
});

const peek = async (path) => {
  const s = await db.doc(path).get();
  return s.exists ? s.data() : undefined;
};
const reply = (over = {}) => publicReply(deps(), { caseId: 'c1', eventId: 'r1', actorUid: 'lars', body: 'Hej', ...over });

describe('public reply', () => {
  test('writes a public event and bumps activityRev and publicRev by exactly one', async () => {
    const r = await reply();
    assert.equal(r.outcome, SUPPORT.WRITTEN);
    assert.equal(r.ownerUid, 'alice');
    const c = await peek('cases/c1');
    assert.equal(c.activityRev, 4);
    assert.equal(c.publicRev, 1);
    assert.equal(c.activityFor, 'r1');
    assert.ok(c.lastRelevantAt && c.lastActivityAt);
    const e = await peek('cases/c1/events/r1');
    assert.deepEqual({ ...e, createdAt: undefined }, {
      type: 'note', visibility: 'public', actorUid: 'lars', body: 'Hej', state: 'accepted', createdAt: undefined,
    });
  });

  test('a retry is idempotent; the same id with other content is refused', async () => {
    await reply();
    assert.equal((await reply()).outcome, SUPPORT.ALREADY);
    assert.equal((await reply()).outcome, SUPPORT.ALREADY);
    await assert.rejects(reply({ body: 'Annat' }), /EVENT_ID_TAKEN/);
    const c = await peek('cases/c1');
    assert.equal(c.activityRev, 4);
    assert.equal(c.publicRev, 1);
    assert.equal((await peek('cases/c1/events/r1')).body, 'Hej');
  });

  test('two concurrent replies each bump once', async () => {
    await Promise.all([reply({ eventId: 'a' }), reply({ eventId: 'b' }), reply({ eventId: 'a' })]);
    const c = await peek('cases/c1');
    assert.equal(c.activityRev, 5);
    assert.equal(c.publicRev, 2);
  });

  test('only a live writer: reader, owner, revoked admin, expired, unapproved, unverified, disabled', async () => {
    await assert.rejects(reply({ actorUid: 'reader' }), /NOT_WRITER/);
    await assert.rejects(reply({ actorUid: 'alice' }), /NOT_WRITER/);
    await assert.rejects(reply({ actorUid: 'ghost' }), /NOT_WRITER/);
    await db.doc('admins/lars').update({ activeUntil: Timestamp.fromMillis(Date.now() - 1000) });
    await assert.rejects(reply(), /NOT_WRITER/);
    await db.doc('admins/lars').update({ activeUntil: FieldValue.delete(), revokedAt: Timestamp.now() });
    await assert.rejects(reply(), /NOT_WRITER/);
    await db.doc('admins/lars').update({ revokedAt: FieldValue.delete() });
    await db.doc('users/lars').update({ status: 'revoked' });
    await assert.rejects(reply(), /NOT_WRITER/);
    await db.doc('users/lars').update({ status: 'approved' });
    const unverified = new Map([['lars', { email: 'lars@example.com', emailVerified: false }]]);
    await assert.rejects(publicReply(deps({ auth: fakeAuth(unverified) }),
      { caseId: 'c1', eventId: 'r1', actorUid: 'lars', body: 'x' }), /NOT_WRITER/);
    const other = new Map([['lars', { email: 'someone@example.com', emailVerified: true }]]);
    await assert.rejects(publicReply(deps({ auth: fakeAuth(other) }),
      { caseId: 'c1', eventId: 'r1', actorUid: 'lars', body: 'x' }), /NOT_WRITER/);
    const disabled = new Map([['lars', { email: 'lars@example.com', emailVerified: true, disabled: true }]]);
    await assert.rejects(publicReply(deps({ auth: fakeAuth(disabled) }),
      { caseId: 'c1', eventId: 'r1', actorUid: 'lars', body: 'x' }), /NOT_WRITER/);
    await assert.rejects(publicReply({ db, FieldValue }, { caseId: 'c1', eventId: 'r1', actorUid: 'lars', body: 'x' }), /NO_AUTH/);
    assert.equal(await peek('cases/c1/events/r1'), undefined);
    assert.equal((await peek('cases/c1')).activityRev, 3);
  });

  test('refused on a submitted, withdrawn, purging or missing case', async () => {
    await db.doc('cases/c1').update({ state: 'submitted' });
    await assert.rejects(reply(), /CASE_NOT_OPEN/);
    await db.doc('cases/c1').update({ state: 'accepted', withdrawnAt: Timestamp.now() });
    await assert.rejects(reply(), /CASE_NOT_OPEN/);
    await db.doc('cases/c1').update({ withdrawnAt: FieldValue.delete(), retentionState: 'purging' });
    await assert.rejects(reply(), /PURGING/);
    await assert.rejects(reply({ caseId: 'nope' }), /NO_CASE/);
    assert.equal(await peek('cases/c1/events/r1'), undefined);
  });

  test('a closed case takes a reply, as the rules allow; closing itself is no activity', async () => {
    await closeCase(deps(), { caseId: 'c1', actorUid: 'lars' });
    const closed = await peek('cases/c1');
    assert.equal(closed.activityRev, 3);
    assert.equal(closed.publicRev, undefined);
    assert.equal(closed.statusCache, 'Mottaget');
    assert.equal((await reply()).outcome, SUPPORT.WRITTEN);
  });

  test('bad input is refused before anything is read', async () => {
    await assert.rejects(reply({ body: '' }), /BAD_BODY/);
    await assert.rejects(reply({ body: 'x'.repeat(20001) }), /BAD_BODY/);
    await assert.rejects(reply({ eventId: 'a/b' }), /BAD_ID/);
    await assert.rejects(reply({ caseId: '../x' }), /BAD_ID/);
  });
});

const slug = (s) => `s-${s.replace(/[^A-Za-z]/g, '')}`;

describe('status change', () => {
  const change = (toStatus, over = {}) =>
    statusChange(deps(), { caseId: 'c1', eventId: slug(toStatus), actorUid: 'lars', toStatus, ...over });

  test('writes a status event, follows it with the cache, and bumps once', async () => {
    const r = await change('Under granskning');
    assert.equal(r.outcome, SUPPORT.WRITTEN);
    const c = await peek('cases/c1');
    assert.equal(c.statusCache, 'Under granskning');
    assert.equal(c.lastStatusEventId, 's-Undergranskning');
    assert.equal(c.activityRev, 4);
    assert.equal(c.publicRev, 1);
    const e = await peek('cases/c1/events/s-Undergranskning');
    assert.equal(e.fromStatus, 'Mottaget');
    assert.equal(e.visibility, 'public');
    assert.equal((await change('Under granskning')).outcome, SUPPORT.ALREADY);
    assert.equal((await peek('cases/c1')).publicRev, 1);
  });

  test('follows the transition table only; open/closed is separate', async () => {
    await assert.rejects(change('Pågår'), /BAD_TRANSITION/);
    await assert.rejects(change('Stängd'), /BAD_STATUS/);
    await change('Under granskning');
    await change('Planerat');
    await change('Pågår');
    await assert.rejects(change('Levererat'), /BAD_EVIDENCE/);
    const evidence = { releaseTag: 'v1.2.0', versionCode: 12, distributedAt: Timestamp.fromMillis(Date.now() - 1000), verifiedBy: 'lars' };
    await assert.rejects(change('Levererat', { evidence: { ...evidence, verifiedBy: 'bob' } }), /BAD_EVIDENCE/);
    await assert.rejects(change('Parkerat', { evidence }), /BAD_EVIDENCE/);
    await change('Levererat', { evidence });
    const c = await peek('cases/c1');
    assert.equal(c.statusCache, 'Levererat');
    assert.equal(c.closedAt, undefined);
    assert.equal(c.publicRev, 4);
    assert.equal((await peek('cases/c1/events/s-Levererat')).evidence.releaseTag, 'v1.2.0');
  });

  test('an event id already used for something else is refused', async () => {
    await reply({ eventId: 'x' });
    await assert.rejects(change('Under granskning', { eventId: 'x' }), /EVENT_ID_TAKEN/);
  });

  test('refused on a purging case', async () => {
    await db.doc('cases/c1').update({ retentionState: 'purging' });
    await assert.rejects(change('Under granskning'), /PURGING/);
  });

  test('the table is the rules\' table', () => {
    const rules = readFileSync('firestore.rules', 'utf8');
    for (const [from, tos] of Object.entries(TRANSITIONS)) {
      const line = rules.match(new RegExp(`from == '${from}' && to in \\[([^\\]]*)\\]`));
      assert.ok(line, from);
      assert.deepEqual(line[1].split(',').map((s) => s.trim().replace(/'/g, '')), tos);
    }
    assert.deepEqual(Object.keys(TRANSITIONS).sort(), STATUSES.filter((s) => s !== 'Levererat').sort());
  });
});

describe('internal note and close', () => {
  test('an internal note moves nothing on the case', async () => {
    const before = await peek('cases/c1');
    const r = await internalNote(deps(), { caseId: 'c1', eventId: 'n1', actorUid: 'lars', body: 'Intern' });
    assert.equal(r.outcome, SUPPORT.WRITTEN);
    assert.deepEqual(await peek('cases/c1'), before);
    assert.equal((await peek('cases/c1/events/n1')).visibility, 'internal');
    assert.equal((await internalNote(deps(), { caseId: 'c1', eventId: 'n1', actorUid: 'lars', body: 'Intern' })).outcome, SUPPORT.ALREADY);
    await assert.rejects(internalNote(deps(), { caseId: 'c1', eventId: 'n1', actorUid: 'lars', body: 'x' }), /EVENT_ID_TAKEN/);
  });

  test('close is one way, idempotent, refused while purging', async () => {
    assert.equal((await closeCase(deps(), { caseId: 'c1', actorUid: 'lars' })).outcome, SUPPORT.WRITTEN);
    const first = (await peek('cases/c1')).closedAt;
    assert.equal((await closeCase(deps(), { caseId: 'c1', actorUid: 'lars' })).outcome, SUPPORT.ALREADY);
    assert.deepEqual((await peek('cases/c1')).closedAt, first);
    await db.doc('cases/c2').set({ ownerUid: 'alice', state: 'accepted', retentionState: 'purging', activityRev: 0 });
    await assert.rejects(closeCase(deps(), { caseId: 'c2', actorUid: 'lars' }), /PURGING/);
    await assert.rejects(closeCase(deps(), { caseId: 'c1', actorUid: 'reader' }), /NOT_WRITER/);
  });
});
