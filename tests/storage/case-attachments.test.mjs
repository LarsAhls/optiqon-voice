// FS-1: `storage.rules`, the file `firebase.json` releases to the `feedback` target.
//
// Rows N1-N21 and P1/P2 are the negative matrix and the two positives from the FS-1 plan.
// The Firestore side is `firestore.rules` too, so the attachment and users/ documents the
// Storage rules read are the ones the app will really write -- seeded here past the rules,
// because what is under test is the byte path, not the metadata path (fs1-cases.test.mjs).
//
// What this suite cannot prove, and does not pretend to:
//   * The two-document ceiling. The emulator enforces none; the PR-0 spike measured three and
//     five documents passing here. The count is pinned as a property of the text instead, in
//     tests/deploy/storage-rules-text.test.mjs, and only a live probe settles it (P2).
//   * When a real resumable upload is evaluated, and whether the real service mints a
//     download token on upload or honours a client-chosen one (P3, P5, P6). The emulator's
//     behaviour is recorded below as a measurement of the emulator, nothing more.
import { readFileSync } from 'node:fs';
import { after, before, beforeEach, describe, test } from 'node:test';
import assert from 'node:assert/strict';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';

const PROJECT_ID = 'optiqon-voice-rules-test';
const BUCKET = `gs://${PROJECT_ID}.appspot.com`;
const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const MiB2 = 2 * 1024 * 1024;
const HOUR = 60 * 60 * 1000;

let env;

function as(uid, overrides = {}) {
  return env.authenticatedContext(uid, {
    email: `${uid}@example.com`,
    email_verified: true,
    ...overrides,
  });
}
const admin = (uid, overrides = {}) => as(uid, { admin: true, ...overrides });

function obj(ctx, ownerUid, caseId, aid) {
  return ctx.storage(BUCKET).ref(`case-attachments/${ownerUid}/${caseId}/${aid}`);
}
const png = (ref, bytes = PNG, meta = {}) => ref.put(bytes, { contentType: 'image/png', ...meta });

before(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { rules: readFileSync('firestore.rules', 'utf8'), host: '127.0.0.1', port: 8080 },
    storage: { rules: readFileSync('storage.rules', 'utf8'), host: '127.0.0.1', port: 9199 },
  });
});
after(async () => { await env.cleanup(); });

beforeEach(async () => {
  await env.clearFirestore();
  // Not env.clearStorage(): that lists the DEFAULT bucket's top level only, so every nested
  // object here would survive into the next test and turn `resource == null` into a false deny.
  await env.withSecurityRulesDisabled(async (ctx) => {
    const wipe = async (ref) => {
      const { items, prefixes } = await ref.listAll();
      await Promise.all(items.map((i) => i.delete()));
      for (const p of prefixes) await wipe(p);
    };
    await wipe(ctx.storage(BUCKET).ref());
  });
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    const now = new Date();
    const users = {
      alice: { status: 'approved' },
      bob: { status: 'approved' },
      rev: { status: 'revoked' },
      pat: { status: 'pending' },
      rej: { status: 'rejected' },
      // The users/ mirror of admins/{uid}; Storage never reads admins/ itself.
      lars: { status: 'approved', adminActive: true },
      larsuntil: { status: 'approved', adminActive: true, adminActiveUntil: new Date(Date.now() + HOUR) },
      larspast: { status: 'approved', adminActive: true, adminActiveUntil: new Date(Date.now() - HOUR) },
      larsoff: { status: 'approved', adminActive: false },
      larsnomirror: { status: 'approved' },
    };
    for (const [uid, extra] of Object.entries(users)) {
      await db.doc(`users/${uid}`).set({
        uid, email: `${uid}@example.com`, displayName: uid, createdAt: now, ...extra,
      });
    }

    // Attachment documents in the FS-1 shape: no fingerprint, the reservation's ceiling copied in.
    const att = (caseId, aid, extra = {}) => db.doc(`cases/${caseId}/attachments/${aid}`).set({
      ownerUid: 'alice', caseId, messageId: null, maxBytes: MiB2, createdAt: now, ...extra,
    });
    await att('case-alice', 'a1');                                   // has bytes
    await att('case-alice', 'fresh');                                // awaiting bytes
    await att('case-alice', 'fresh2');
    await att('case-alice', 'small', { maxBytes: 4 });
    await att('case-alice', 'huge', { maxBytes: 3 * 1024 * 1024 });  // ceiling above the cap
    await att('case-alice', 'tomb', { deleteRequestedAt: now });     // has bytes, taken down
    await att('case-alice', 'lateTomb', { deleteRequestedAt: now }); // taken down before upload
    await att('case-alice', 'stale', { createdAt: new Date(Date.now() - 73 * HOUR) });
    await att('case-alice', 'wrongCase', { caseId: 'case-other' });
    await att('case-alice', 'wrongOwner', { ownerUid: 'bob' });
    await att('case-alice', 'noBytesDoc');                           // for the missing-object read
    // Bytes whose attachment document does not exist, and an owner with no users/ document.
    await att('case-ghost', 'g1', { ownerUid: 'ghost' });
    // Lars's own screenshot, for the branch-order row.
    await att('case-lars', 'l1', { ownerUid: 'larsnomirror' });

    const storage = ctx.storage(BUCKET);
    for (const path of [
      'case-attachments/alice/case-alice/a1',
      'case-attachments/alice/case-alice/tomb',
      'case-attachments/alice/case-alice/orphan',
      'case-attachments/ghost/case-ghost/g1',
      'case-attachments/larsnomirror/case-lars/l1',
      'elsewhere/alice/x.png',
    ]) {
      await storage.ref(path).put(PNG, { contentType: 'image/png' });
    }
  });
});

// ----------------------------------------------------------------- positives

describe('P1/P2 — the paths that must work', () => {
  test('P1 an approved owner uploads png, jpeg and webp against its attachment', async () => {
    for (const [aid, type] of [['fresh', 'image/png'], ['fresh2', 'image/jpeg'], ['small', 'image/webp']]) {
      await assertSucceeds(obj(as('alice'), 'alice', 'case-alice', aid).put(PNG.slice(0, 4), {
        contentType: type,
      }));
    }
  });

  test('P1 an approved owner reads its own screenshot', async () => {
    await assertSucceeds(obj(as('alice'), 'alice', 'case-alice', 'a1').getMetadata());
  });

  test('P2 an admin with claim and live mirror reads another owner\'s screenshot', async () => {
    await assertSucceeds(obj(admin('lars'), 'alice', 'case-alice', 'a1').getMetadata());
    await assertSucceeds(obj(admin('larsuntil'), 'alice', 'case-alice', 'a1').getMetadata());
  });

  test('exactly 2 MiB is accepted when the attachment allows it', async () => {
    await assertSucceeds(png(obj(as('alice'), 'alice', 'case-alice', 'fresh'), new Uint8Array(MiB2)));
  });
});

// ----------------------------------------------------------------- negatives

describe('N1-N21 — everything else is refused', () => {
  test('N1 unauthenticated get and create', async () => {
    const ctx = env.unauthenticatedContext();
    await assertFails(obj(ctx, 'alice', 'case-alice', 'a1').getMetadata());
    await assertFails(png(obj(ctx, 'alice', 'case-alice', 'fresh')));
  });

  test('N2 another approved account on the owner\'s path', async () => {
    await assertFails(obj(as('bob'), 'alice', 'case-alice', 'a1').getMetadata());
    await assertFails(png(obj(as('bob'), 'alice', 'case-alice', 'fresh')));
  });

  test('N3 a revoked owner, with the attachment already issued', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await ctx.firestore().doc('users/alice').update({ status: 'revoked' });
    });
    await assertFails(obj(as('alice'), 'alice', 'case-alice', 'a1').getMetadata());
    await assertFails(png(obj(as('alice'), 'alice', 'case-alice', 'fresh')));
  });

  test('N4 pending and rejected accounts', async () => {
    for (const uid of ['pat', 'rej', 'rev']) {
      await assertFails(obj(as(uid), uid, 'case-alice', 'a1').getMetadata());
    }
  });

  test('N4 an unverified or mismatched token email', async () => {
    await assertFails(obj(as('alice', { email_verified: false }), 'alice', 'case-alice', 'a1').getMetadata());
    await assertFails(obj(as('alice', { email: 'x@example.com' }), 'alice', 'case-alice', 'a1').getMetadata());
  });

  test('N5 the admin claim without a mirror', async () => {
    await assertFails(obj(admin('larsnomirror'), 'alice', 'case-alice', 'a1').getMetadata());
    await assertFails(obj(admin('bob'), 'alice', 'case-alice', 'a1').getMetadata());
  });

  test('N6 the mirror without the claim, or switched off', async () => {
    await assertFails(obj(as('lars'), 'alice', 'case-alice', 'a1').getMetadata());
    await assertFails(obj(admin('larsoff'), 'alice', 'case-alice', 'a1').getMetadata());
  });

  test('N7 an admin whose adminActiveUntil has passed, or whose account is revoked', async () => {
    await assertFails(obj(admin('larspast'), 'alice', 'case-alice', 'a1').getMetadata());
    await env.withSecurityRulesDisabled(async (ctx) => {
      await ctx.firestore().doc('users/lars').update({ status: 'revoked' });
    });
    await assertFails(obj(admin('lars'), 'alice', 'case-alice', 'a1').getMetadata());
  });

  test('N8 bytes without an attachment document, for owner and admin', async () => {
    await assertFails(obj(as('alice'), 'alice', 'case-alice', 'orphan').getMetadata());
    await assertFails(obj(admin('lars'), 'alice', 'case-alice', 'orphan').getMetadata());
    await assertFails(png(obj(as('alice'), 'alice', 'case-alice', 'never')));
  });

  test('N9 an owner with no users/ document', async () => {
    await assertFails(obj(as('ghost'), 'ghost', 'case-ghost', 'g1').getMetadata());
  });

  test('N10 a tombstoned screenshot is unreadable to owner and admin, bytes or not', async () => {
    await assertFails(obj(as('alice'), 'alice', 'case-alice', 'tomb').getMetadata());
    await assertFails(obj(admin('lars'), 'alice', 'case-alice', 'tomb').getMetadata());
  });

  test('N10 the flip is immediate: readable, tombstoned, unreadable', async () => {
    const ref = () => obj(as('alice'), 'alice', 'case-alice', 'a1');
    await assertSucceeds(ref().getMetadata());
    await env.withSecurityRulesDisabled(async (ctx) => {
      await ctx.firestore().doc('cases/case-alice/attachments/a1').update({ deleteRequestedAt: new Date() });
    });
    await assertFails(ref().getMetadata());
  });

  test('N11 a late upload after the tombstone, and after the bytes are gone', async () => {
    await assertFails(png(obj(as('alice'), 'alice', 'case-alice', 'lateTomb')));
    await env.withSecurityRulesDisabled(async (ctx) => {
      await ctx.storage(BUCKET).ref('case-attachments/alice/case-alice/tomb').delete();
    });
    await assertFails(png(obj(as('alice'), 'alice', 'case-alice', 'tomb')));
  });

  test('N12 content types outside png, jpeg and webp', async () => {
    const ref = obj(as('alice'), 'alice', 'case-alice', 'fresh');
    for (const contentType of ['image/gif', 'text/html', 'image/svg+xml', 'application/octet-stream']) {
      await assertFails(ref.put(PNG, { contentType }), contentType);
    }
  });

  test('N13 over the attachment\'s ceiling, over 2 MiB, and empty', async () => {
    const big = new Uint8Array(64);
    await assertFails(png(obj(as('alice'), 'alice', 'case-alice', 'small'), big));
    await assertFails(png(obj(as('alice'), 'alice', 'case-alice', 'huge'), new Uint8Array(MiB2 + 1)));
    await assertFails(png(obj(as('alice'), 'alice', 'case-alice', 'fresh'), new Uint8Array(0)));
  });

  test('N14 a client overwrite or metadata update', async () => {
    const ref = obj(as('alice'), 'alice', 'case-alice', 'a1');
    await assertFails(png(ref));
    await assertFails(ref.updateMetadata({ contentType: 'image/jpeg' }));
    await assertFails(ref.updateMetadata({ customMetadata: { firebaseStorageDownloadTokens: 'x' } }));
    await assertFails(obj(admin('lars'), 'alice', 'case-alice', 'a1').updateMetadata({ contentType: 'image/jpeg' }));
  });

  test('N15 a client delete, owner or admin', async () => {
    await assertFails(obj(as('alice'), 'alice', 'case-alice', 'a1').delete());
    await assertFails(obj(admin('lars'), 'alice', 'case-alice', 'a1').delete());
  });

  test('N16 path and attachment disagree', async () => {
    // Wrong owner segment for the caller.
    await assertFails(png(obj(as('alice'), 'bob', 'case-alice', 'fresh')));
    // The attachment names another case, or another owner.
    await assertFails(png(obj(as('alice'), 'alice', 'case-alice', 'wrongCase')));
    await assertFails(png(obj(as('alice'), 'alice', 'case-alice', 'wrongOwner')));
    // The right attachment under the wrong case segment is a different document: none.
    await assertFails(png(obj(as('alice'), 'alice', 'case-bob', 'fresh')));
  });

  test('N17 an admin-claim account with no mirror still reads its OWN screenshot', async () => {
    // Allowed through the owner branch. If the admin branch were evaluated first and its
    // failure were fatal, this would deny: the row pins that the branches are independent.
    await assertSucceeds(obj(admin('larsnomirror'), 'larsnomirror', 'case-lars', 'l1').getMetadata());
  });

  test('N18 an upload outside the 72-hour window', async () => {
    await assertFails(png(obj(as('alice'), 'alice', 'case-alice', 'stale')));
  });

  test('N19 client-chosen custom metadata is refused', async () => {
    const ref = obj(as('alice'), 'alice', 'case-alice', 'fresh');
    await assertFails(png(ref, PNG, { customMetadata: { note: 'x' } }));
    await assertFails(png(ref, PNG, { customMetadata: { a: '1', b: '2' } }));
    // Standard metadata is not custom metadata.
    await assertSucceeds(png(ref, PNG, { cacheControl: 'private, max-age=0' }));
  });

  test('N20 listing a prefix', async () => {
    const root = (ctx, p) => ctx.storage(BUCKET).ref(p);
    await assertFails(root(as('alice'), 'case-attachments/alice/case-alice').listAll());
    await assertFails(root(admin('lars'), 'case-attachments/alice/case-alice').listAll());
    await assertFails(root(as('alice'), 'case-attachments').listAll());
  });

  test('N21 any other path, and the root', async () => {
    await assertFails(as('alice').storage(BUCKET).ref('elsewhere/alice/x.png').getMetadata());
    await assertFails(png(as('alice').storage(BUCKET).ref('elsewhere/alice/y.png')));
    await assertFails(png(as('alice').storage(BUCKET).ref('root.png')));
    await assertFails(png(as('alice').storage(BUCKET).ref('case-attachments/alice/fresh')));
    await assertFails(png(as('alice').storage(BUCKET).ref('case-attachments/alice/case-alice/fresh/deeper')));
  });
});

// ----------------------------------------------------------------- measurement

// Not contracts. The real service's behaviour is P5/P6, and only a live probe answers it. What
// these record is how the EMULATOR treats download tokens, so a later reader knows which way the
// local evidence pointed -- and why the N19 row above does not name the token key.
//
// Measured 2026-09-28, firebase-tools 13.35.1: the emulator removes the reserved key
// `firebaseStorageDownloadTokens` from custom metadata BEFORE rules evaluation, so
// `request.resource.metadata` is empty and the metadata ban never sees it; nor is the key stored.
// Whether production exposes the key to rules is UNKNOWN. The rules-level ban on a client-chosen
// token (plan 4.4b) is therefore unproven for that key; 4.4a (no getDownloadUrl in the client)
// and 4.4c (the server actor strips tokens on tombstone) carry it until P5/P6 are measured.
test('measurement: the emulator and a client-chosen download token', async () => {
  const ref = obj(as('alice'), 'alice', 'case-alice', 'fresh');
  let outcome;
  try {
    await png(ref, PNG, { customMetadata: { firebaseStorageDownloadTokens: 'chosen' } });
    outcome = 'ALLOWED';
  } catch (e) {
    outcome = `DENIED (${e.code})`;
  }
  let stored = 'n/a';
  if (outcome === 'ALLOWED') {
    await env.withSecurityRulesDisabled(async (ctx) => {
      const meta = await obj(ctx, 'alice', 'case-alice', 'fresh').getMetadata();
      stored = JSON.stringify({ downloadTokens: meta.downloadTokens ?? null, customMetadata: meta.customMetadata ?? null });
    });
  }
  console.log(`emulator, client-chosen firebaseStorageDownloadTokens: ${outcome}; stored as ${stored}`);
  assert.ok(true);
});

test('measurement: does the emulator attach a download token to a fresh upload?', async () => {
  const ref = obj(as('alice'), 'alice', 'case-alice', 'fresh');
  await assertSucceeds(png(ref));
  const meta = await ref.getMetadata();
  const tokens = meta.downloadTokens ?? meta.customMetadata?.firebaseStorageDownloadTokens;
  console.log(`emulator download token on upload: ${tokens ? 'PRESENT' : 'absent'}`);
  assert.ok(true);
});
