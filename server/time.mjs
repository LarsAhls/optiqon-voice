// Time arithmetic the retention and purge code share. Everything takes and returns epoch
// milliseconds, so a Firestore Timestamp, a Date and a plain number all compare the same way.

export const HOUR_MS = 60 * 60 * 1000;
export const DAY_MS = 24 * HOUR_MS;

/** Epoch millis of a Firestore Timestamp, a Date or a number; null for anything else. */
export function millis(value) {
  if (value == null) return null;
  if (typeof value === 'number') return value;
  if (value instanceof Date) return value.getTime();
  if (typeof value.toMillis === 'function') return value.toMillis();
  return null;
}

/**
 * Calendar months added in UTC. The day of the month is clamped, so 31 January + 1 month is
 * 28/29 February rather than overflowing into March: a deadline is never later than the
 * calendar says it should be.
 */
export function addMonths(ms, months) {
  const d = new Date(ms);
  const day = d.getUTCDate();
  d.setUTCDate(1);
  d.setUTCMonth(d.getUTCMonth() + months);
  const last = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 0)).getUTCDate();
  d.setUTCDate(Math.min(day, last));
  return d.getTime();
}
