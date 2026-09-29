// S4: the configuration interface of the reconciliation server. Values come from the
// environment the provider gives the process; FS-G decides what that environment is. This file
// only reads and checks it -- it never talks to Firebase itself.

/** The one project this server may act on. Kept equal to the M1 bootstrap's lock by a test. */
export const TARGET_PROJECT = 'optiqon-voice-47498';

/** The shortest a `submitted` leftover may live before the sweep removes it. */
export const MIN_TTL_DAYS = 30;

export class ConfigError extends Error {
  constructor(message) {
    super(message);
    this.name = 'ConfigError';
  }
}

/**
 * Reads `PROJECT_ID` (or `GOOGLE_CLOUD_PROJECT`), `SWEEP_TTL_DAYS` and `APPLY`.
 *
 * Refuses any project but [TARGET_PROJECT], unless `FIRESTORE_EMULATOR_HOST` is set -- then the
 * run cannot reach a real database and any project id is accepted, which is what the tests use.
 * Writes happen only when `APPLY` is exactly `true`; anything else is a dry run.
 */
export function loadConfig(env = process.env) {
  const projectId = env.PROJECT_ID || env.GOOGLE_CLOUD_PROJECT || '';
  const emulator = Boolean(env.FIRESTORE_EMULATOR_HOST);
  if (!projectId) throw new ConfigError('PROJECT_ID is not set');
  if (!emulator && projectId !== TARGET_PROJECT) {
    throw new ConfigError(`refusing project '${projectId}'; this server only knows '${TARGET_PROJECT}'`);
  }

  const rawTtl = env.SWEEP_TTL_DAYS ?? String(MIN_TTL_DAYS);
  const ttlDays = /^\d+$/.test(rawTtl) ? Number(rawTtl) : NaN;
  if (!Number.isInteger(ttlDays) || ttlDays < MIN_TTL_DAYS) {
    throw new ConfigError(`SWEEP_TTL_DAYS must be a whole number >= ${MIN_TTL_DAYS}, got '${rawTtl}'`);
  }

  return Object.freeze({ projectId, emulator, ttlDays, apply: env.APPLY === 'true' });
}
