// The HTTP face of the Feedback server for Cloud Run (FS-S468). Nothing here is deployed; FS-G
// decides whether and how (deploy/fs-g/ holds the intended service, trigger and job
// definitions).
//
// Eventarc delivers a Firestore event to Cloud Run as a CloudEvent over HTTP. Only its
// `ce-subject` header (`documents/<path>`) and `ce-type` are read: every entrypoint re-reads the
// document itself and decides from what it finds, so the body -- protobuf in binary mode -- is
// never parsed and never trusted. Cloud Scheduler calls the job route with an empty body.
//
// Authentication is Cloud Run's, not this file's: the service is deployed without
// unauthenticated access and only the trigger and scheduler service accounts hold
// `roles/run.invoker` on it (docs/FS_G_PROVIDER_CONFIG.md).
//
//   POST /events/withdrawal   google.cloud.firestore.document.v1.created  users/{uid}/withdrawals/{id}
//   POST /events/attachment   google.cloud.firestore.document.v1.written  cases/{c}/attachments/{a}
//   POST /events/case-event   google.cloud.firestore.document.v1.created  cases/{c}/events/{e}
//   POST /jobs/scheduled      (Cloud Scheduler)                          reconcile, sweep, purge, retention

import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';

const CREATED = 'google.cloud.firestore.document.v1.created';
const WRITTEN = 'google.cloud.firestore.document.v1.written';

/** Route → the one event type it accepts and the entrypoint it calls. */
export const ROUTES = {
  '/events/withdrawal': { type: CREATED, entry: 'onWithdrawalCreated' },
  '/events/attachment': { type: WRITTEN, entry: 'onAttachmentWritten' },
  '/events/case-event': { type: CREATED, entry: 'onCaseEventCreated' },
  '/jobs/scheduled': { type: null, entry: 'scheduledRun' },
};

const header = (headers, name) => {
  const v = headers?.[name] ?? headers?.[name.toLowerCase()];
  return Array.isArray(v) ? v[0] : v;
};

/**
 * Decides one request. Returns `{ status, body }`; never throws for a bad request. A failing
 * entrypoint is a 500 so that Eventarc retries it -- every entrypoint is idempotent.
 *
 * `entrypoints` is the module server/main.mjs, injected so the routing can be tested without
 * Firebase.
 */
export async function handle({ method, path, headers }, entrypoints) {
  const route = ROUTES[String(path ?? '').split('?')[0]];
  if (!route) return { status: 404, body: { error: 'no such route' } };
  if (method !== 'POST') return { status: 405, body: { error: 'POST only' } };

  try {
    if (route.type === null) {
      return { status: 200, body: await entrypoints.scheduledRun() };
    }
    const type = header(headers, 'ce-type');
    if (type !== route.type) return { status: 400, body: { error: `expected ${route.type}` } };
    const subject = header(headers, 'ce-subject');
    if (!subject) return { status: 400, body: { error: 'no ce-subject' } };
    return { status: 200, body: await entrypoints[route.entry]({ subject }) };
  } catch (e) {
    // The message only: config and core errors name ids and paths, never tokens or contents.
    return { status: 500, body: { error: String(e?.message ?? e) } };
  }
}

/** Starts the listener Cloud Run expects on `PORT`. */
export async function serve({ port = Number(process.env.PORT ?? 8080), entrypoints } = {}) {
  const entries = entrypoints ?? await import('./main.mjs');
  const server = createServer((req, res) => {
    // The body is drained and dropped: nothing in it is used.
    req.resume();
    req.on('end', async () => {
      const { status, body } = await handle({ method: req.method, path: req.url, headers: req.headers }, entries);
      res.writeHead(status, { 'content-type': 'application/json' });
      res.end(JSON.stringify(body));
    });
  });
  await new Promise((resolve) => server.listen(port, resolve));
  return server;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  serve().catch((e) => {
    console.error(e.message);
    process.exitCode = 1;
  });
}
