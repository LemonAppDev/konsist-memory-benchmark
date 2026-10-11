#!/usr/bin/env bash
# Runs the benchmark of the installed variant in the project directory (Konsist creates the project scope from the
# working directory).
#
# Usage: scripts/run.sh <variant> <project-dir> <max-heap> <mode> [mode arguments] [options]
#   Modes and options: see README.md or run the benchmark without arguments.
#   Extra JVM options can be passed in BENCHMARK_JAVA_OPTS environment variable.
#
# Example: scripts/run.sh release projects/ktor 8g suite --histogram results/release-ktor-histogram.txt
set -euo pipefail

if [ $# -lt 4 ]; then
    sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
fi

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VARIANT="$1"
PROJECT_DIR="$2"
MAX_HEAP="$3"
shift 3

BIN="$ROOT_DIR/build/install/$VARIANT/bin/konsist-memory-benchmark"
[ -x "$BIN" ] || { echo "Variant '$VARIANT' is not installed, run scripts/install.sh first" >&2; exit 1; }
[ -d "$PROJECT_DIR" ] || { echo "Project directory not found: $PROJECT_DIR" >&2; exit 1; }

# Relative output paths passed to the benchmark are resolved against the directory the script was started from
export BENCHMARK_CALLER_DIR="$PWD"
export BENCHMARK_VARIANT="$VARIANT"

cd "$PROJECT_DIR"
JAVA_OPTS="-Xmx$MAX_HEAP ${BENCHMARK_JAVA_OPTS:-}" exec "$BIN" "$@"
