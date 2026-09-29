// FS-S34: `server/` is repository code only. Deploying it is FS-G, so nothing a `firebase deploy`
// reads may name it, and it must not grow a deploy manifest of its own by accident.
import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { test } from 'node:test';

test('firebase.json deploys no functions and names nothing under server/', () => {
  const cfg = JSON.parse(readFileSync('firebase.json', 'utf8'));
  assert.ok(!('functions' in cfg), 'no functions target');
  assert.ok(!('extensions' in cfg), 'no extensions target');
  assert.doesNotMatch(JSON.stringify(cfg), /server\//);
});

test('server/ carries no deploy manifest', () => {
  const files = readdirSync('server');
  for (const f of ['package.json', 'Dockerfile', 'app.yaml', 'cloudbuild.yaml', 'Procfile', 'service.yaml']) {
    assert.ok(!files.includes(f), `server/${f}`);
  }
  assert.ok(!existsSync('.firebaserc') || !/server/.test(readFileSync('.firebaserc', 'utf8')));
});
