// FS-S468: the Cloud Run adapter routes by path and CloudEvent type, reads only the subject, and
// turns a failing entrypoint into a retryable 500. No Firebase: the entrypoints are fakes.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { handle, ROUTES, serve } from '../../server/http.mjs';
import * as main from '../../server/main.mjs';

const CREATED = 'google.cloud.firestore.document.v1.created';
const WRITTEN = 'google.cloud.firestore.document.v1.written';

function fakes() {
  const calls = [];
  const rec = (name) => async (event) => {
    calls.push([name, event]);
    return { ok: name };
  };
  return {
    calls,
    onWithdrawalCreated: rec('onWithdrawalCreated'),
    onAttachmentWritten: rec('onAttachmentWritten'),
    onCaseEventCreated: rec('onCaseEventCreated'),
    scheduledRun: rec('scheduledRun'),
  };
}

const post = (path, type, subject) => ({
  method: 'POST',
  path,
  headers: { ...(type ? { 'ce-type': type } : {}), ...(subject ? { 'ce-subject': subject } : {}) },
});

describe('http adapter', () => {
  test('every route names an entrypoint that server/main.mjs really exports', () => {
    for (const { entry } of Object.values(ROUTES)) assert.equal(typeof main[entry], 'function', entry);
  });

  test('each event route hands only the subject to its entrypoint', async () => {
    const f = fakes();
    const cases = [
      ['/events/withdrawal', CREATED, 'documents/users/u1/withdrawals/m1', 'onWithdrawalCreated'],
      ['/events/attachment', WRITTEN, 'documents/cases/c1/attachments/a1', 'onAttachmentWritten'],
      ['/events/case-event', CREATED, 'documents/cases/c1/events/e1', 'onCaseEventCreated'],
    ];
    for (const [path, type, subject, entry] of cases) {
      const r = await handle(post(path, type, subject), f);
      assert.equal(r.status, 200, path);
      assert.deepEqual(f.calls.at(-1), [entry, { subject }]);
    }
  });

  test('the scheduled route runs the job and needs no event headers', async () => {
    const f = fakes();
    const r = await handle({ method: 'POST', path: '/jobs/scheduled', headers: {} }, f);
    assert.equal(r.status, 200);
    assert.deepEqual(f.calls.map((c) => c[0]), ['scheduledRun']);
  });

  test('a misrouted event type is refused before any entrypoint runs', async () => {
    const f = fakes();
    // An attachment write delivered to the withdrawal route, and a created event on the write route.
    assert.equal((await handle(post('/events/withdrawal', WRITTEN, 'documents/users/u/withdrawals/m'), f)).status, 400);
    assert.equal((await handle(post('/events/attachment', 'google.cloud.firestore.document.v1.deleted', 'documents/cases/c/attachments/a'), f)).status, 400);
    assert.equal((await handle(post('/events/case-event', CREATED, undefined), f)).status, 400);
    assert.deepEqual(f.calls, []);
  });

  test('unknown paths and other methods are refused', async () => {
    const f = fakes();
    assert.equal((await handle({ method: 'POST', path: '/admin/delete-account', headers: {} }, f)).status, 404);
    assert.equal((await handle({ method: 'GET', path: '/jobs/scheduled', headers: {} }, f)).status, 405);
    assert.deepEqual(f.calls, []);
  });

  test('a failing entrypoint is a 500, so the provider retries the idempotent handler', async () => {
    const f = fakes();
    f.onAttachmentWritten = async () => { throw new Error('storage unavailable'); };
    const r = await handle(post('/events/attachment', WRITTEN, 'documents/cases/c/attachments/a'), f);
    assert.equal(r.status, 500);
    assert.equal(r.body.error, 'storage unavailable');
  });

  test('the real entrypoints refuse a foreign project through the adapter', async () => {
    const saved = { ...process.env };
    try {
      delete process.env.FIRESTORE_EMULATOR_HOST;
      process.env.PROJECT_ID = 'optioqon-voice';
      const r = await handle(post('/events/attachment', WRITTEN, 'documents/cases/c/attachments/a'), main);
      assert.equal(r.status, 500);
      assert.match(r.body.error, /refusing project/);
    } finally {
      process.env = saved;
    }
  });

  test('the listener serves the same decisions over HTTP', async () => {
    const f = fakes();
    const server = await serve({ port: 0, entrypoints: f });
    try {
      const { port } = server.address();
      const res = await fetch(`http://127.0.0.1:${port}/events/case-event`, {
        method: 'POST',
        headers: { 'ce-type': CREATED, 'ce-subject': 'documents/cases/c1/events/e1' },
        body: 'ignored protobuf bytes',
      });
      assert.equal(res.status, 200);
      assert.deepEqual(f.calls, [['onCaseEventCreated', { subject: 'documents/cases/c1/events/e1' }]]);
    } finally {
      server.close();
    }
  });
});
