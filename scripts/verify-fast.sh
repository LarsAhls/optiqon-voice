#!/bin/sh
# The hard gate in front of `git commit`. Also the agent's own inner loop: run it by hand.
#
#   sh scripts/verify-fast.sh          # what is staged (what the hook checks)
#   sh scripts/verify-fast.sh --all    # every tracked file
#
# WHAT IS NOT HERE, AND WHY
#
# Not the compiler, and not the tests. That is a measurement, not a preference. On this machine,
# in a warm daemon with the configuration cache reused:
#
#   `:app:compileDebugKotlin`, nothing changed ............... 15 s
#   `:app:compileDebugKotlin`, one comment added to one file .. 4 m 30 s
#
# The 15 s is Gradle starting, configuring and deciding it had nothing to do. Any gate that shells
# out to `gradlew` therefore costs a quarter-minute before it checks anything, and the case it
# actually meets -- a commit, which by definition has changed a source file -- costs four and a
# half minutes, because KSP re-runs Hilt and Room. A commit gate at that price does not get
# obeyed, it gets bypassed. Compilation and the suite stay where they already are: the agent's
# explicit runs, and CI on every push to every branch.
#
# What is left is the class of defect that is (a) free to detect, (b) deterministic, and (c) worse
# after a commit than before one. That is almost exactly one thing -- material that must never
# enter the history, because once it does, removing it means rewriting the history. CI cannot help
# with it: by the time CI sees the push, the commit exists.
#
# So this gate does not prove the code works. It proves the commit is not one of the few kinds
# that cannot be undone by a later commit. Everything else is CI's job.
set -eu

mode="${1:-staged}"

case "$mode" in
    --all) files=$(git ls-files) ;;
    staged) files=$(git diff --cached --name-only --diff-filter=ACMR) ;;
    *) echo "usage: verify-fast.sh [--all]" >&2; exit 2 ;;
esac

failed=0

fail() {
    failed=1
    echo "verify-fast: $1" >&2
}

# ---------------------------------------------------------------- secrets by path
#
# Every one of these is in .gitignore, so reaching this check means someone passed `git add -f`
# or the ignore rule moved. The keystore in the repo root is the deliberate exception: the debug
# key is committed on purpose and the release build falls back to it, which is why the check is
# a list of names rather than "anything that looks like a key".
for f in $files; do
    case "$f" in
        local.properties|*/local.properties)
            fail "$f is machine-local and must not be committed." ;;
        google-services*.json|*/google-services*.json)
            fail "$f is downloaded per machine from the Firebase console; it is not repo content." ;;
        release-signing.properties|*/release-signing.properties)
            fail "$f carries the release signing material. It is supplied through RELEASE_* only." ;;
        *.example)
            : ;;  # `.env.example` is a committed template; it is the thing that has no values.
        .env|.env.*|*/.env|*/.env.*)
            fail "$f is an environment file and must not be committed." ;;
        debug.keystore)
            : ;;  # committed on purpose; see app/build.gradle.kts
        *.keystore|*.jks|*/*.keystore|*/*.jks)
            fail "$f is a keystore. Only the committed debug.keystore belongs in this repo." ;;
    esac
done

# ---------------------------------------------------------------- secrets by content
#
# Two patterns, both unambiguous. Anything fuzzier ("apiKey", a long base64 run) fires on test
# fixtures and on the words themselves, and a gate that cries wolf is a gate that gets bypassed.
for f in $files; do
    [ -f "$f" ] || continue
    case "$f" in *.png|*.jpg|*.webp|*.jar|*.keystore|*.zip) continue ;; esac
    if grep -qE 'BEGIN (RSA |EC |OPENSSH |PGP )?PRIVATE KEY' "$f" 2>/dev/null; then
        fail "$f contains a private key block."
    fi
    # A Google API key has a fixed shape, so this cannot match prose.
    if grep -qE 'AIza[0-9A-Za-z_-]{35}' "$f" 2>/dev/null; then
        fail "$f contains what looks like a Google API key."
    fi
done

# ---------------------------------------------------------------- the no-mocks rule
#
# Not a taste question and not reversible by review alone: the first mock in the suite is the one
# that makes the second one reasonable. It is cheap to check here and it is checked nowhere else --
# adding mockk compiles and passes, so neither the suite nor CI has an opinion about it.
for f in $files; do
    [ -f "$f" ] || continue
    case "$f" in
        *.kt|*.kts) ;;
        *) continue ;;
    esac
    if grep -qE '(^|[^a-zA-Z])io\.mockk|app\.cash\.turbine' "$f" 2>/dev/null; then
        fail "$f introduces mockk or turbine. This suite uses hand-written fakes; see CLAUDE.md."
    fi
done

if [ "$failed" -ne 0 ]; then
    echo "verify-fast: FAILED -- nothing committed." >&2
    exit 1
fi

echo "verify-fast: ok"
