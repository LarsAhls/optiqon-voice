// The live credential branch of the bootstrap tool — the one path the emulator suite cannot
// reach, because `FIRESTORE_EMULATOR_HOST` short-circuits credentials before it is ever run.
//
// That gap shipped a real defect: `firebase-admin`'s Firestore accepts only a certificate
// credential or ADC, so `getFirestore()` on an OAuth-refresh-token app threw
// `firestore/invalid-credential` on every live invocation while all 22 emulator tests stayed
// green. These tests exercise the branch with controlled, fake credentials and make no
// network call and no write: nothing here contacts Google, and the refresh token is a
// literal string that could not authenticate anything if it did.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { initializeApp, deleteApp, refreshToken } from 'firebase-admin/app';
import { getFirestore, FieldValue } from 'firebase-admin/firestore';
import { Firestore, FieldValue as GcpFieldValue } from '@google-cloud/firestore';
import { UserRefreshClient } from 'google-auth-library';
import { liveFirestore, TARGET_PROJECT } from '../../scripts/admin/m1-bootstrap.mjs';

/** Shaped like cliCredential()'s return value. Deliberately not a real token. */
const FAKE = Object.freeze({
  email: 'lars@optiqon.se',
  refreshToken: 'not-a-real-refresh-token',
  clientId: 'test-client-id.apps.googleusercontent.com',
  clientSecret: 'test-client-secret',
});

/** Runs `fn` with the emulator env removed, so the live path is genuinely taken. */
async function withoutEmulator(fn) {
  const saved = process.env.FIRESTORE_EMULATOR_HOST;
  delete process.env.FIRESTORE_EMULATOR_HOST;
  try {
    return await fn();
  } finally {
    if (saved !== undefined) process.env.FIRESTORE_EMULATOR_HOST = saved;
  }
}

test('the live branch builds a Firestore bound to the target project and the CLI refresh token', async () => {
  await withoutEmulator(async () => {
    const db = await liveFirestore(FAKE, TARGET_PROJECT);
    assert.ok(db instanceof Firestore, 'must be a real @google-cloud/firestore client');
    assert.equal(db.projectId, TARGET_PROJECT, 'project lock must survive the client swap');

    const client = db._settings.authClient;
    assert.ok(client instanceof UserRefreshClient, 'must authenticate as the logged-in CLI user');
    assert.equal(client._refreshToken, FAKE.refreshToken, 'must reuse the existing credential');
    assert.equal(client._clientId, FAKE.clientId);
  });
});

test('no new credential material is invented: the client carries only what cliCredential supplied', async () => {
  await withoutEmulator(async () => {
    const db = await liveFirestore(FAKE, TARGET_PROJECT);
    const s = db._settings;
    assert.equal(s.keyFilename, undefined, 'no key file');
    assert.equal(s.credentials, undefined, 'no service account');
    assert.equal(s.authClient._clientSecret, FAKE.clientSecret, 'the CLI secret, nothing else');
  });
});

test('the client is usable as a drop-in: FieldValue sentinels are the same class', () => {
  // One hoisted @google-cloud/firestore instance, so run()'s deps.FieldValue still applies to
  // documents written through the swapped client. If a future install nests a second copy,
  // this fails here rather than as a silent write corruption in a live Gate run.
  assert.equal(FieldValue, GcpFieldValue);
});

test('regression guard: firebase-admin still refuses the refresh-token credential for Firestore', async () => {
  // The reason liveFirestore() exists. If a future firebase-admin makes this work, this test
  // fails and the workaround can be reconsidered deliberately — not reverted by accident.
  await withoutEmulator(async () => {
    const app = initializeApp(
      {
        credential: refreshToken({
          type: 'authorized_user',
          client_id: FAKE.clientId,
          client_secret: FAKE.clientSecret,
          refresh_token: FAKE.refreshToken,
        }),
        projectId: TARGET_PROJECT,
      },
      'live-credential-regression'
    );
    try {
      assert.throws(() => getFirestore(app), (e) => {
        assert.match(String(e.message), /certificate credential or application default/i);
        return true;
      });
    } finally {
      await deleteApp(app);
    }
  });
});
