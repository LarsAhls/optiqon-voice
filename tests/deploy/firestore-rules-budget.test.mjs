// The document-access budget of firestore.rules, pinned against the text itself (FS-S12).
//
// Firestore allows 10 document accesses (get / getAfter / exists) per single-document request
// and 20 across a batch or transaction. The emulator does enforce the ceiling, but only for the
// requests a test happens to make, and only at the ceiling -- it says nothing about how close a
// rule has crept. This test reads the rules as data and holds a tighter line:
//
//   * every helper call is expanded, recursively, into the accesses it makes;
//   * accesses are keyed by kind and path, with get and exists of one path counted once and
//     getAfter of it counted apart (the conservative reading);
//   * every single allow statement -- one branch, the path a legitimate request takes -- must
//     stay at or under PER_OP (7). Ternaries count both arms;
//   * for each match and operation, the accesses of ALL its allow statements are also summed as
//     a union and held at or under the platform's 10. The platform may evaluate several allows
//     for one request and a branch that comes out false has still spent what it read before it
//     failed. Every branch here opens with cheap identity and key checks, so the true figure is
//     lower; the union is the bound nobody can argue with;
//   * the commits the app makes (case + child, intent + quota, the attachment transaction) are
//     summed the same way across their documents and held at or under the platform's 20.
//
// The result is an upper bound: two spellings of one document count twice. If an edit makes
// this too crude to follow, the edit has moved the budget, and a person should read the rules
// again. The self-test at the bottom proves the check can go red.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const RULES = readFileSync(path.join(REPO, 'firestore.rules'), 'utf8');

export const PER_OP = 7;
const PLATFORM_PER_OP = 10;
const PLATFORM_PER_COMMIT = 20;

function stripComments(text) {
  return text.split('\n').map((l) => l.replace(/\/\/.*$/, '')).join('\n');
}

/** Index of the bracket closing the one at `open`. */
function closing(s, open, [o, c] = ['(', ')']) {
  let depth = 0;
  for (let i = open; i < s.length; i++) {
    if (s[i] === o) depth++;
    else if (s[i] === c && --depth === 0) return i;
  }
  throw new Error(`unbalanced ${o} at ${open}`);
}

/** Split at top-level commas. */
function splitArgs(s) {
  const out = [];
  let depth = 0;
  let start = 0;
  for (let i = 0; i < s.length; i++) {
    if ('([{'.includes(s[i])) depth++;
    else if (')]}'.includes(s[i])) depth--;
    else if (s[i] === ',' && depth === 0) {
      out.push(s.slice(start, i).trim());
      start = i + 1;
    }
  }
  if (s.slice(start).trim()) out.push(s.slice(start).trim());
  return out;
}

/** Every `function name(params) { [let x = ...;]* return expr; }` as an expression template. */
function parseFunctions(code) {
  const fns = {};
  const re = /function\s+(\w+)\s*\(([^)]*)\)\s*\{/g;
  let m;
  while ((m = re.exec(code))) {
    const open = m.index + m[0].length - 1;
    const body = code.slice(open + 1, closing(code, open, ['{', '}']));
    let expr = body.slice(body.indexOf('return') + 'return'.length).replace(/;\s*$/, '').trim();
    const lets = [...body.matchAll(/let\s+(\w+)\s*=\s*([\s\S]*?);/g)];
    for (const [, name, value] of lets.reverse()) {
      expr = expr.replace(new RegExp(`\\b${name}\\b`, 'g'), `(${value.trim()})`);
    }
    fns[m[1]] = { params: m[2].split(',').map((p) => p.trim()).filter(Boolean), expr };
  }
  return fns;
}

/** Inline every known helper call until none is left. */
function expand(expr, fns, depth = 0) {
  if (depth > 20) throw new Error('helper expansion did not terminate');
  let changed = false;
  const re = /\b(\w+)\s*\(/g;
  let out = '';
  let last = 0;
  let m;
  while ((m = re.exec(expr))) {
    const fn = fns[m[1]];
    if (!fn) continue;
    const open = m.index + m[0].length - 1;
    const close = closing(expr, open);
    const args = splitArgs(expr.slice(open + 1, close));
    let body = fn.expr;
    fn.params.forEach((p, i) => {
      body = body.replace(new RegExp(`\\b${p}\\b`, 'g'), `(${args[i] ?? ''})`);
    });
    out += expr.slice(last, m.index) + `(${body})`;
    last = close + 1;
    re.lastIndex = close + 1;
    changed = true;
  }
  out += expr.slice(last);
  return changed ? expand(out, fns, depth + 1) : out;
}

/** The set of document accesses an expanded expression can make. */
function accesses(expr) {
  const keys = new Set();
  const re = /\b(getAfter|get|exists)\s*\(/g;
  let m;
  while ((m = re.exec(expr))) {
    // `x.get('field', default)` is a map lookup, not a document read.
    if (expr[m.index - 1] === '.') continue;
    const open = m.index + m[0].length - 1;
    const arg = expr.slice(open + 1, closing(expr, open)).replace(/[\s()]/g, '');
    keys.add(`${m[1] === 'getAfter' ? 'after' : 'read'} ${arg}`);
  }
  return keys;
}

/**
 * `byOp`: { 'match path :: op' -> Set(access) }, the union over every allow for that op.
 * `byAllow`: [{ key, cond, found }] for every single allow statement.
 */
export function analyse(text) {
  const code = stripComments(text);
  const fns = parseFunctions(code);
  const rootOpen = code.indexOf('{', code.indexOf('match /databases/{database}/documents') + 'match /databases/{database}/documents'.length);
  const inner = code.slice(rootOpen + 1, closing(code, rootOpen, ['{', '}']));
  const out = new Map();
  const byAllow = [];
  const re = /match\s+(\S+)\s*\{/g;
  let m;
  while ((m = re.exec(inner))) {
    const open = m.index + m[0].length - 1;
    const block = inner.slice(open + 1, closing(inner, open, ['{', '}']));
    const allowRe = /allow\s+([a-z, ]+):\s*if\s+([\s\S]*?);/g;
    let a;
    while ((a = allowRe.exec(block))) {
      const ops = a[1].split(',').map((o) => o.trim())
        .flatMap((o) => (o === 'read' ? ['get', 'list'] : o === 'write' ? ['create', 'update', 'delete'] : [o]));
      const found = accesses(expand(a[2], fns));
      byAllow.push({ key: `${m[1]} :: ${ops.join(',')}`, cond: a[2].replace(/\s+/g, ' ').slice(0, 80), found });
      for (const op of ops) {
        const key = `${m[1]} :: ${op}`;
        if (!out.has(key)) out.set(key, new Set());
        found.forEach((f) => out.get(key).add(f));
      }
    }
    re.lastIndex = open + 1 + block.length;
  }
  return { byOp: out, byAllow };
}

export function budget(text) {
  return analyse(text).byOp;
}

// The commits the app makes, by the match/op pairs each one touches.
const COMMITS = {
  'case finalize': ['/cases/{caseId} :: update'],
  'message finalize': ['/cases/{caseId}/events/{eventId} :: update', '/cases/{caseId} :: update'],
  'writer reply / status': ['/cases/{caseId}/events/{eventId} :: create', '/cases/{caseId} :: update'],
  'attachment transaction': [
    '/cases/{caseId}/attachments/{aid} :: create', '/cases/{caseId} :: update',
    '/cases/{caseId}/events/{eventId} :: update', '/users/{userId}/uploads/{aid} :: create',
    '/users/{userId}/quota/attachments :: update'],
  'tombstone': ['/cases/{caseId}/attachments/{aid} :: update', '/cases/{caseId} :: update'],
  'withdrawal': ['/users/{userId}/withdrawals/{targetId} :: create',
    '/users/{userId}/quota/withdrawals :: create', '/users/{userId}/quota/withdrawals :: update'],
};

function commitCost(map, keys) {
  const all = new Set();
  for (const k of keys) {
    assert.ok(map.has(k), `missing ${k}`);
    map.get(k).forEach((a) => all.add(a));
  }
  return all.size;
}

function overPerAllow(list, limit) {
  return list.filter((a) => a.found.size > limit)
    .map((a) => `${a.key} [${a.cond}]: ${a.found.size}\n    ${[...a.found].join('\n    ')}`);
}

function overBudget(map, limit) {
  return [...map].filter(([, s]) => s.size > limit)
    .map(([k, s]) => `${k}: ${s.size}\n    ${[...s].join('\n    ')}`);
}

test('every single allow stays within the documented budget of 7 accesses', () => {
  const { byOp, byAllow } = analyse(RULES);
  assert.ok(byOp.size > 30, `parsed only ${byOp.size} match/op pairs -- the parser has lost the file`);
  assert.ok(byAllow.length > 40, `parsed only ${byAllow.length} allows`);
  assert.deepEqual(overPerAllow(byAllow, PER_OP), []);
});

test('every match and operation, all branches together, stays within the platform 10', () => {
  assert.deepEqual(overBudget(budget(RULES), PLATFORM_PER_OP), []);
});

test('every commit the app makes stays within the platform 20', () => {
  const map = budget(RULES);
  for (const [name, keys] of Object.entries(COMMITS)) {
    const cost = commitCost(map, keys);
    assert.ok(cost <= PLATFORM_PER_COMMIT, `${name}: ${cost}`);
  }
});

test('the lifecycle paths are parsed, and cost what the plan says', () => {
  const map = budget(RULES);
  const size = (k) => {
    assert.ok(map.has(k), `missing ${k}`);
    return map.get(k).size;
  };
  // Nonzero proves the helpers were expanded; the ceilings are the plan's table.
  assert.ok(size('/cases/{caseId} :: create') >= 2);
  assert.ok(size('/cases/{caseId} :: update') <= PLATFORM_PER_OP);
  assert.ok(size('/cases/{caseId}/events/{eventId} :: update') >= 4);
  assert.ok(size('/cases/{caseId}/attachments/{aid} :: create') <= PER_OP);
  assert.ok(size('/users/{userId}/withdrawals/{targetId} :: create') >= 2);
  assert.ok(size('/users/{userId}/quota/withdrawals :: create') >= 2);
  // A withdrawal path reads nothing it does not name: no case, event or attachment document.
  for (const k of ['/users/{userId}/withdrawals/{targetId} :: create',
    '/users/{userId}/quota/withdrawals :: create', '/users/{userId}/quota/withdrawals :: update']) {
    for (const acc of map.get(k)) assert.doesNotMatch(acc, /cases\//, `${k} reads ${acc}`);
  }
});

test('the check goes red when a rule reads more (self-test)', () => {
  // Pile extra reads into the case create. If the parser missed them the check would be blind.
  const extra = Array.from({ length: PLATFORM_PER_OP }, (_, i) =>
    `&& exists(/databases/$(database)/documents/probe/p${i})`).join('\n        ');
  const bloated = RULES.replace('allow create: if isApproved()', `allow create: if isApproved()\n        ${extra}`);
  assert.notEqual(bloated, RULES);
  const over = overPerAllow(analyse(bloated).byAllow, PER_OP);
  assert.equal(over.length, 1, over.join('\n'));
  assert.match(over[0], /^\/cases\/\{caseId\} :: create/);
  assert.equal(overBudget(budget(bloated), PLATFORM_PER_OP).length, 1);
  // And through a helper: a function that reads is expanded, not skipped.
  const viaHelper = RULES.replace('function currentGen() {',
    `function probeRead(n) { return exists(/databases/$(database)/documents/probe/$(n)); }
    function currentGen() {`)
    .replace('allow create: if isApproved()', `allow create: if isApproved()
        ${Array.from({ length: PER_OP }, (_, i) => `&& probeRead('h${i}')`).join('\n        ')}`);
  assert.equal(overPerAllow(analyse(viaHelper).byAllow, PER_OP).length, 1);
  // One more read on any case-update branch tips the union over the platform ceiling.
  const anchor = '&& !exists(withdrawalDoc(uid(), caseId))\n        && !(\'withdrawnAt\' in resource.data)';
  assert.equal(RULES.split(anchor).length, 2, 'the case finalize branch moved; update the self-test');
  const tipped = RULES.replace(anchor,
    `${anchor}\n        && exists(/databases/$(database)/documents/probe/tip)`);
  assert.deepEqual(overBudget(budget(tipped), PLATFORM_PER_OP).map((l) => l.replace(/: \d+[\s\S]*$/, '')),
    ['/cases/{caseId} :: update']);
});

test('the documented budget is itself under the platform ceiling', () => {
  assert.ok(PER_OP < PLATFORM_PER_OP);
});
