#!/usr/bin/env bash
# Builds Konsist jar from a git ref (branch, tag, commit) of a local Konsist repository clone.
# The ref is checked out in a temporary git worktree, so the working tree of the Konsist repository stays untouched.
#
# Usage: scripts/build-konsist-jar.sh <konsist-repo-dir> <git-ref> <name>
#   Output: konsist-jars/<name>.jar
#
# Example: scripts/build-konsist-jar.sh ~/IdeaProjects/konsist develop baseline
set -euo pipefail

if [ $# -ne 3 ]; then
    sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
fi

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
KONSIST_DIR="$(cd "$1" && pwd)"
REF="$2"
NAME="$3"

WORKTREE_DIR="$(mktemp -d)/konsist"
git -C "$KONSIST_DIR" worktree add -q --detach "$WORKTREE_DIR" "$REF"
trap 'git -C "$KONSIST_DIR" worktree remove --force "$WORKTREE_DIR"' EXIT

echo "Building Konsist $REF ($(git -C "$WORKTREE_DIR" rev-parse --short HEAD))"
(cd "$WORKTREE_DIR" && ./gradlew -q :lib:jar)

mkdir -p "$ROOT_DIR/konsist-jars"
cp "$WORKTREE_DIR/lib/build/libs/lib.jar" "$ROOT_DIR/konsist-jars/$NAME.jar"
echo "konsist-jars/$NAME.jar"
