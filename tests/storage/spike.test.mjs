// PR-0 — the Mission D storage spike.
//
// This suite exists to answer three questions that no amount of reading can settle, and
// that between them decide whether the approved attachment architecture is buildable at
// all:
//
//   P1  Can a Cloud Storage rule call `firestore.get()` under the emulator, with the
//       outcome actually depending on Firestore state?
//   P2  Is cross-service document access counted per *distinct document* or per call?
//       The approved design reads two documents but touches them eight times.
//   P5  Is there a third-document ceiling at all, and does the design stay under it?
//
// P3 (owner liveness) and P4 (admin liveness and the users/ mirror) then exercise the
// contract itself against those answers.
//
// Nothing here is deployed: `firebase.json` does not name `storage.future.rules`, and the
// emulator is started from `firebase.spike.json`.
import { readFileSync } from 'node:fs';
import { after, before, beforeEach, describe, it } from 'node:test';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';

const PROJECT_ID = 'optiqon-voice-rules-test';
const BUCKET = `gs://${PROJECT_ID}.appspot.com`;
const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const IMAGE = { contentType: 'image/png' };

let env;

/** A signed-in context. Defaults keep the token and the seeded users/ document agreeing. */
function as(uid, overrides = {}) {
  return env.authenticatedContext(uid, {
    email: `${uid}@example.com`,
    email_verified: true,
    ...overrides,
  });
}

/** The object path the contract is written about. */
function objectRef(ctx, ownerUid, caseId, aid) {
  return ctx.storage(BUCKET).ref(`case-attachments/${ownerUid}/${caseId}/${aid}`);
}

before(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules: readFileSync('firestore.rules', 'utf8'),
      host: '127.0.0.1',
      port: 8080,
    },
    storage: {
      rules: readFileSync('storage.future.rules', 'utf8'),
      host: '127.0.0.1',
      port: 9199,
    },
  });
});

after(async () => {
  await env.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
  await env.clearStorage();

  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    const now = new Date();
    const hour = 60 * 60 * 1000;

    // Ordinary accounts, one per status the owner path has to distinguish.
    const users = {
      alice: { status: 'approved' },
      bob: { status: 'approved' },
      rev: { status: 'revoked' },
      pat: { status: 'pending' },
      rej: { status: 'rejected' },
      // Admin principals. `adminActive`/`adminActiveUntil` are the mirror of
      // admins/{uid}; the Storage rules never read admins/ itself.
      lars: { status: 'approved', adminActive: true },
      larsuntil: { status: 'approved', adminActive: true, adminActiveUntil: new Date(Date.now() + hour) },
      larspast: { status: 'approved', adminActive: true, adminActiveUntil: new Date(Date.now() - hour) },
      larsrevoked: { status: 'revoked', adminActive: true },
      larsoff: { status: 'approved', adminActive: false },
      larsnomirror: { status: 'approved' },
    };
    for (const [uid, extra] of Object.entries(users)) {
      await db.doc(`users/${uid}`).set({
        uid,
        email: `${uid}@example.com`,
        displayName: uid,
        createdAt: now,
        ...extra,
      });
    }

    await db.doc('config/limits').set({ maxApprovedUsers: 10, uploadsEnabled: true });
    await db.doc('config/counters').set({ approvedUsers: 4 });

    // Reservations. `a1` and `tomb` already have bytes; `fresh` and `small` do not.
    const reservation = (aid, extra = {}) =>
      db.doc(`cases/case-alice/attachments/${aid}`).set({
        ownerUid: 'alice',
        caseId: 'case-alice',
        sha256: 'x'.repeat(64),
        maxBytes: 2097152,
        createdAt: now,
        ...extra,
      });
    await reservation('a1');
    await reservation('fresh');
    await reservation('small', { maxBytes: 4 });
    await reservation('tomb', { deleteRequestedAt: now });
    // A reservation whose ownerUid disagrees with the object path it will be used on.
    await db.doc('cases/case-bob/attachments/mismatch').set({
      ownerUid: 'bob',
      caseId: 'case-bob',
      sha256: 'y'.repeat(64),
      maxBytes: 2097152,
      createdAt: now,
    });

    // Objects that already exist, so a denied read is a denial and not a 404.
    const storage = ctx.storage(BUCKET);
    for (const path of [
      'case-attachments/alice/case-alice/a1',
      'case-attachments/alice/case-alice/tomb',
      'probe-2doc-manycalls/alice/case-alice/a1',
      'probe-3doc/alice/case-alice/a1',
      'probe-5doc/alice/case-alice/a1',
    ]) {
      await storage.ref(path).put(PNG, IMAGE);
    }
  });
});

// ---------------------------------------------------------------------------- P1

describe('P1 — cross-service firestore.get() in Storage rules', () => {
  it('allows the read when Firestore says the account is approved', async () => {
    await assertSucceeds(objectRef(as('alice'), 'alice', 'case-alice', 'a1').getMetadata());
  });

  it('denies the same request after Firestore state changes, with nothing else altered', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await ctx.firestore().doc('users/alice').update({ status: 'revoked' });
    });
    await assertFails(objectRef(as('alice'), 'alice', 'case-alice', 'a1').getMetadata());
  });
});

// ---------------------------------------------------------------------------- P2 / P5

describe('P2/P5 — how cross-service document access is counted', () => {
  // These three probes are a measurement, not a contract. Read together they answer
  // whether the emulator can settle the dedupe question at all.
  //
  // Measured on this toolchain: all three succeed. The emulator enforces no
  // two-document ceiling, which means a passing two-document/eight-call probe is not
  // evidence of dedupe — there is no budget here for eight accesses to exceed. The
  // assertions below therefore record what the emulator *does*; they deliberately do
  // not pretend it proves what only production can.
  const probe = (path) => as('alice').storage(BUCKET).ref(path).getMetadata();

  it('allows two distinct documents read eight times over', async () => {
    await assertSucceeds(probe('probe-2doc-manycalls/alice/case-alice/a1'));
  });

  it('allows three distinct documents — the ceiling is not enforced here', async () => {
    await assertSucceeds(probe('probe-3doc/alice/case-alice/a1'));
  });

  it('allows five distinct documents — no ceiling whatsoever', async () => {
    await assertSucceeds(probe('probe-5doc/alice/case-alice/a1'));
  });
});

// ---------------------------------------------------------------------------- P3

describe('P3 — owner liveness', () => {
  it('approved owner uploads against a valid reservation', async () => {
    await assertSucceeds(objectRef(as('alice'), 'alice', 'case-alice', 'fresh').put(PNG, IMAGE));
  });

  it('approved owner reads their own attachment', async () => {
    await assertSucceeds(objectRef(as('alice'), 'alice', 'case-alice', 'a1').getMetadata());
  });

  it('revoked owner cannot spend an already issued reservation', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await ctx.firestore().doc('users/alice').update({ status: 'revoked' });
    });
    await assertFails(objectRef(as('alice'), 'alice', 'case-alice', 'fresh').put(PNG, IMAGE));
  });

  it('revoked owner cannot read an existing attachment', async () => {
    await assertFails(objectRef(as('rev'), 'rev', 'case-alice', 'a1').getMetadata());
  });

  it('pending account is denied', async () => {
    await assertFails(objectRef(as('pat'), 'pat', 'case-alice', 'a1').getMetadata());
  });

  it('rejected account is denied', async () => {
    await assertFails(objectRef(as('rej'), 'rej', 'case-alice', 'a1').getMetadata());
  });

  it('denies a token whose email disagrees with the users/ document', async () => {
    const ctx = as('alice', { email: 'someone-else@example.com' });
    await assertFails(objectRef(ctx, 'alice', 'case-alice', 'a1').getMetadata());
  });

  it('denies an unverified email', async () => {
    const ctx = as('alice', { email_verified: false });
    await assertFails(objectRef(ctx, 'alice', 'case-alice', 'a1').getMetadata());
  });

  it('denies a foreign uid reading another owner path', async () => {
    await assertFails(objectRef(as('bob'), 'alice', 'case-alice', 'a1').getMetadata());
  });

  it('denies a foreign uid uploading into another owner path', async () => {
    await assertFails(objectRef(as('bob'), 'alice', 'case-alice', 'fresh').put(PNG, IMAGE));
  });

  it('denies an unauthenticated read', async () => {
    const ctx = env.unauthenticatedContext();
    await assertFails(objectRef(ctx, 'alice', 'case-alice', 'a1').getMetadata());
  });

  it('denies reading a tombstoned attachment even though the object exists', async () => {
    await assertFails(objectRef(as('alice'), 'alice', 'case-alice', 'tomb').getMetadata());
  });

  it('denies retrying an upload after the reservation was tombstoned', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await ctx.firestore().doc('cases/case-alice/attachments/fresh').update({
        deleteRequestedAt: new Date(),
      });
    });
    await assertFails(objectRef(as('alice'), 'alice', 'case-alice', 'fresh').put(PNG, IMAGE));
  });

  it('denies an upload with no reservation at all', async () => {
    await assertFails(objectRef(as('alice'), 'alice', 'case-alice', 'never').put(PNG, IMAGE));
  });

  it('denies a reservation whose ownerUid disagrees with the path', async () => {
    await assertFails(objectRef(as('alice'), 'alice', 'case-bob', 'mismatch').put(PNG, IMAGE));
  });

  it('denies a non-image content type', async () => {
    const ref = objectRef(as('alice'), 'alice', 'case-alice', 'fresh');
    await assertFails(ref.put(PNG, { contentType: 'text/plain' }));
  });

  it('denies bytes over the reservation ceiling', async () => {
    const big = new Uint8Array(64);
    big.set(PNG);
    await assertFails(objectRef(as('alice'), 'alice', 'case-alice', 'small').put(big, IMAGE));
  });

  it('denies overwriting an object that already exists', async () => {
    await assertFails(objectRef(as('alice'), 'alice', 'case-alice', 'a1').put(PNG, IMAGE));
  });

  it('denies a client delete', async () => {
    await assertFails(objectRef(as('alice'), 'alice', 'case-alice', 'a1').delete());
  });
});

// ---------------------------------------------------------------------------- P4

describe('P4 — admin liveness through the users/ mirror', () => {
  const admin = (uid) => as(uid, { admin: true });

  it('active mirror, no expiry, reads another owner attachment', async () => {
    await assertSucceeds(objectRef(admin('lars'), 'alice', 'case-alice', 'a1').getMetadata());
  });

  it('active mirror with a future adminActiveUntil reads', async () => {
    await assertSucceeds(objectRef(admin('larsuntil'), 'alice', 'case-alice', 'a1').getMetadata());
  });

  it('denies a passed adminActiveUntil', async () => {
    await assertFails(objectRef(admin('larspast'), 'alice', 'case-alice', 'a1').getMetadata());
  });

  it('denies an admin whose own account was revoked, mirror still true', async () => {
    await assertFails(objectRef(admin('larsrevoked'), 'alice', 'case-alice', 'a1').getMetadata());
  });

  it('denies adminActive == false', async () => {
    await assertFails(objectRef(admin('larsoff'), 'alice', 'case-alice', 'a1').getMetadata());
  });

  it('denies a missing adminActive field — an incomplete mirror fails closed', async () => {
    await assertFails(objectRef(admin('larsnomirror'), 'alice', 'case-alice', 'a1').getMetadata());
  });

  it('denies an active mirror without the admin claim', async () => {
    await assertFails(objectRef(as('lars'), 'alice', 'case-alice', 'a1').getMetadata());
  });

  it('denies the admin claim without a mirror', async () => {
    await assertFails(objectRef(admin('bob'), 'alice', 'case-alice', 'a1').getMetadata());
  });

  it('denies an admin token whose email disagrees with the users/ document', async () => {
    const ctx = as('lars', { admin: true, email: 'someone-else@example.com' });
    await assertFails(objectRef(ctx, 'alice', 'case-alice', 'a1').getMetadata());
  });

  it('denies an admin reading a tombstoned attachment', async () => {
    await assertFails(objectRef(admin('lars'), 'alice', 'case-alice', 'tomb').getMetadata());
  });

  it('denies an admin create', async () => {
    await assertFails(objectRef(admin('lars'), 'alice', 'case-alice', 'fresh').put(PNG, IMAGE));
  });

  it('denies an admin overwrite', async () => {
    await assertFails(objectRef(admin('lars'), 'alice', 'case-alice', 'a1').put(PNG, IMAGE));
  });

  it('denies an admin delete', async () => {
    await assertFails(objectRef(admin('lars'), 'alice', 'case-alice', 'a1').delete());
  });
});

// ---------------------------------------------------------------------------- catch-all

describe('catch-all', () => {
  it('denies any path outside the contract', async () => {
    const ref = as('alice').storage(BUCKET).ref('somewhere-else/alice/x.png');
    await assertFails(ref.put(PNG, IMAGE));
  });
});
