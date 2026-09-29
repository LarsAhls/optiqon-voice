// The one seam between the server and Cloud Storage.
//
// The server only ever deletes and lists; it never reads bytes, never writes metadata and
// never records a size, checksum or other fingerprint of what it removed. Tests use
// `memoryStorage`; production wraps a firebase-admin bucket with `bucketStorage`.

export const ATTACHMENT_PREFIX = 'case-attachments';

/** The one place an attachment's object path is spelled. Mirrors AttachmentStore.path(). */
export function attachmentPath(ownerUid, caseId, aid) {
  for (const part of [ownerUid, caseId, aid]) {
    if (typeof part !== 'string' || part.length === 0 || part.includes('/')) {
      throw new Error(`invalid attachment path segment: ${JSON.stringify(part)}`);
    }
  }
  return `${ATTACHMENT_PREFIX}/${ownerUid}/${caseId}/${aid}`;
}

export function ownerPrefix(ownerUid) {
  if (typeof ownerUid !== 'string' || ownerUid.length === 0 || ownerUid.includes('/')) {
    throw new Error(`invalid owner uid: ${JSON.stringify(ownerUid)}`);
  }
  return `${ATTACHMENT_PREFIX}/${ownerUid}/`;
}

/**
 * firebase-admin bucket → adapter. `remove` treats a missing object as done: a retry after a
 * crash, or a purge racing another purge, must not fail on the object already being gone.
 */
export function bucketStorage(bucket) {
  return {
    async remove(path) {
      await bucket.file(path).delete({ ignoreNotFound: true });
    },
    async exists(path) {
      const [found] = await bucket.file(path).exists();
      return found;
    },
    async list(prefix) {
      const [files] = await bucket.getFiles({ prefix });
      return files.map((f) => f.name).sort();
    },
  };
}

/** In-memory adapter for tests. `failNext(op)` makes the next call of that op throw once. */
export function memoryStorage(initial = []) {
  const objects = new Set(initial);
  const failures = new Map();
  const calls = [];
  const maybeFail = (op) => {
    const n = failures.get(op) ?? 0;
    if (n > 0) {
      failures.set(op, n - 1);
      throw new Error(`injected ${op} failure`);
    }
  };
  return {
    objects,
    calls,
    put(path) {
      objects.add(path);
    },
    failNext(op, times = 1) {
      failures.set(op, times);
    },
    async remove(path) {
      calls.push(['remove', path]);
      maybeFail('remove');
      objects.delete(path);
    },
    async exists(path) {
      calls.push(['exists', path]);
      maybeFail('exists');
      return objects.has(path);
    },
    async list(prefix) {
      calls.push(['list', prefix]);
      maybeFail('list');
      return [...objects].filter((p) => p.startsWith(prefix)).sort();
    },
  };
}
