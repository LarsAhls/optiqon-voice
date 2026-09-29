// The shared body of the predeploy guards. Each guard is one product and one permitted command.
//
// Why guards exist at all: `firebase deploy` with no `--only` selects every configured target,
// and a ruleset published against the wrong project -- `optioqon-voice`, the misspelled older
// project, is one keystroke away -- is a live change to a live surface. A guard is a predeploy
// hook because the whole predeploy chain runs before the first prepare/deploy/release
// (firebase-tools lib/deploy/index.js), so a refusal stops an unscoped deploy before anything at
// all is published, not merely its own part of it.
//
// Two conditions, both required:
//   OPTIQON_ALLOW_DEPLOY names the product -- the deploy was meant, not inherited from a default
//   GCLOUD_PROJECT is exactly the prod id  -- the deploy is aimed at the right project
//
// firebase-tools sets GCLOUD_PROJECT for lifecycle hooks (lib/deploy/lifecycleHooks.js), so the
// second condition reads the project the CLI actually resolved, not a flag this script parsed.
// The hook is NOT told what `--only` said, so the guard cannot check the scope itself; it names
// the one scoped form in its refusal, and tests/deploy/predeploy-scope.test.mjs pins what each
// scope selects.

export const EXPECTED_PROJECT = 'optiqon-voice-47498';

export function runGuard({ product, permitted }) {
  const name = `${product}-guard`;
  const allowed = (process.env.OPTIQON_ALLOW_DEPLOY ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0);

  const project = process.env.GCLOUD_PROJECT ?? '';

  const problems = [];

  if (!allowed.includes(product)) {
    problems.push(
      `OPTIQON_ALLOW_DEPLOY does not name '${product}' (got: ${JSON.stringify(process.env.OPTIQON_ALLOW_DEPLOY ?? null)}).`,
    );
  }

  if (project !== EXPECTED_PROJECT) {
    problems.push(`GCLOUD_PROJECT is '${project}', expected '${EXPECTED_PROJECT}'.`);
  }

  if (problems.length > 0) {
    console.error(`${name}: refusing to deploy ${product} rules.`);
    for (const p of problems) console.error(`${name}:   - ${p}`);
    console.error(`${name}: the only permitted form is`);
    console.error(`${name}:   ${permitted}`);
    // Exit 2, not 1, and the reason is specific to this toolchain. firebase-tools runs the hook
    // through cross-env-shell, which spawns with cross-spawn under `shell: true`. In that mode
    // cross-spawn cannot resolve the command file, so its verifyENOENT heuristic treats *any*
    // exit status of 1 as "the command did not exist" and raises a spurious ENOENT that buries
    // the message above under a stack trace (cross-spawn 7.0.6, lib/enoent.js). The heuristic
    // only fires on status 1. Any other non-zero code aborts the deploy just as hard and reaches
    // the operator intact.
    process.exit(2);
  }

  console.log(`${name}: ok -- ${product} deploy allowed for ${EXPECTED_PROJECT}.`);
}
