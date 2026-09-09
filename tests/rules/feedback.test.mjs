// The feedback proposal — `firestore.feedback.rules`, which is NOT deployed.
//
// Two jobs. First, prove what the proposal would open if it were adopted, and — more
// importantly — what it would still keep shut, so the decision to adopt it can be made from
// evidence rather than from reading a diff. Second, prove that the live ruleset does not
// have this path today, which is the thing "built but not switched on" actually claims.
import { after, before, beforeEach, describe, test } from 'node:test';
import { deleteDoc, doc, getDoc, getDocs, collection, setDoc, updateDoc } from 'firebase/firestore';
import { as, assertFails, assertSucceeds, makeFeedbackEnv, makeM1Env, seed } from './helpers.mjs';

const message = (overrides = {}) => ({
  uid: 'alice',
  message: 'The bubble disappears when I switch apps',
  contact: null,
  appVersion: '1.4.2',
  androidSdk: 33,
  deviceModel: 'OnePlus IN2023',
  createdAtMs: 1757000000000,
  ...overrides,
});

describe('the live ruleset has no feedback path', () => {
  let env;
  before(async () => { env = await makeM1Env(); });
  after(async () => { await env.cleanup(); });
  beforeEach(async () => { await env.clearFirestore(); await seed(env); });

  test('an approved account cannot write feedback today', async () => {
    const db = as(env, 'alice').firestore();
    await assertFails(setDoc(doc(db, 'feedback/row-1'), message()));
  });

  test('not even an admin can', async () => {
    const db = as(env, 'lars').firestore();
    await assertFails(setDoc(doc(db, 'feedback/row-1'), message({ uid: 'lars' })));
  });
});

describe('what the proposal would open', () => {
  let env;
  before(async () => { env = await makeFeedbackEnv(); });
  after(async () => { await env.cleanup(); });
  beforeEach(async () => { await env.clearFirestore(); await seed(env); });

  test('an approved account may file one report, naming itself', async () => {
    const db = as(env, 'alice').firestore();
    await assertSucceeds(setDoc(doc(db, 'feedback/row-1'), message()));
  });

  test('a contact address is allowed but never required', async () => {
    const db = as(env, 'alice').firestore();
    await assertSucceeds(
      setDoc(doc(db, 'feedback/row-2'), message({ contact: 'alice@example.com' })));
  });

  test('the M1 chain is untouched by the addition', async () => {
    const db = as(env, 'alice').firestore();
    await assertSucceeds(getDoc(doc(db, 'users/alice')));
    await assertFails(getDoc(doc(db, 'users/bob')));
    await assertFails(setDoc(doc(db, 'cases/case-new'), { ownerUid: 'alice' }));
  });
});

describe('what the proposal keeps shut', () => {
  let env;
  before(async () => { env = await makeFeedbackEnv(); });
  after(async () => { await env.cleanup(); });
  beforeEach(async () => {
    await env.clearFirestore();
    await seed(env);
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'feedback/existing'), message());
    });
  });

  test('nobody reads feedback back — not the author, not an admin', async () => {
    await assertFails(getDoc(doc(as(env, 'alice').firestore(), 'feedback/existing')));
    await assertFails(getDoc(doc(as(env, 'lars').firestore(), 'feedback/existing')));
  });

  test('the collection cannot be enumerated, so who complained stays private', async () => {
    await assertFails(getDocs(collection(as(env, 'lars').firestore(), 'feedback')));
  });

  test('a filed report cannot be rewritten or erased, including by its author', async () => {
    const db = as(env, 'alice').firestore();
    await assertFails(updateDoc(doc(db, 'feedback/existing'), { message: 'never mind' }));
    await assertFails(deleteDoc(doc(db, 'feedback/existing')));
  });

  test('a duplicate delivery is denied rather than doubled', async () => {
    const db = as(env, 'alice').firestore();
    await assertFails(setDoc(doc(db, 'feedback/existing'), message()));
  });

  test('nobody files a report in somebody else\'s name', async () => {
    const db = as(env, 'alice').firestore();
    await assertFails(setDoc(doc(db, 'feedback/row-3'), message({ uid: 'bob' })));
  });

  test('an account that is not approved cannot file at all', async () => {
    await assertFails(
      setDoc(doc(as(env, 'pat').firestore(), 'feedback/row-4'), message({ uid: 'pat' })));
    await assertFails(
      setDoc(doc(as(env, 'stranger').firestore(), 'feedback/row-5'), message({ uid: 'stranger' })));
  });

  test('an unverified email cannot file, even for an approved account', async () => {
    const db = as(env, 'alice', { email_verified: false }).firestore();
    await assertFails(setDoc(doc(db, 'feedback/row-6'), message()));
  });

  test('a field nobody agreed to is refused, so the shape cannot be widened from the client', async () => {
    const db = as(env, 'alice').firestore();
    await assertFails(
      setDoc(doc(db, 'feedback/row-7'), message({ dictationText: 'my private dictation' })));
    await assertFails(
      setDoc(doc(db, 'feedback/row-8'), message({ audioPath: '/data/rec.m4a' })));
  });

  test('an empty or runaway message is refused', async () => {
    const db = as(env, 'alice').firestore();
    await assertFails(setDoc(doc(db, 'feedback/row-9'), message({ message: '' })));
    await assertFails(
      setDoc(doc(db, 'feedback/row-10'), message({ message: 'a'.repeat(4001) })));
  });
});
