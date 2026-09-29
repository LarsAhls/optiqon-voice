// S4: the server's configuration interface and entrypoints. No emulator writes here beyond what
// the entrypoint tests inject; the project lock and the dry-run default are what is pinned.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { TARGET_PROJECT as BOOTSTRAP_TARGET } from '../../scripts/admin/m1-bootstrap.mjs';
import { ConfigError, loadConfig, MIN_TTL_DAYS, TARGET_PROJECT } from '../../server/config.mjs';
import { cli, onWithdrawalCreated } from '../../server/main.mjs';

describe('server config', () => {
  test('the project lock is the one the M1 bootstrap uses', () => {
    assert.equal(TARGET_PROJECT, BOOTSTRAP_TARGET);
  });

  test('only the target project is accepted outside the emulator', () => {
    assert.throws(() => loadConfig({}), ConfigError);
    assert.throws(() => loadConfig({ PROJECT_ID: 'optioqon-voice' }), /refusing/);
    assert.throws(() => loadConfig({ GOOGLE_CLOUD_PROJECT: 'other' }), /refusing/);
    assert.equal(loadConfig({ PROJECT_ID: TARGET_PROJECT }).projectId, TARGET_PROJECT);
    const emu = loadConfig({ PROJECT_ID: 'optiqon-voice-rules-test', FIRESTORE_EMULATOR_HOST: '127.0.0.1:8080' });
    assert.equal(emu.emulator, true);
  });

  test('writes need APPLY exactly true', () => {
    for (const APPLY of [undefined, '', '1', 'TRUE', 'yes']) {
      assert.equal(loadConfig({ PROJECT_ID: TARGET_PROJECT, APPLY }).apply, false, String(APPLY));
    }
    assert.equal(loadConfig({ PROJECT_ID: TARGET_PROJECT, APPLY: 'true' }).apply, true);
  });

  test('the sweep TTL defaults to 30 days and cannot go lower', () => {
    assert.equal(loadConfig({ PROJECT_ID: TARGET_PROJECT }).ttlDays, MIN_TTL_DAYS);
    assert.equal(loadConfig({ PROJECT_ID: TARGET_PROJECT, SWEEP_TTL_DAYS: '45' }).ttlDays, 45);
    for (const t of ['29', '0', '-30', '30.5', 'x']) {
      assert.throws(() => loadConfig({ PROJECT_ID: TARGET_PROJECT, SWEEP_TTL_DAYS: t }), /SWEEP_TTL_DAYS/, t);
    }
  });
});

describe('entrypoints', () => {
  const env = { PROJECT_ID: TARGET_PROJECT };
  const noDb = { db: new Proxy({}, { get() { throw new Error('no database may be touched'); } }), FieldValue: {} };

  test('the trigger ignores any document that is not a withdrawal intent', async () => {
    for (const subject of ['documents/cases/c1', 'documents/users/alice', 'documents/users/a/withdrawals/x/y']) {
      assert.deepEqual(await onWithdrawalCreated({ subject }, { env, deps: noDb }), { ignored: true });
    }
  });

  test('the trigger and the CLI refuse a foreign project before touching anything', async () => {
    await assert.rejects(onWithdrawalCreated({ subject: 'documents/users/a/withdrawals/c1' },
      { env: { PROJECT_ID: 'other' }, deps: noDb }), /refusing/);
    await assert.rejects(cli(['sweep'], { env: { PROJECT_ID: 'other' }, deps: noDb }), /refusing/);
  });

  test('the CLI knows two commands and one flag', async () => {
    await assert.rejects(cli([], { env, deps: noDb }), /usage/);
    await assert.rejects(cli(['delete'], { env, deps: noDb }), /usage/);
    await assert.rejects(cli(['sweep', '--force'], { env, deps: noDb }), /usage/);
  });
});
