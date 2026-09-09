// The seat counter, and the one thing it must never become: a number a client can move on
// its own.
//
// `config/counters.approvedUsers` is not bookkeeping. It is compared against
// `config/limits.maxApprovedUsers` to decide whether another account may be approved at all,
// so a counter that can drift away from the accounts it claims to count is a licence control
// that can be talked out of its own ceiling. The users/ rule already refused an approval that
// did not move the counter. It did not refuse a counter that moved without an approval, and
// the two are not the same guarantee.
//
// This suite exercises `firestore.rules` — the deployed Mission 1 set — through
// `makeM1Env()`. Almost every caller here is `lars`, the writer-admin: the strongest caller
// these rules recognise. A path that denies the writer denies everybody.
import { after, before, beforeEach, describe, test } from 'node:test';
import { doc, getDoc, serverTimestamp, setDoc, updateDoc, writeBatch } from 'firebase/firestore';
import { as, assertFails, assertSucceeds, makeM1Env, seed } from './helpers.mjs';

let env;
before(async () => { env = await makeM1Env(); });
after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); await seed(env); });

/** The decision half of the commit: the write the counter is supposed to be accounting for. */
const decide = (batch, db, userId, status) => batch.update(doc(db, `users/${userId}`), {
  status, decidedBy: 'lars', decidedAt: serverTimestamp(),
});

/** The counter half. `seatFor` names the account whose transition this movement pays for. */
const moveSeat = (batch, db, approvedUsers, seatFor) =>
  batch.update(doc(db, 'config/counters'), { approvedUsers, seatFor });

function assertEq(actual, expected) {
  if (actual !== expected) {
    throw new Error(`expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

async function counterIs(db, expected) {
  assertEq((await getDoc(doc(db, 'config/counters'))).data().approvedUsers, expected);
}

describe('the counter cannot move on its own', () => {
  test('a writer cannot take a seat without approving anybody', async () => {
    const db = as(env, 'lars').firestore();
    await assertFails(updateDoc(doc(db, 'config/counters'), {
      approvedUsers: 5, seatFor: 'pat',
    }));
  });

  test('a writer cannot return a seat without revoking anybody', async () => {
    const db = as(env, 'lars').firestore();
    await assertFails(updateDoc(doc(db, 'config/counters'), {
      approvedUsers: 3, seatFor: 'alice',
    }));
  });

  test('a counter write that names nobody is refused', async () => {
    // The pre-fix shape: approvedUsers alone, with nothing to check it against.
    const db = as(env, 'lars').firestore();
    await assertFails(updateDoc(doc(db, 'config/counters'), { approvedUsers: 5 }));
    await assertFails(updateDoc(doc(db, 'config/counters'), { approvedUsers: 3 }));
  });

  test('a counter write naming an account that does not exist is refused', async () => {
    const db = as(env, 'lars').firestore();
    await assertFails(updateDoc(doc(db, 'config/counters'), {
      approvedUsers: 5, seatFor: 'nobody',
    }));
  });

  test('the seat cannot be paid to one account while another is approved', async () => {
    // The batch is well-formed on its face — a real approval, a real +1 — but the seat is
    // booked against somebody who is not moving. That is exactly how a counter drifts.
    const db = as(env, 'lars').firestore();
    const batch = writeBatch(db);
    decide(batch, db, 'pat', 'approved');
    moveSeat(batch, db, 5, 'bob');
    await assertFails(batch.commit());
  });

  test('two approvals cannot share one seat', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'users/quinn'), {
        uid: 'quinn', email: 'quinn@example.com', displayName: 'quinn',
        status: 'pending', createdAt: serverTimestamp(),
      });
    });
    const db = as(env, 'lars').firestore();
    const batch = writeBatch(db);
    decide(batch, db, 'pat', 'approved');
    decide(batch, db, 'quinn', 'approved');
    moveSeat(batch, db, 5, 'pat');
    await assertFails(batch.commit());
  });
});

describe('the ceiling cannot be walked around', () => {
  test('a standalone bump past maxApprovedUsers is refused', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'config/counters'), { approvedUsers: 10 });
    });
    const db = as(env, 'lars').firestore();
    await assertFails(updateDoc(doc(db, 'config/counters'), {
      approvedUsers: 11, seatFor: 'pat',
    }));
  });

  test('the counter cannot be lowered to make room without a revocation', async () => {
    // Freeing headroom is the cheapest bypass there is: drop the counter below the ceiling
    // without revoking anybody, and every later approval looks legal.
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'config/counters'), { approvedUsers: 10 });
    });
    const db = as(env, 'lars').firestore();
    await assertFails(updateDoc(doc(db, 'config/counters'), {
      approvedUsers: 9, seatFor: 'alice',
    }));
    await counterIs(db, 10);
  });

  test('the counter cannot jump by more than one seat', async () => {
    const db = as(env, 'lars').firestore();
    const batch = writeBatch(db);
    decide(batch, db, 'pat', 'approved');
    moveSeat(batch, db, 7, 'pat');
    await assertFails(batch.commit());
  });

  test('a negative counter is refused', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'config/counters'), { approvedUsers: 0 });
    });
    const db = as(env, 'lars').firestore();
    const batch = writeBatch(db);
    decide(batch, db, 'alice', 'revoked');
    moveSeat(batch, db, -1, 'alice');
    await assertFails(batch.commit());
  });
});

describe('the direction has to match the transition', () => {
  test('a rejection cannot return a seat the account never held', async () => {
    const db = as(env, 'lars').firestore();
    const batch = writeBatch(db);
    decide(batch, db, 'pat', 'rejected');   // pat is pending; no seat was ever taken
    moveSeat(batch, db, 3, 'pat');
    await assertFails(batch.commit());
  });

  test('a revocation cannot take a seat', async () => {
    const db = as(env, 'lars').firestore();
    const batch = writeBatch(db);
    decide(batch, db, 'alice', 'revoked');
    moveSeat(batch, db, 5, 'alice');
    await assertFails(batch.commit());
  });

  test('an approval cannot return a seat', async () => {
    const db = as(env, 'lars').firestore();
    const batch = writeBatch(db);
    decide(batch, db, 'pat', 'approved');
    moveSeat(batch, db, 3, 'pat');
    await assertFails(batch.commit());
  });

  test('an already-approved account cannot be approved again for another seat', async () => {
    const db = as(env, 'lars').firestore();
    const batch = writeBatch(db);
    decide(batch, db, 'alice', 'approved');   // alice is already approved
    moveSeat(batch, db, 5, 'alice');
    await assertFails(batch.commit());
  });

  test('a reader-admin moves nothing', async () => {
    const db = as(env, 'reader').firestore();
    const batch = writeBatch(db);
    decide(batch, db, 'pat', 'approved');
    moveSeat(batch, db, 5, 'pat');
    await assertFails(batch.commit());
  });
});

describe('the real transitions still commit, atomically', () => {
  test('pending to approved takes exactly one seat', async () => {
    const db = as(env, 'lars').firestore();
    const batch = writeBatch(db);
    decide(batch, db, 'pat', 'approved');
    moveSeat(batch, db, 5, 'pat');
    await assertSucceeds(batch.commit());

    assertEq((await getDoc(doc(db, 'users/pat'))).data().status, 'approved');
    await counterIs(db, 5);
    assertEq((await getDoc(doc(db, 'config/counters'))).data().seatFor, 'pat');
  });

  test('approved to revoked returns exactly one seat', async () => {
    const db = as(env, 'lars').firestore();
    const batch = writeBatch(db);
    decide(batch, db, 'alice', 'revoked');
    moveSeat(batch, db, 3, 'alice');
    await assertSucceeds(batch.commit());

    assertEq((await getDoc(doc(db, 'users/alice'))).data().status, 'revoked');
    await counterIs(db, 3);
  });

  test('approved to rejected also returns the seat', async () => {
    const db = as(env, 'lars').firestore();
    const batch = writeBatch(db);
    decide(batch, db, 'bob', 'rejected');
    moveSeat(batch, db, 3, 'bob');
    await assertSucceeds(batch.commit());
  });

  test('pending to rejected commits alone, and leaves the counter where it was', async () => {
    const db = as(env, 'lars').firestore();
    await assertSucceeds(updateDoc(doc(db, 'users/pat'), {
      status: 'rejected', decidedBy: 'lars', decidedAt: serverTimestamp(),
    }));
    await counterIs(db, 4);
  });
});
