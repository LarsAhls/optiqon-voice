// The rule set that actually gets deployed.
//
// Every other suite in this directory exercises `firestore.future.rules`, the full F1 set.
// This one exercises `firestore.rules` — the Mission 1 set — and its job is twofold: prove
// that the one chain Mission 1 ships still works end to end, and prove that every path
// belonging to a feature Mission 1 does not ship is shut.
//
// The closure tests deny an approved writer-admin, not a stranger. That is deliberate: an
// admin is the strongest caller these rules recognise, so a path that denies Lars denies
// everybody, and the test does not have to enumerate weaker callers to be convincing.
import { after, before, beforeEach, describe, test } from 'node:test';
import {
  collection, doc, getDoc, getDocs, serverTimestamp, setDoc, updateDoc, writeBatch,
} from 'firebase/firestore';
import { as, assertFails, assertSucceeds, makeM1Env, seed } from './helpers.mjs';

let env;
before(async () => { env = await makeM1Env(); });
after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); await seed(env); });

describe('the Mission 1 chain works', () => {
  test('a verified newcomer registers itself as pending', async () => {
    const db = as(env, 'newcomer').firestore();
    await assertSucceeds(setDoc(doc(db, 'users/newcomer'), {
      uid: 'newcomer', email: 'newcomer@example.com', displayName: 'Ny Testare',
      status: 'pending', createdAt: serverTimestamp(),
    }));
  });

  test('registration is a claim, not a grant', async () => {
    const db = as(env, 'newcomer').firestore();
    await assertFails(setDoc(doc(db, 'users/newcomer'), {
      uid: 'newcomer', email: 'newcomer@example.com', displayName: 'Ny Testare',
      status: 'approved', createdAt: serverTimestamp(),
    }));
  });

  test('an unverified address cannot register at all', async () => {
    const db = as(env, 'newcomer', { email_verified: false }).firestore();
    await assertFails(setDoc(doc(db, 'users/newcomer'), {
      uid: 'newcomer', email: 'newcomer@example.com', displayName: 'Ny Testare',
      status: 'pending', createdAt: serverTimestamp(),
    }));
  });

  test('every account reads its own status, whatever that status is', async () => {
    for (const [uid, status] of [['pat', 'pending'], ['alice', 'approved']]) {
      await env.withSecurityRulesDisabled(async (ctx) => {
        await updateDoc(doc(ctx.firestore(), `users/${uid}`), { status });
      });
      await assertSucceeds(getDoc(doc(as(env, uid).firestore(), `users/${uid}`)));
    }
  });

  test('nobody reads anybody else', async () => {
    await assertFails(getDoc(doc(as(env, 'alice').firestore(), 'users/bob')));
  });

  test('an admin approves a pending account and takes a seat in the same commit', async () => {
    const db = as(env, 'lars').firestore();
    const batch = writeBatch(db);
    batch.update(doc(db, 'users/pat'), {
      status: 'approved', decidedBy: 'lars', decidedAt: serverTimestamp(),
    });
    batch.update(doc(db, 'config/counters'), { approvedUsers: 5, seatFor: 'pat' });
    await assertSucceeds(batch.commit());
  });

  test('an approval without its seat is refused', async () => {
    const db = as(env, 'lars').firestore();
    await assertFails(updateDoc(doc(db, 'users/pat'), {
      status: 'approved', decidedBy: 'lars', decidedAt: serverTimestamp(),
    }));
  });

  test('a revocation returns the seat in the same commit', async () => {
    const db = as(env, 'lars').firestore();
    const batch = writeBatch(db);
    batch.update(doc(db, 'users/alice'), {
      status: 'revoked', decidedBy: 'lars', decidedAt: serverTimestamp(),
    });
    batch.update(doc(db, 'config/counters'), { approvedUsers: 3, seatFor: 'alice' });
    await assertSucceeds(batch.commit());
  });

  test('a revocation that keeps the seat is refused', async () => {
    // Without this the counter is a high-water mark, and the ceiling it guards drifts.
    const db = as(env, 'lars').firestore();
    await assertFails(updateDoc(doc(db, 'users/alice'), {
      status: 'revoked', decidedBy: 'lars', decidedAt: serverTimestamp(),
    }));
  });

  test('rejecting a pending account does not move the counter', async () => {
    // pat never held a seat, so returning one would credit a seat that was never taken.
    const db = as(env, 'lars').firestore();
    await assertSucceeds(updateDoc(doc(db, 'users/pat'), {
      status: 'rejected', decidedBy: 'lars', decidedAt: serverTimestamp(),
    }));

    const batch = writeBatch(db);
    batch.update(doc(db, 'users/bob'), {
      status: 'rejected', decidedBy: 'lars', decidedAt: serverTimestamp(),
    });
    batch.update(doc(db, 'config/counters'), { approvedUsers: 4 });
    await assertFails(batch.commit());
  });

  test('an ordinary approved account is not an admin', async () => {
    const db = as(env, 'alice').firestore();
    await assertFails(updateDoc(doc(db, 'users/pat'), {
      status: 'approved', decidedBy: 'alice', decidedAt: serverTimestamp(),
    }));
    await assertFails(getDoc(doc(db, 'config/counters')));
    await assertFails(getDocs(collection(db, 'users')));
  });

  test('an admin sees who is waiting and can count what is approved', async () => {
    const db = as(env, 'lars').firestore();
    await assertSucceeds(getDocs(collection(db, 'users')));
    await assertSucceeds(getDoc(doc(db, 'config/counters')));
    await assertSucceeds(getDoc(doc(db, 'config/limits')));
  });

  test('a seat cannot be taken past the ceiling', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'config/counters'), { approvedUsers: 10 });
    });
    const db = as(env, 'lars').firestore();
    const batch = writeBatch(db);
    batch.update(doc(db, 'users/pat'), {
      status: 'approved', decidedBy: 'lars', decidedAt: serverTimestamp(),
    });
    batch.update(doc(db, 'config/counters'), { approvedUsers: 11 });
    await assertFails(batch.commit());
  });

  test('nobody deletes a user, and nobody renames themselves', async () => {
    // Mission 1 ships no profile editing, so the rule that would allow it is not live.
    await assertFails(updateDoc(doc(as(env, 'alice').firestore(), 'users/alice'), {
      displayName: 'Someone Else',
    }));
  });
});

describe('the features Mission 1 does not ship are shut', () => {
  // Seeded past the rules so the documents genuinely exist: a denial has to come from the
  // rule, not from there being nothing at the path.
  beforeEach(async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await setDoc(doc(db, 'cases/case-alice/events/e1'), {
        type: 'note', visibility: 'public', actorUid: 'lars', createdAt: serverTimestamp(),
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
      await setDoc(doc(db, 'news/n1'), { title: 'Hello' });
      await setDoc(doc(db, 'invites/newbie@example.com'), { invitedBy: 'lars' });
      await setDoc(doc(db, 'deletionRequests/alice'), { requestedAt: serverTimestamp() });
      await setDoc(doc(db, 'admins/lars'), { role: 'writer', grantedAt: serverTimestamp() });
    });
  });

  const closed = [
    ['a case', 'cases/case-alice'],
    ['a case event', 'cases/case-alice/events/e1'],
    ['attachment metadata', 'cases/case-alice/attachments/a1'],
    ['an upload reservation', 'users/alice/uploads/a1'],
    ['an upload quota', 'users/alice/quota/attachments'],
    ['a private sync document', 'users/alice/sync/state'],
    ['a private read marker', 'users/alice/reads/news-1'],
    ['a news item', 'news/n1'],
    ['an invite', 'invites/newbie@example.com'],
    ['a deletion request', 'deletionRequests/alice'],
    ['the admin roster', 'admins/lars'],
  ];

  for (const [what, path] of closed) {
    test(`${what} is unreachable, even for a writer-admin`, async () => {
      // Direct API with a valid token, no client in between. If an admin cannot get here,
      // nobody can.
      const lars = as(env, 'lars').firestore();
      await assertFails(getDoc(doc(lars, path)));
      await assertFails(setDoc(doc(lars, path), { touched: true }));

      // And the owner of the data fares no better.
      const alice = as(env, 'alice').firestore();
      await assertFails(getDoc(doc(alice, path)));
      await assertFails(setDoc(doc(alice, path), { touched: true }));
    });
  }

  test('the collections behind them do not list either', async () => {
    for (const uid of ['lars', 'alice', 'pat']) {
      const db = as(env, uid).firestore();
      await assertFails(getDocs(collection(db, 'cases')));
      await assertFails(getDocs(collection(db, 'news')));
      await assertFails(getDocs(collection(db, 'invites')));
      await assertFails(getDocs(collection(db, 'deletionRequests')));
      await assertFails(getDocs(collection(db, 'users/alice/reads')));
    }
  });

  test('support and deletion stay reachable without a rule, by email', async () => {
    // deletionRequests being shut is not a gap. In Mission 1 the revoked screen shows
    // support@optiqon.se and opens the device's own mail composer; the app transmits nothing
    // itself. A revoked account's route to support is therefore email, which no rule can take
    // away — and this test exists so that opening a Firestore write path here later is a
    // deliberate decision rather than a quiet one. All it pins is that a revoked account can
    // still read the status document the screen is drawn from.
    await env.withSecurityRulesDisabled(async (ctx) => {
      await updateDoc(doc(ctx.firestore(), 'users/alice'), { status: 'revoked' });
    });
    const db = as(env, 'alice').firestore();
    await assertSucceeds(getDoc(doc(db, 'users/alice')));
    await assertFails(setDoc(doc(db, 'deletionRequests/alice'), {
      requestedAt: serverTimestamp(),
    }));
  });
});
