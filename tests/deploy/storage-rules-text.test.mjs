// Properties of storage.rules that no emulator can prove, pinned against the text itself.
//
// Cloud Storage allows at most two distinct Firestore documents per rules evaluation. The
// Storage emulator enforces no such ceiling -- the PR-0 spike passed three and five documents
// there -- so a green emulator suite says nothing about it. Only a live probe settles it (P2).
// What CAN be held still in the repository is the shape that keeps each path at two:
//
//   * every `firestore.get` in the file is one of exactly three helper functions, each reading
//     one document: the attachment, users/{ownerUid}, users/{request.auth.uid};
//   * each allow statement reaches at most two of those helpers, the attachment plus one users/;
//   * each allow compares uid or claim BEFORE its first helper, so a caller who is not the
//     owner never pays for the owner's document, and an admin reading another owner's image
//     never reaches users/{ownerUid} at all (plan §4).
//
// A textual check is crude on purpose. If a later edit makes it too crude to follow, the edit
// has changed the lookup budget, and it is right that a person reads the rules again.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const RULES = readFileSync(path.join(REPO, 'storage.rules'), 'utf8');
const code = RULES.split('\n').map((l) => l.replace(/\/\/.*$/, '')).join('\n');

// The body of the case-attachments match, up to the catch-all.
const block = code.slice(
  code.indexOf('match /case-attachments/{ownerUid}/{caseId}/{aid}'),
  code.indexOf('match /{allPaths=**}'),
);

const HELPERS = {
  att: 'cases/$(caseId)/attachments/$(aid)',
  owner: 'users/$(ownerUid)',
  me: 'users/$(request.auth.uid)',
};
// Helpers that reach a document only through another helper.
const VIA = { liveOwner: ['owner'], liveAdmin: ['me'] };

function allows() {
  const out = [];
  const re = /allow\s+([a-z, ]+):\s*if\s+([\s\S]*?);/g;
  let m;
  while ((m = re.exec(block))) out.push({ ops: m[1].trim(), cond: m[2].replace(/\s+/g, ' ').trim() });
  return out;
}

function docsOf(cond) {
  const docs = new Set();
  for (const h of Object.keys(HELPERS)) if (new RegExp(`\\b${h}\\(\\)`).test(cond)) docs.add(h);
  for (const [h, via] of Object.entries(VIA)) if (new RegExp(`\\b${h}\\(\\)`).test(cond)) via.forEach((d) => docs.add(d));
  return docs;
}

test('every firestore.get is one of the three one-document helpers', () => {
  const gets = [...code.matchAll(/firestore\.(get|exists|getAfter)\(([^)]*\))/g)].map((m) => m[0]);
  assert.equal(gets.length, 3, gets.join('\n'));
  for (const [name, doc] of Object.entries(HELPERS)) {
    const fn = new RegExp(
      `function ${name}\\(\\) \\{\\s*return firestore\\.get\\(/databases/\\(default\\)/documents/${doc.replace(/[$()./]/g, '\\$&')}\\)\\.data;\\s*\\}`,
    );
    assert.match(block, fn, name);
  }
});

test('the case-attachments block has exactly the allow statements the plan names', () => {
  assert.deepEqual(allows().map((a) => a.ops), ['get', 'get', 'list', 'create', 'update, delete']);
  // `read` would silently include list; `write` would silently include update and delete.
  assert.doesNotMatch(block, /allow\s+[a-z, ]*\b(read|write)\b/);
});

test('each allow reaches at most two distinct documents, and never both users/ documents', () => {
  for (const { ops, cond } of allows()) {
    const docs = docsOf(cond);
    assert.ok(docs.size <= 2, `${ops}: ${[...docs]}`);
    assert.ok(!(docs.has('owner') && docs.has('me')), `${ops} reads users/ twice`);
    if (docs.size > 0) assert.ok(docs.has('att'), `${ops} must be bound to its attachment`);
  }
});

test('uid or claim is compared before the first document is read', () => {
  const [ownerGet, adminGet, list, create, updel] = allows();
  const firstHelper = (cond) => Math.min(
    ...[...Object.keys(HELPERS), ...Object.keys(VIA)]
      .map((h) => cond.search(new RegExp(`\\b${h}\\(\\)`)))
      .filter((i) => i >= 0),
  );
  for (const a of [ownerGet, create]) {
    const guard = a.cond.indexOf('request.auth.uid == ownerUid');
    assert.ok(guard >= 0 && guard < firstHelper(a.cond), a.ops);
    assert.ok(a.cond.startsWith('request.auth != null'), a.ops);
  }
  const claim = adminGet.cond.indexOf('request.auth.token.admin == true');
  assert.ok(claim >= 0 && claim < firstHelper(adminGet.cond), 'admin get');
  assert.deepEqual(docsOf(ownerGet.cond), new Set(['owner', 'att']));
  assert.deepEqual(docsOf(adminGet.cond), new Set(['me', 'att']));
  assert.deepEqual(docsOf(create.cond), new Set(['owner', 'att']));
  assert.equal(list.cond, 'false');
  assert.equal(updel.cond, 'false');
});

test('the create path carries every byte-side condition of the plan', () => {
  const create = allows()[3].cond;
  for (const needle of [
    'resource == null',
    "!('deleteRequestedAt' in att())",
    "request.time < att().createdAt + duration.value(72, 'h')",
    'request.resource.size > 0',
    'request.resource.size <= att().maxBytes',
    'request.resource.size <= 2097152',
    "request.resource.contentType.matches('image/(png|jpeg|webp)')",
    'request.resource.metadata == null || request.resource.metadata.size() == 0',
  ]) assert.ok(create.includes(needle), needle);
});

test('both get paths refuse a tombstoned attachment', () => {
  const [ownerGet, adminGet] = allows();
  for (const a of [ownerGet, adminGet]) assert.ok(a.cond.endsWith("!('deleteRequestedAt' in att())"));
});

test('everything outside case-attachments is denied', () => {
  assert.match(code, /match \/\{allPaths=\*\*\} \{\s*allow read, write: if false;\s*\}/);
  assert.equal((code.match(/match \//g) || []).length, 3); // /b/{bucket}/o, the block, the catch-all
});
