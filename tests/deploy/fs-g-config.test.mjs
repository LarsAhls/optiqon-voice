// FS-S468: deploy/fs-g/ holds the intended FS-G provider definitions. Nothing deploys them; these
// tests keep them consistent with the server code so FS-G does not discover a mismatch in
// production: same project, bucket and region; every trigger lands on a route that accepts its
// event type; the env the service gets is the env the config reads, dry run by default; every
// server query has its index; no key material anywhere.
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { test } from 'node:test';
import { TARGET_BUCKET, TARGET_PROJECT } from '../../server/config.mjs';
import { ROUTES } from '../../server/http.mjs';

const DIR = 'deploy/fs-g';
const targets = JSON.parse(readFileSync(`${DIR}/targets.json`, 'utf8'));
const indexes = JSON.parse(readFileSync(`${DIR}/firestore.indexes.json`, 'utf8'));

test('the definitions name the one project, bucket and region', () => {
  assert.equal(targets.project, TARGET_PROJECT);
  assert.equal(targets.bucket, TARGET_BUCKET);
  assert.equal(targets.region, 'europe-north2');
  assert.equal(targets.service.region, 'europe-north2');
  for (const t of targets.triggers) assert.equal(t.location, 'europe-north2', t.name);
  // The scheduler is the one place another region may come up; it must stay flagged for FS-G.
  assert.equal(targets.scheduler.location, 'europe-north2');
  assert.match(targets.scheduler.locationNote, /FS-G/);
  assert.match(targets.status, /NOT DEPLOYED/);
});

test('every trigger lands on a route that accepts exactly its event type', () => {
  const routed = new Set();
  for (const t of targets.triggers) {
    const route = ROUTES[t.route];
    assert.ok(route, `${t.name}: no route ${t.route}`);
    assert.equal(route.type, t.eventType, t.name);
    routed.add(t.route);
  }
  assert.equal(ROUTES[targets.scheduler.route].type, null);
  routed.add(targets.scheduler.route);
  assert.deepEqual([...routed].sort(), Object.keys(ROUTES).sort(), 'every route has a caller');
});

test('the trigger patterns are the documents the entrypoints act on', () => {
  const byRoute = Object.fromEntries(targets.triggers.map((t) => [t.route, t.documentPattern]));
  assert.equal(byRoute['/events/withdrawal'], 'users/{uid}/withdrawals/{targetId}');
  assert.equal(byRoute['/events/attachment'], 'cases/{caseId}/attachments/{aid}');
  assert.equal(byRoute['/events/case-event'], 'cases/{caseId}/events/{eventId}');
});

test('the service env is what server/config.mjs reads, and it starts as a dry run', () => {
  const env = targets.service.env;
  assert.deepEqual(Object.keys(env).sort(), ['APPLY', 'BUCKET', 'PROJECT_ID', 'SWEEP_TTL_DAYS']);
  assert.equal(env.APPLY, 'false');
  assert.equal(env.PROJECT_ID, TARGET_PROJECT);
  assert.equal(env.BUCKET, TARGET_BUCKET);
  assert.ok(Number(env.SWEEP_TTL_DAYS) >= 30);
  assert.equal(targets.service.allowUnauthenticated, false);
});

test('least privilege: no broad roles, no Auth admin for the server, storage only on the bucket', () => {
  const broad = /roles\/(owner|editor|storage\.admin|firebaseauth\.admin|iam\.serviceAccountKeyAdmin|firebase\.admin)$/;
  for (const [name, sa] of Object.entries(targets.serviceAccounts)) {
    for (const r of sa.projectRoles) assert.doesNotMatch(r, broad, `${name}: ${r}`);
    assert.ok(!sa.projectRoles.some((r) => r.startsWith('roles/storage.')), `${name}: project-wide storage`);
  }
  assert.deepEqual(Object.keys(targets.serviceAccounts['voice-feedback-server'].bucketRoles), [TARGET_BUCKET]);
  const invokers = JSON.stringify(targets);
  assert.doesNotMatch(invokers, /allUsers|allAuthenticatedUsers/);
});

test('every query the server runs has the index it needs', () => {
  const composite = (group, scope, fields) => indexes.indexes.some((i) =>
    i.collectionGroup === group && i.queryScope === scope
    && JSON.stringify(i.fields.map((f) => f.fieldPath)) === JSON.stringify(fields));
  const groupField = (group, field) => indexes.fieldOverrides.some((o) =>
    o.collectionGroup === group && o.fieldPath === field
    && o.indexes.some((i) => i.queryScope === 'COLLECTION_GROUP' && i.order === 'ASCENDING')
    // An override replaces the defaults; the collection-scope ones must be kept.
    && o.indexes.some((i) => i.queryScope === 'COLLECTION' && i.order === 'ASCENDING'));

  const source = (f) => readFileSync(`server/${f}`, 'utf8');
  // sweepSubmitted: cases and the events group by (state ==, createdAt <).
  assert.match(source('withdrawal.mjs'), /collection\('cases'\)\s*\.where\('state', '==', 'submitted'\)\.where\('createdAt', '<'/);
  assert.ok(composite('cases', 'COLLECTION', ['state', 'createdAt']));
  assert.match(source('withdrawal.mjs'), /collectionGroup\('events'\)\s*\.where\('state', '==', 'submitted'\)\.where\('createdAt', '<'/);
  assert.ok(composite('events', 'COLLECTION_GROUP', ['state', 'createdAt']));
  // sweepPurges: the attachments group by deleteRequestedAt.
  assert.match(source('purge.mjs'), /collectionGroup\('attachments'\)\s*\.where\('deleteRequestedAt'/);
  assert.ok(groupField('attachments', 'deleteRequestedAt'));
  // notify: the notificationTokens group by token.
  assert.match(source('notify.mjs'), /collectionGroup\('notificationTokens'\)\.where\('token', '=='/);
  assert.ok(groupField('notificationTokens', 'token'));
});

test('a plain firebase deploy does not pick the FS-G indexes up', () => {
  assert.doesNotMatch(readFileSync('firebase.json', 'utf8'), /indexes|deploy\/fs-g/);
});

test('the image carries the locked firebase-admin and nothing secret', () => {
  const docker = readFileSync(`${DIR}/Dockerfile`, 'utf8');
  const locked = JSON.parse(readFileSync('package-lock.json', 'utf8')).packages['node_modules/firebase-admin'].version;
  assert.match(docker, new RegExp(`firebase-admin@${locked.replace(/\./g, '\\.')}\\b`));
  assert.match(docker, /CMD \["node", "server\/http\.mjs"\]/);
  const copies = docker.split('\n').filter((l) => /^\s*(COPY|ADD)\b/.test(l));
  assert.deepEqual(copies, ['COPY server/ ./server/']);
});

test('no key material in the FS-G definitions', () => {
  for (const f of readdirSync(DIR)) {
    const text = readFileSync(`${DIR}/${f}`, 'utf8');
    assert.doesNotMatch(text, /BEGIN [A-Z ]*PRIVATE KEY|"private_key"|"type":\s*"service_account"|AIza[0-9A-Za-z_-]{20,}/, f);
  }
  assert.match(targets.keys, /No service-account key/);
});
