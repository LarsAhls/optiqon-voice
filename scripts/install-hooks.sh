#!/bin/sh
# Point this clone at the versioned hooks. Run once per clone; worktrees inherit it.
set -eu
cd "$(git rev-parse --show-toplevel)"
git config core.hooksPath .githooks
echo "core.hooksPath = $(git config --get core.hooksPath)"
