// Entrypoints of the Feedback server (S4 withdrawal reconciliation, FS-S468 purge, retention and
// notification). Nothing here is deployed; FS-G decides how the provider calls it
// (deploy/fs-g/ holds the intended trigger and job definitions). Every entrypoint goes through
// loadConfig, so the project lock, the bucket lock and the dry-run default hold however it is
// invoked.
//
//   node server/main.mjs reconcile [--apply]   every withdrawal intent still without an outcome
//   node server/main.mjs sweep [--apply]       `submitted` leftovers older than the TTL
//   node server/main.mjs purge [--apply]       M5 backstop: every tombstone not yet purged for good
//   node server/main.mjs retention [--apply]   retention deadlines: screenshots, then closed cases
//
// Support operations and account deletion are not server entrypoints: an administrator runs
// them through scripts/admin/voice-admin.mjs.

import { fileURLToPath } from 'node:url';
import { loadConfig } from './config.mjs';
import { notifyForEvent } from './notify.mjs';
import { purgeAttachment, sweepPurges } from './purge.mjs';
import { sweepRetention } from './retention.mjs';
import { intentPath, reconcilePending, reconcileWithdrawal, sweepSubmitted } from './withdrawal.mjs';

const ID = '[A-Za-z0-9_-]{1,128}';
const ATTACHMENT_DOC = new RegExp(`^cases/(${ID})/attachments/(${ID})$`);
const EVENT_DOC = new RegExp(`^cases/(${ID})/events/(${ID})$`);

const docPath = (event) => String(event?.subject ?? event?.document ?? '').replace(/^.*?documents\//, '');

async function adminDeps(config) {
  const { initializeApp, getApps } = await import('firebase-admin/app');
  const { getFirestore, FieldValue } = await import('firebase-admin/firestore');
  const app = getApps().find((a) => a.name === 'feedback-server')
    ?? initializeApp({ projectId: config.projectId, storageBucket: config.bucket }, 'feedback-server');
  const deps = { db: getFirestore(app), FieldValue };
  // Storage and Messaging are loaded only by the entrypoints that need them.
  deps.load = async (what) => {
    if (what === 'storage' && !deps.storage) {
      const { getStorage } = await import('firebase-admin/storage');
      const { bucketStorage } = await import('./storage.mjs');
      deps.storage = bucketStorage(getStorage(app).bucket(config.bucket));
    }
    if (what === 'messenger' && !deps.messenger) {
      const { getMessaging } = await import('firebase-admin/messaging');
      const { fcmMessenger } = await import('./notify.mjs');
      deps.messenger = fcmMessenger(getMessaging(app));
    }
    return deps;
  };
  return deps;
}

async function depsFor(config, injected, ...needs) {
  if (injected) return injected;
  const d = await adminDeps(config);
  for (const n of needs) await d.load(n);
  return d;
}

/**
 * Withdrawal trigger: a CloudEvent for a created document. Only
 * `users/{uid}/withdrawals/{targetId}` is acted on; anything else is ignored.
 */
export async function onWithdrawalCreated(event, { env = process.env, deps } = {}) {
  const config = loadConfig(env);
  const where = intentPath(docPath(event));
  if (!where) return { ignored: true };
  const d = await depsFor(config, deps);
  return reconcileWithdrawal({ ...d, apply: config.apply }, where.uid, where.targetId);
}

/**
 * M5 primary path: an attachment document was written. The event's payload is not trusted for
 * the decision; purgeAttachment re-reads the document and does nothing without a tombstone.
 */
export async function onAttachmentWritten(event, { env = process.env, deps, now } = {}) {
  const config = loadConfig(env);
  const m = ATTACHMENT_DOC.exec(docPath(event));
  if (!m) return { ignored: true };
  const d = await depsFor(config, deps, 'storage');
  return purgeAttachment({ ...d, now, apply: config.apply }, m[1], m[2]);
}

/** S6: an event was created in a case. notifyForEvent decides; most events notify nobody. */
export async function onCaseEventCreated(event, { env = process.env, deps } = {}) {
  const config = loadConfig(env);
  const m = EVENT_DOC.exec(docPath(event));
  if (!m) return { ignored: true };
  const d = await depsFor(config, deps, 'messenger');
  return notifyForEvent({ ...d, apply: config.apply }, m[1], m[2]);
}

/**
 * The scheduled job. Each part runs even when an earlier one failed, so a broken reconcile
 * cannot stop the purge backstop that the 24 h promise depends on.
 */
export async function scheduledRun({ env = process.env, deps, now = new Date() } = {}) {
  const config = loadConfig(env);
  const d = await depsFor(config, deps, 'storage');
  const nowMs = now.getTime();
  const parts = {
    reconciled: () => reconcilePending({ ...d, apply: config.apply }),
    swept: () => sweepSubmitted({ ...d, now, ttlDays: config.ttlDays, apply: config.apply }),
    purged: () => sweepPurges({ ...d, now: nowMs, apply: config.apply }),
    retention: () => sweepRetention({ ...d, now: nowMs, apply: config.apply }),
  };
  const result = {};
  for (const [name, fn] of Object.entries(parts)) {
    try {
      result[name] = await fn();
    } catch (e) {
      result[name] = { error: String(e?.message ?? e) };
    }
  }
  return result;
}

const COMMANDS = ['reconcile', 'sweep', 'purge', 'retention'];

export async function cli(argv, { env = process.env, deps, log = console.log, now = new Date() } = {}) {
  const [command, ...flags] = argv;
  const unknown = flags.filter((f) => f !== '--apply');
  if (!COMMANDS.includes(command) || unknown.length) {
    throw new Error(`usage: node server/main.mjs ${COMMANDS.join('|')} [--apply]`);
  }
  const config = loadConfig({ ...env, APPLY: flags.includes('--apply') ? 'true' : 'false' });
  const needsStorage = command === 'purge' || command === 'retention';
  const d = await depsFor(config, deps, ...(needsStorage ? ['storage'] : []));
  const nowMs = now.getTime();
  const run = {
    reconcile: () => reconcilePending({ ...d, apply: config.apply }),
    sweep: () => sweepSubmitted({ ...d, now, ttlDays: config.ttlDays, apply: config.apply }),
    purge: () => sweepPurges({ ...d, now: nowMs, apply: config.apply }),
    retention: () => sweepRetention({ ...d, now: nowMs, apply: config.apply }),
  }[command];
  const result = await run();
  log(JSON.stringify({ command, apply: config.apply, projectId: config.projectId, result }, null, 2));
  return result;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  cli(process.argv.slice(2)).catch((e) => {
    console.error(e.message);
    process.exitCode = 1;
  });
}
