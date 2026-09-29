// S4: the server's configuration interface and entrypoints. No emulator writes here beyond what
// the entrypoint tests inject; the project lock and the dry-run default are what is pinned.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { TARGET_PROJECT as BOOTSTRAP_TARGET } from '../../scripts/admin/m1-bootstrap.mjs';
import { ConfigError, loadConfig, MIN_TTL_DAYS, TARGET_BUCKET, TARGET_PROJECT } from '../../server/config.mjs';
import { cli, onAttachmentWritten, onCaseEventCreated, onWithdrawalCreated, scheduledRun } from '../../server/main.mjs';

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

  test('only the europe-north2 bucket is accepted outside the emulator', () => {
    assert.equal(TARGET_BUCKET, 'optiqon-voice-47498-eun2');
    assert.equal(loadConfig({ PROJECT_ID: TARGET_PROJECT }).bucket, TARGET_BUCKET);
    assert.throws(() => loadConfig({ PROJECT_ID: TARGET_PROJECT, BUCKET: 'optiqon-voice-47498.appspot.com' }), /refusing bucket/);
    assert.throws(() => loadConfig({ PROJECT_ID: TARGET_PROJECT, BUCKET: 'optiqon-voice-47498.firebasestorage.app' }), /refusing bucket/);
    const emu = loadConfig({ PROJECT_ID: 'x', FIRESTORE_EMULATOR_HOST: '127.0.0.1:8080', BUCKET: 'local' });
    assert.equal(emu.bucket, 'local');
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

  test('the CLI knows four commands and one flag', async () => {
    await assert.rejects(cli([], { env, deps: noDb }), /usage/);
    for (const bad of ['delete', 'delete-account', 'reply', 'notify']) {
      await assert.rejects(cli([bad], { env, deps: noDb }), /usage/, bad);
    }
    await assert.rejects(cli(['sweep', '--force'], { env, deps: noDb }), /usage/);
    await assert.rejects(cli(['purge', '--apply=true'], { env, deps: noDb }), /usage/);
  });

  test('the attachment and event triggers ignore every other document', async () => {
    for (const subject of ['documents/cases/c1', 'documents/cases/c1/events/e1', 'documents/cases/c 1/attachments/a',
      'documents/users/a/withdrawals/c1', 'documents/cases/c1/attachments/a1/x']) {
      assert.deepEqual(await onAttachmentWritten({ subject }, { env, deps: noDb }), { ignored: true }, subject);
    }
    for (const subject of ['documents/cases/c1', 'documents/cases/c1/attachments/a1', 'documents/cases/c1/events/e1/x',
      'documents/cases/../events/e1']) {
      assert.deepEqual(await onCaseEventCreated({ subject }, { env, deps: noDb }), { ignored: true }, subject);
    }
  });

  test('every trigger and the scheduled job refuse a foreign project before touching anything', async () => {
    const other = { PROJECT_ID: 'other' };
    await assert.rejects(onAttachmentWritten({ subject: 'documents/cases/c1/attachments/a1' }, { env: other, deps: noDb }), /refusing/);
    await assert.rejects(onCaseEventCreated({ subject: 'documents/cases/c1/events/e1' }, { env: other, deps: noDb }), /refusing/);
    await assert.rejects(scheduledRun({ env: other, deps: noDb }), /refusing/);
    await assert.rejects(cli(['purge'], { env: { ...env, BUCKET: 'elsewhere' }, deps: noDb }), /refusing bucket/);
  });

  test('the scheduled job runs every part even when one fails', async () => {
    const r = await scheduledRun({ env, deps: noDb });
    assert.deepEqual(Object.keys(r), ['reconciled', 'swept', 'purged', 'retention']);
    for (const part of Object.values(r)) assert.match(part.error, /no database may be touched/);
  });

  test('the triggers hand the ids from the document path to the core, dry run by default', async () => {
    const seen = [];
    const fakeDb = { doc: (path) => ({ get: async () => { seen.push(path); return { exists: false }; } }) };
    const d = { db: fakeDb, FieldValue: {}, storage: {}, messenger: {} };
    assert.deepEqual(await onAttachmentWritten({ subject: 'projects/p/databases/(default)/documents/cases/c1/attachments/a1' }, { env, deps: d }), { outcome: 'absent' });
    assert.deepEqual(await onCaseEventCreated({ subject: 'projects/p/databases/(default)/documents/cases/c2/events/e2' }, { env, deps: d }), { outcome: 'absent' });
    assert.deepEqual(seen, ['cases/c1/attachments/a1', 'cases/c2', 'cases/c2/events/e2']);
  });
});
