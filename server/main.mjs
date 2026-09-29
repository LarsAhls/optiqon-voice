// S4: entrypoints of the reconciliation server. Nothing here is deployed; FS-G decides how the
// provider calls it (an Eventarc trigger on withdrawal creation, a Scheduler job for the
// backstop and the sweep). Every entrypoint goes through loadConfig, so the project lock and
// the dry-run default hold however it is invoked.
//
//   node server/main.mjs reconcile [--apply]   every intent still without an outcome
//   node server/main.mjs sweep [--apply]       `submitted` leftovers older than the TTL

import { fileURLToPath } from 'node:url';
import { loadConfig } from './config.mjs';
import { intentPath, reconcilePending, reconcileWithdrawal, sweepSubmitted } from './withdrawal.mjs';

async function adminDeps(config) {
  const { initializeApp, getApps } = await import('firebase-admin/app');
  const { getFirestore, FieldValue } = await import('firebase-admin/firestore');
  const app = getApps().find((a) => a.name === 'reconcile')
    ?? initializeApp({ projectId: config.projectId }, 'reconcile');
  return { db: getFirestore(app), FieldValue };
}

/**
 * The trigger's handler: a CloudEvent for a created document. Only
 * `users/{uid}/withdrawals/{targetId}` is acted on; anything else is ignored.
 */
export async function onWithdrawalCreated(event, { env = process.env, deps } = {}) {
  const config = loadConfig(env);
  const name = String(event?.subject ?? event?.document ?? '');
  const where = intentPath(name.replace(/^.*?documents\//, ''));
  if (!where) return { ignored: true };
  const d = deps ?? (await adminDeps(config));
  return reconcileWithdrawal({ ...d, apply: config.apply }, where.uid, where.targetId);
}

/** The scheduled job: the reconcile backstop, then the sweep. */
export async function scheduledRun({ env = process.env, deps, now = new Date() } = {}) {
  const config = loadConfig(env);
  const d = deps ?? (await adminDeps(config));
  const reconciled = await reconcilePending({ ...d, apply: config.apply });
  const swept = await sweepSubmitted({ ...d, now, ttlDays: config.ttlDays, apply: config.apply });
  return { reconciled, swept };
}

export async function cli(argv, { env = process.env, deps, log = console.log } = {}) {
  const [command, ...flags] = argv;
  const unknown = flags.filter((f) => f !== '--apply');
  if (!['reconcile', 'sweep'].includes(command) || unknown.length) {
    throw new Error('usage: node server/main.mjs reconcile|sweep [--apply]');
  }
  const config = loadConfig({ ...env, APPLY: flags.includes('--apply') ? 'true' : 'false' });
  const d = deps ?? (await adminDeps(config));
  const result = command === 'reconcile'
    ? await reconcilePending({ ...d, apply: config.apply })
    : await sweepSubmitted({ ...d, ttlDays: config.ttlDays, apply: config.apply });
  log(JSON.stringify({ command, apply: config.apply, projectId: config.projectId, result }, null, 2));
  return result;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  cli(process.argv.slice(2)).catch((e) => {
    console.error(e.message);
    process.exitCode = 1;
  });
}
