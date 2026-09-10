#!/usr/bin/env bash
# G1 step 5 — local check of a freshly downloaded app/google-services.json before it is used.
# Read-only, no network. Exit 0 only when the file belongs to the expected project AND
# contains at least one OAuth client (that is what makes Google sign-in available: the
# Gradle plugin generates default_web_client_id from oauth_client[]).
#
# Usage: scripts/gate/check-google-services.sh [path] [expected-project-id]
set -euo pipefail
FILE="${1:-app/google-services.json}"
PROJECT="${2:-optiqon-voice-47498}"
[ -f "$FILE" ] || { echo "MISSING: $FILE"; exit 1; }
command -v node >/dev/null || { echo "node is required"; exit 2; }
node - "$FILE" "$PROJECT" <<'JS'
const [file, expected] = process.argv.slice(2);
const cfg = JSON.parse(require('fs').readFileSync(file, 'utf8'));
const pid = cfg.project_info?.project_id;
const num = cfg.project_info?.project_number;
const clients = cfg.client ?? [];
const app = clients.find(c => c.client_info?.android_client_info?.package_name === 'se.optiqon.voice');
const oauth = app?.oauth_client ?? [];
const web = oauth.filter(c => c.client_type === 3);
let ok = true;
const line = (good, msg) => { console.log(`${good ? 'OK  ' : 'FAIL'} ${msg}`); if (!good) ok = false; };
line(pid === expected, `project_id = ${pid} (expected ${expected})`);
line(!!num, `project_number present`);
line(!!app, `client entry for se.optiqon.voice present`);
line(oauth.length >= 1, `oauth_client.length = ${oauth.length} (need >= 1)`);
line(web.length >= 1, `web OAuth client (client_type 3) present: ${web.length}`);
line((app?.api_key ?? []).length >= 1, `api_key present (value not printed)`);
process.exit(ok ? 0 : 1);
JS
