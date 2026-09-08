// Shared setup for the Firestore rules suite. Every file gets its own test environment
// because `node --test` runs files in separate processes; they all talk to the single
// emulator that `firebase emulators:exec` starts around the whole run.
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment } from '@firebase/rules-unit-testing';
import { doc, setDoc, Timestamp } from 'firebase/firestore';

export { assertFails, assertSucceeds } from '@firebase/rules-unit-testing';

export const PROJECT_ID = 'optiqon-voice-rules-test';

export async function makeEnv() {
  return initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules: readFileSync('firestore.rules', 'utf8'),
      host: '127.0.0.1',
      port: 8080,
    },
  });
}

/** An authenticated context whose token email matches the seeded users/ document. */
export function as(env, uid, overrides = {}) {
  return env.authenticatedContext(uid, {
    email: `${uid}@example.com`,
    email_verified: true,
    ...overrides,
  });
}

export const MAX_APPROVED = 10;

/**
 * The baseline every test starts from: one writer, one reader-admin, two approved
 * testers, one pending applicant, and a case owned by bob so that unconstrained queries
 * have something to be denied over.
 */
export async function seed(env) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    const now = Timestamp.now();

    await setDoc(doc(db, 'config/limits'), {
      maxApprovedUsers: MAX_APPROVED,
      uploadsEnabled: true,
    });
    await setDoc(doc(db, 'config/counters'), { approvedUsers: 4 });

    for (const uid of ['alice', 'bob', 'lars', 'reader']) {
      await setDoc(doc(db, 'users', uid), {
        uid,
        email: `${uid}@example.com`,
        displayName: uid,
        status: 'approved',
        createdAt: now,
      });
      await setDoc(doc(db, 'users', uid, 'quota', 'attachments'), {
        count: 0,
        bytes: 0,
        windowStart: now,
        windowCount: 0,
        lastUploadId: '',
      });
    }

    await setDoc(doc(db, 'users/pat'), {
      uid: 'pat',
      email: 'pat@example.com',
      displayName: 'pat',
      status: 'pending',
      createdAt: now,
    });

    await setDoc(doc(db, 'admins/lars'), { role: 'writer', grantedAt: now });
    await setDoc(doc(db, 'admins/reader'), { role: 'reader', grantedAt: now });

    await setDoc(doc(db, 'cases/case-alice'), {
      ownerUid: 'alice',
      title: 'Alice case',
      body: '',
      statusCache: 'Mottaget',
      lastStatusEventId: null,
      attachmentCount: 0,
      createdAt: now,
      updatedAt: now,
    });
    await setDoc(doc(db, 'cases/case-bob'), {
      ownerUid: 'bob',
      title: 'Bob case',
      body: '',
      statusCache: 'Mottaget',
      lastStatusEventId: null,
      attachmentCount: 0,
      createdAt: now,
      updatedAt: now,
    });
  });
}
