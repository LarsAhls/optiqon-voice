// Losing approval must close the cloud, not just the screen.
//
// These run against `firestore.future.rules` — the full F1 set — because that is the set that
// has cases, events, attachments and private subtrees to lose access to. The Mission 1 set
// closes those paths outright; `m1.test.mjs` proves that separately.
//
// The scenario every test shares: an account that WAS approved, that created real data while
// approved, and that is then rejected, revoked or pushed back to pending. It still holds a
// perfectly valid ID token — revoking a status does not invalidate an issued token, and the
// token is what the Firestore API authenticates. So the rules are the only thing standing
// between that token and the data. There is no client in this picture: `authenticatedContext`
// talks to the emulator's API directly, exactly as `curl` with a bearer token would.
import { after, before, beforeEach, describe, test } from 'node:test';
import {
  collection, doc, getDoc, getDocs, serverTimestamp, setDoc, updateDoc,
} from 'firebase/firestore';
import { as, assertFails, assertSucceeds, makeEnv, seed } from './helpers.mjs';

let env;
before(async () => { env = await makeEnv(); });
after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); await seed(env); });

/** Everything alice owns, written while she was still approved. */
async function givenAliceHasData() {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, 'cases/case-alice/events/e1'), {
      type: 'status_change', visibility: 'public', actorUid: 'lars',
      fromStatus: 'Mottaget', toStatus: 'Under granskning', createdAt: serverTimestamp(),
    });
    await setDoc(doc(db, 'cases/case-alice/attachments/a1'), {
      ownerUid: 'alice', caseId: 'case-alice', sha256: 'a'.repeat(64),
      createdAt: serverTimestamp(),
    });
    await setDoc(doc(db, 'users/alice/uploads/a1'), {
      caseId: 'case-alice', maxBytes: 1024, sha256: 'a'.repeat(64),
      createdAt: serverTimestamp(),
    });
    await setDoc(doc(db, 'users/alice/sync/state'), { cursor: 12 });
    await setDoc(doc(db, 'users/alice/reads/news-1'), { readAt: serverTimestamp() });
  });
}

async function setAliceStatus(status) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await updateDoc(doc(ctx.firestore(), 'users/alice'), { status });
  });
}

for (const status of ['revoked', 'rejected', 'pending']) {
  describe(`an account that is ${status} keeps its token and loses its data`, () => {
    beforeEach(async () => {
      await givenAliceHasData();
      await setAliceStatus(status);
    });

    test('its own old cases are closed to it', async () => {
      const db = as(env, 'alice').firestore();
      await assertFails(getDoc(doc(db, 'cases/case-alice')));
      await assertFails(getDocs(collection(db, 'cases')));
    });

    test('the events on its own cases are closed to it', async () => {
      const db = as(env, 'alice').firestore();
      await assertFails(getDoc(doc(db, 'cases/case-alice/events/e1')));
      await assertFails(getDocs(collection(db, 'cases/case-alice/events')));
    });

    test('attachment metadata is closed to it', async () => {
      const db = as(env, 'alice').firestore();
      await assertFails(getDoc(doc(db, 'cases/case-alice/attachments/a1')));
      await assertFails(getDocs(collection(db, 'cases/case-alice/attachments')));
    });

    test('its upload reservations and quota are closed to it', async () => {
      const db = as(env, 'alice').firestore();
      await assertFails(getDoc(doc(db, 'users/alice/uploads/a1')));
      await assertFails(getDoc(doc(db, 'users/alice/quota/attachments')));
    });

    test('its private sync and read-status are closed to it', async () => {
      const db = as(env, 'alice').firestore();
      await assertFails(getDoc(doc(db, 'users/alice/sync/state')));
      await assertFails(getDoc(doc(db, 'users/alice/reads/news-1')));
      await assertFails(getDocs(collection(db, 'users/alice/reads')));
    });

    test('the seat count and the news it used to read are closed to it', async () => {
      const db = as(env, 'alice').firestore();
      await assertFails(getDoc(doc(db, 'config/counters')));
      await assertFails(getDoc(doc(db, 'config/limits')));
      await assertFails(getDocs(collection(db, 'news')));
    });

    test('another account stays isolated from it, in both directions', async () => {
      const alice = as(env, 'alice').firestore();
      await assertFails(getDoc(doc(alice, 'cases/case-bob')));
      await assertFails(getDoc(doc(alice, 'users/bob/sync/state')));
      await assertFails(getDoc(doc(alice, 'users/bob')));

      // And bob, still approved, gains nothing from alice's fall.
      const bob = as(env, 'bob').firestore();
      await assertFails(getDoc(doc(bob, 'cases/case-alice')));
      await assertFails(getDoc(doc(bob, 'users/alice/sync/state')));
    });

    test('it can still read its own status document, and register if it had none', async () => {
      // The one read that must survive: without it the app can never show the user why it
      // stopped working. It exposes the reader's own record and nothing else.
      const db = as(env, 'alice').firestore();
      await assertSucceeds(getDoc(doc(db, 'users/alice')));

      // And a brand-new account is not collateral damage of the tightening.
      const fresh = as(env, 'newcomer').firestore();
      await assertSucceeds(setDoc(doc(fresh, 'users/newcomer'), {
        uid: 'newcomer', email: 'newcomer@example.com', displayName: 'Newcomer',
        status: 'pending', createdAt: serverTimestamp(),
      }));
    });
  });
}

describe('an admin whose own account was revoked stops being an admin', () => {
  test('a revoked writer cannot read the roster, the seats or anybody else', async () => {
    // isReader()/isWriter() are built on isApproved(), so revoking the underlying account is
    // enough — the admins/ document does not have to be touched. Worth pinning: it is the
    // difference between one revocation and two.
    await env.withSecurityRulesDisabled(async (ctx) => {
      await updateDoc(doc(ctx.firestore(), 'users/lars'), { status: 'revoked' });
    });
    const db = as(env, 'lars').firestore();
    await assertFails(getDoc(doc(db, 'admins/lars')));
    await assertFails(getDoc(doc(db, 'config/counters')));
    await assertFails(getDoc(doc(db, 'users/alice')));
    await assertFails(getDocs(collection(db, 'users')));
    await assertSucceeds(getDoc(doc(db, 'users/lars')));
  });
});
