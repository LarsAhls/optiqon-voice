// Negative tests 1-6 and 12: who exists, who is an admin, and what a registration or an
// approval is allowed to claim.
import { after, before, beforeEach, describe, test } from 'node:test';
import {
  collection, doc, getDoc, getDocs, query, serverTimestamp, setDoc, updateDoc, where,
  writeBatch,
} from 'firebase/firestore';
import { as, assertFails, assertSucceeds, makeEnv, seed } from './helpers.mjs';

let env;
before(async () => { env = await makeEnv(); });
after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); await seed(env); });

describe('registration is a claim, not a grant', () => {
  test('a new user may only write status pending (test 2)', async () => {
    const db = as(env, 'newbie').firestore();
    const base = {
      uid: 'newbie',
      email: 'newbie@example.com',
      displayName: 'Newbie',
      createdAt: serverTimestamp(),
    };
    await assertFails(setDoc(doc(db, 'users/newbie'), { ...base, status: 'approved' }));
    await assertSucceeds(setDoc(doc(db, 'users/newbie'), { ...base, status: 'pending' }));
  });

  test('an unverified email cannot register at all', async () => {
    const db = as(env, 'newbie', { email_verified: false }).firestore();
    await assertFails(setDoc(doc(db, 'users/newbie'), {
      uid: 'newbie',
      email: 'newbie@example.com',
      displayName: 'Newbie',
      status: 'pending',
      createdAt: serverTimestamp(),
    }));
  });

  test('a user cannot register under another account email', async () => {
    const db = as(env, 'newbie').firestore();
    await assertFails(setDoc(doc(db, 'users/newbie'), {
      uid: 'newbie',
      email: 'lars@example.com',
      displayName: 'Newbie',
      status: 'pending',
      createdAt: serverTimestamp(),
    }));
  });

  // A name is required and a phone number is not, which only means something if the rules
  // and the client agree on what a name is. They previously did not: `displayName.size() > 0`
  // accepts a single space, so a registration could arrive that the person approving it
  // cannot identify. These pin the same bounds DisplayName.kt applies.
  test('a blank or untrimmed name is not a registration', async () => {
    const db = as(env, 'newbie').firestore();
    const base = {
      uid: 'newbie',
      email: 'newbie@example.com',
      status: 'pending',
      createdAt: serverTimestamp(),
    };
    const register = (displayName) =>
      setDoc(doc(db, 'users/newbie'), { ...base, displayName });

    await assertFails(register(''));
    await assertFails(register(' '));
    await assertFails(register('  Newbie  '));
    await assertFails(register('N'));
    await assertFails(register('N'.repeat(81)));
    await assertFails(register(42));
    await assertSucceeds(register('Newbie'));
  });

  test('the owner cannot rename themselves to something unregistrable', async () => {
    const db = as(env, 'alice').firestore();
    const ref = doc(db, 'users/alice');

    await assertFails(updateDoc(ref, { displayName: ' ' }));
    await assertFails(updateDoc(ref, { displayName: 'A' }));
    await assertFails(updateDoc(ref, { displayName: 'A'.repeat(81) }));
    await assertSucceeds(updateDoc(ref, { displayName: 'Alice Anderson' }));
  });

  test('a pending user cannot approve themselves (test 3)', async () => {
    const db = as(env, 'pat').firestore();
    await assertFails(updateDoc(doc(db, 'users/pat'), { status: 'approved' }));
  });

  test('the owner cannot change email or the decision fields (test 3)', async () => {
    const db = as(env, 'alice').firestore();
    const ref = doc(db, 'users/alice');
    await assertFails(updateDoc(ref, { email: 'other@example.com' }));
    await assertFails(updateDoc(ref, { decidedBy: 'alice', decidedAt: serverTimestamp() }));
    await assertFails(updateDoc(ref, { status: 'revoked' }));
    await assertSucceeds(updateDoc(ref, { displayName: 'Alice A' }));
  });

  // diff() reports the keys that actually changed, so rewriting a field with the value
  // it already holds affects nothing and is allowed. It grants no privilege — the write
  // cannot move the document — but the distinction matters when reading these rules.
  test('rewriting an unchanged status is a no-op, not an escalation', async () => {
    const db = as(env, 'alice').firestore();
    await assertSucceeds(updateDoc(doc(db, 'users/alice'), { status: 'approved' }));
  });
});

describe('token identity must match the stored account', () => {
  test('a mismatched token email loses all cloud access (test 4)', async () => {
    const db = as(env, 'alice', { email: 'attacker@example.com' }).firestore();
    // Reading your own users/ document only needs the uid, but everything gated on
    // approval is now unreachable.
    await assertSucceeds(getDoc(doc(db, 'users/alice')));
    await assertFails(setDoc(doc(db, 'cases/case-new'), {
      ownerUid: 'alice',
      title: 'x',
      body: '',
      statusCache: 'Mottaget',
      lastStatusEventId: null,
      attachmentCount: 0,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    }));
  });
});

describe('the admin roster is not readable', () => {
  test('self-get works, other uids and listing do not (test 5)', async () => {
    const db = as(env, 'lars').firestore();
    await assertSucceeds(getDoc(doc(db, 'admins/lars')));
    await assertFails(getDoc(doc(db, 'admins/reader')));
    await assertFails(getDocs(collection(db, 'admins')));
    await assertFails(setDoc(doc(db, 'admins/alice'), { role: 'writer' }));
  });

  test('a revoked admin and an expired writer are not admins (test 6)', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await setDoc(doc(db, 'admins/lars'), { role: 'writer', revokedAt: new Date() });
      await setDoc(doc(db, 'admins/reader'), {
        role: 'reader',
        activeUntil: new Date(Date.now() - 60_000),
      });
    });
    const lars = as(env, 'lars').firestore();
    const reader = as(env, 'reader').firestore();
    await assertFails(getDocs(collection(lars, 'users')));
    await assertFails(getDocs(collection(reader, 'users')));
  });
});

describe('cases are not readable by everyone', () => {
  test('an unconstrained collection read is denied in full (test 1)', async () => {
    const db = as(env, 'alice').firestore();
    await assertFails(getDocs(collection(db, 'cases')));
    await assertSucceeds(
      getDocs(query(collection(db, 'cases'), where('ownerUid', '==', 'alice'))));
    await assertFails(getDoc(doc(db, 'cases/case-bob')));
  });

  test('a reader-admin may read every case but write none', async () => {
    const db = as(env, 'reader').firestore();
    await assertSucceeds(getDocs(collection(db, 'cases')));
    await assertFails(updateDoc(doc(db, 'cases/case-bob'), {
      statusCache: 'Under granskning',
      lastStatusEventId: 'e1',
      updatedAt: serverTimestamp(),
    }));
  });
});

describe('the approved-user ceiling is enforced by the rules', () => {
  const approve = (db, counterValue) => {
    const batch = writeBatch(db);
    batch.update(doc(db, 'users/pat'), {
      status: 'approved',
      decidedBy: 'lars',
      decidedAt: serverTimestamp(),
    });
    if (counterValue !== null) {
      batch.update(doc(db, 'config/counters'), { approvedUsers: counterValue });
    }
    return batch.commit();
  };

  test('approval without a counter bump is denied (test 12)', async () => {
    const db = as(env, 'lars').firestore();
    await assertFails(approve(db, null));
  });

  test('approval with the bump succeeds', async () => {
    const db = as(env, 'lars').firestore();
    await assertSucceeds(approve(db, 5));
  });

  test('a bump past maxApprovedUsers is denied, even for the writer (test 12)', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'config/counters'), { approvedUsers: 10 });
    });
    const db = as(env, 'lars').firestore();
    await assertFails(approve(db, 11));
  });

  test('a reader-admin cannot approve anyone', async () => {
    const db = as(env, 'reader').firestore();
    await assertFails(approve(db, 5));
  });
});
