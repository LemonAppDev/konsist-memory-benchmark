#!/usr/bin/env bash
# Finds the smallest max heap (-Xmx) at which the benchmark completes (binary search). This is the most user-facing
# metric: it is the smallest maxHeapSize of the Gradle test task that does not fail with OutOfMemoryError.
#
# Usage: scripts/min-heap.sh <variant> <project-dir> [low-mb] [high-mb] [step-mb] [timeout-sec] [mode arguments]
#   Defaults: low 64, high 4096, step 32, timeout 600, mode suite
#   'high' has to complete (it is checked first), 'low' is expected to fail.
#   Runs taking longer than the timeout count as failed (with a tight heap JVM can spend most of the time in GC).
#
# Example: scripts/min-heap.sh release projects/ktor 64 1024 16
set -uo pipefail

if [ $# -lt 2 ]; then
    sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
fi

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VARIANT="$1"
PROJECT_DIR="$2"
LOW="${3:-64}"
HIGH="${4:-4096}"
STEP="${5:-32}"
TIMEOUT="${6:-600}"
shift $(($# < 6 ? $# : 6))
MODE_ARGS=("$@")
[ ${#MODE_ARGS[@]} -eq 0 ] && MODE_ARGS=(suite)

# Returns 0 when the benchmark completes with the given heap (MB)
run_with_heap() {
    local heap="$1" start output status result
    start=$(date +%s)
    # perl alarm works as a timeout on macOS and Linux
    output=$(perl -e 'alarm shift; exec @ARGV' "$TIMEOUT" \
        "$ROOT_DIR/scripts/run.sh" "$VARIANT" "$PROJECT_DIR" "${heap}m" "${MODE_ARGS[@]}" --no-gc 2>&1)
    status=$?
    result=$(echo "$output" | grep -E "^RESULT" | tail -1)
    [ -z "$result" ] && result="RESULT NONE (exit status $status)"
    echo "-Xmx${heap}m: $result ($(($(date +%s) - start))s)" >&2
    [ $status -eq 0 ] && [[ "$result" == "RESULT OK"* ]]
}

if ! run_with_heap "$HIGH"; then
    echo "MIN_HEAP > ${HIGH}m"
    exit 1
fi

while [ $((HIGH - LOW)) -gt "$STEP" ]; do
    mid=$(((LOW + HIGH) / 2 / STEP * STEP))
    [ "$mid" -le "$LOW" ] && mid=$((LOW + STEP))

    if run_with_heap "$mid"; then
        HIGH=$mid
    else
        LOW=$mid
    fi
done

echo "MIN_HEAP ${HIGH}m"
