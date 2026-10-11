#!/usr/bin/env bash
# Compares two installed variants (e.g. Konsist release and a local branch) on the project:
#   1. Same results     query results of both variants are identical (dump mode + cmp)
#   2. Retained heap    heap retained after the suite (with -Xmx16g) and heap histogram summary
#   3. Steps            heap retained after each query kind
#   4. Smallest heap    smallest -Xmx that completes the suite (--min-heap, takes several minutes)
#   5. Time             wall and CPU time of the suite, variants run alternately (--timing <runs>)
# Results are stored in results/<project-name>/.
#
# Usage: scripts/compare.sh <baseline-variant> <candidate-variant> <project-dir> [--min-heap] [--timing <runs>]
#
# Example: scripts/compare.sh release develop projects/ktor --min-heap --timing 3
set -uo pipefail

if [ $# -lt 3 ]; then
    sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
fi

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BASELINE="$1"
CANDIDATE="$2"
PROJECT_DIR="$(cd "$3" && pwd)"
shift 3

MIN_HEAP=false
TIMING_RUNS=0
while [ $# -gt 0 ]; do
    case "$1" in
    --min-heap) MIN_HEAP=true ;;
    --timing) TIMING_RUNS="$2"; shift ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
    shift
done

RETAINED_HEAP="${RETAINED_HEAP:-16g}"
TIMING_HEAP="${TIMING_HEAP:-4g}"
RESULTS_DIR="$ROOT_DIR/results/$(basename "$PROJECT_DIR")"
mkdir -p "$RESULTS_DIR"

run() {
    "$ROOT_DIR/scripts/run.sh" "$@"
}

echo "== Same results"
for variant in "$BASELINE" "$CANDIDATE"; do
    run "$variant" "$PROJECT_DIR" "$RETAINED_HEAP" dump "$RESULTS_DIR/$variant-dump.tsv" --no-gc | grep "^RESULT" | sed "s/^/$variant: /"
done
if cmp -s "$RESULTS_DIR/$BASELINE-dump.tsv" "$RESULTS_DIR/$CANDIDATE-dump.tsv"; then
    echo "identical ($(wc -l <"$RESULTS_DIR/$CANDIDATE-dump.tsv" | tr -d ' ') result lines)"
else
    echo "DIFFERENT (first differences):"
    diff "$RESULTS_DIR/$BASELINE-dump.tsv" "$RESULTS_DIR/$CANDIDATE-dump.tsv" | head -10
fi

echo
echo "== Retained heap after suite (-Xmx$RETAINED_HEAP)"
for variant in "$BASELINE" "$CANDIDATE"; do
    run "$variant" "$PROJECT_DIR" "$RETAINED_HEAP" suite --histogram "$RESULTS_DIR/$variant-histogram.txt" \
        >"$RESULTS_DIR/$variant-suite.txt" 2>&1
    echo "$variant: $(grep -E '^CHECKPOINT suite' "$RESULTS_DIR/$variant-suite.txt")"
done
for variant in "$BASELINE" "$CANDIDATE"; do
    echo "-- $variant heap histogram"
    python3 "$ROOT_DIR/scripts/histogram.py" "$RESULTS_DIR/$variant-histogram.txt" 0
done

echo
echo "== Retained heap per query (cumulative)"
for variant in "$BASELINE" "$CANDIDATE"; do
    run "$variant" "$PROJECT_DIR" "$RETAINED_HEAP" steps >"$RESULTS_DIR/$variant-steps.txt" 2>&1
done
paste <(grep "^CHECKPOINT" "$RESULTS_DIR/$BASELINE-steps.txt" | awk '{print $2, $3}') \
    <(grep "^CHECKPOINT" "$RESULTS_DIR/$CANDIDATE-steps.txt" | awk '{print $3}') |
    awk -v b="$BASELINE" -v c="$CANDIDATE" 'BEGIN {printf "%-32s %16s %16s\n", "query", b, c}
        {gsub("usedAfterGcMb=", "", $2); gsub("usedAfterGcMb=", "", $3); printf "%-32s %13s MB %13s MB\n", $1, $2, $3}'

if [ "$MIN_HEAP" = true ]; then
    echo
    echo "== Smallest -Xmx that completes the suite"
    for variant in "$BASELINE" "$CANDIDATE"; do
        echo "$variant: $("$ROOT_DIR/scripts/min-heap.sh" "$variant" "$PROJECT_DIR" 2>>"$RESULTS_DIR/$variant-min-heap.log")"
    done
fi

if [ "$TIMING_RUNS" -gt 0 ]; then
    echo
    echo "== Suite time (-Xmx$TIMING_HEAP, $TIMING_RUNS runs per variant, run alternately)"
    : >"$RESULTS_DIR/timing.txt"
    for i in $(seq 1 "$TIMING_RUNS"); do
        for variant in "$BASELINE" "$CANDIDATE"; do
            times=$({ /usr/bin/time -p "$ROOT_DIR/scripts/run.sh" "$variant" "$PROJECT_DIR" "$TIMING_HEAP" suite --no-gc >/dev/null; } 2>&1)
            real=$(echo "$times" | awk '/^real/ {print $2}')
            cpu=$(echo "$times" | awk '/^user/ {u = $2} /^sys/ {s = $2} END {print u + s}')
            echo "$variant $real $cpu" >>"$RESULTS_DIR/timing.txt"
        done
    done
    python3 - "$RESULTS_DIR/timing.txt" <<'EOF'
import statistics
import sys

runs = {}
for line in open(sys.argv[1]):
    variant, real, cpu = line.split()
    runs.setdefault(variant, []).append((float(real), float(cpu)))
for variant, values in runs.items():
    real = statistics.median(v[0] for v in values)
    cpu = statistics.median(v[1] for v in values)
    print(f"{variant}: median wall time {real:.1f} s, median CPU time {cpu:.1f} s")
EOF
fi
