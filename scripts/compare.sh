#!/usr/bin/env bash
# Compares two installed variants (e.g. Konsist release and a local branch) on the project:
#   1. Same results     query and type resolution results of both variants are identical (dump and resolve modes)
#   2. Retained heap    heap retained after the suite (with -Xmx16g) and heap histogram summary
#   3. Steps            heap retained after each query kind
#   4. Module scopes    heap after scopes of the biggest modules created one after another (--modules), and with
#                       --min-heap the smallest -Xmx of the module scopes
#   5. Smallest heap    smallest -Xmx that completes the suite (--min-heap, takes several minutes)
#   6. Time             wall and CPU time of the suite in new JVMs, variants run alternately (--timing <runs>)
#   7. Warm time        metrics of suite runs after warm-up in one JVM (--warm <runs>): wall time, CPU time of the query
#                       thread, GC and JIT time, allocations. More stable than --timing (no JIT warm-up in the result)
#   8. Scenarios        time of queries that were slow in some Konsist versions (--scenarios)
# Results are stored in results/<project-name>/, the summary table in summary.md (--markdown also prints it).
#
# Usage: scripts/compare.sh <baseline-variant> <candidate-variant> <project-dir> [--min-heap] [--modules]
#          [--timing <runs>] [--warm <runs>] [--scenarios] [--markdown]
#
# Example: scripts/compare.sh release develop projects/ktor --min-heap --modules --warm 5 --markdown
set -uo pipefail

if [ $# -lt 3 ]; then
    sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
fi

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BASELINE="$1"
CANDIDATE="$2"
PROJECT_DIR="$(cd "$3" && pwd)"
shift 3

MIN_HEAP=false
MODULES=false
TIMING_RUNS=0
WARM_RUNS=0
SCENARIOS=false
MARKDOWN=false
while [ $# -gt 0 ]; do
    case "$1" in
    --min-heap) MIN_HEAP=true ;;
    --modules) MODULES=true ;;
    --timing) TIMING_RUNS="$2"; shift ;;
    --warm) WARM_RUNS="$2"; shift ;;
    --scenarios) SCENARIOS=true ;;
    --markdown) MARKDOWN=true ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
    shift
done

RETAINED_HEAP="${RETAINED_HEAP:-16g}"
TIMING_HEAP="${TIMING_HEAP:-4g}"
# Children are searched in the whole project, which is slow in some Konsist versions, so only a sample is compared
RESOLVE_CHILDREN="${RESOLVE_CHILDREN:-10}"
# Type resolution of some Konsist versions is exponential on deep hierarchies (e.g. projects/synthetic)
RESULTS_TIMEOUT="${RESULTS_TIMEOUT:-900}"
SCENARIO_BUDGET="${SCENARIO_BUDGET:-60}"
PROJECT_NAME="$(basename "$PROJECT_DIR")"
RESULTS_DIR="$ROOT_DIR/results/$PROJECT_NAME"
SUMMARY_ROWS="$RESULTS_DIR/summary-rows.tsv"
mkdir -p "$RESULTS_DIR"
: >"$SUMMARY_ROWS"

run() {
    "$ROOT_DIR/scripts/run.sh" "$@"
}

# Adds a row to the summary table: metric, baseline value, candidate value
summary_row() {
    printf '%s\t%s\t%s\n' "$1" "$2" "$3" >>"$SUMMARY_ROWS"
}

load_average() {
    uptime | sed -E 's/.*load averages?: ([0-9.]+).*/\1/'
}

cpu_count() {
    getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu
}

# Timing is not reliable when other processes load the machine
warn_if_loaded() {
    local load cpus
    load=$(load_average)
    cpus=$(cpu_count)
    if awk -v load="$load" -v cpus="$cpus" 'BEGIN {exit !(load > cpus / 2)}'; then
        echo "WARNING: load average $load (CPUs: $cpus), timing results are noisy"
    fi
}

# Same results check: writes the result file of the mode for both variants and compares them
compare_results() {
    local label="$1" mode="$2" extension="$3"
    shift 3
    local variant result output failed=""
    for variant in "$BASELINE" "$CANDIDATE"; do
        # perl alarm works as a timeout on macOS and Linux
        output=$(perl -e 'alarm shift; exec @ARGV' "$RESULTS_TIMEOUT" "$ROOT_DIR/scripts/run.sh" "$variant" "$PROJECT_DIR" \
            "$RETAINED_HEAP" "$mode" "$RESULTS_DIR/$variant-$mode.$extension" "$@" --no-gc 2>&1 | grep "^RESULT")
        [ -z "$output" ] && output="RESULT TIMEOUT after $RESULTS_TIMEOUT s"
        [[ "$output" == "RESULT OK"* ]] || failed="$failed $variant (${output#RESULT })"
        echo "$variant: $output"
    done
    if [ -n "$failed" ]; then
        result="not compared, failed:$failed"
        echo "$label: $result"
    elif cmp -s "$RESULTS_DIR/$BASELINE-$mode.$extension" "$RESULTS_DIR/$CANDIDATE-$mode.$extension"; then
        result="identical ($(wc -l <"$RESULTS_DIR/$CANDIDATE-$mode.$extension" | tr -d ' ') lines)"
        echo "$label: $result"
    else
        result="DIFFERENT ($(diff "$RESULTS_DIR/$BASELINE-$mode.$extension" "$RESULTS_DIR/$CANDIDATE-$mode.$extension" | grep -c '^[<>]') changed lines)"
        echo "$label: $result, first differences:"
        diff "$RESULTS_DIR/$BASELINE-$mode.$extension" "$RESULTS_DIR/$CANDIDATE-$mode.$extension" | head -10
    fi
    summary_row "Same results: $label" "$result" ""
}

{
    echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "system: $(uname -srm), CPUs: $(cpu_count), load average: $(load_average)"
    echo "java: $( ("${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 || true) | head -1)"
    if [ -d "$PROJECT_DIR/.git" ]; then
        echo "project: $PROJECT_DIR ($(git -C "$PROJECT_DIR" rev-parse --short HEAD))"
    else
        echo "project: $PROJECT_DIR"
    fi
} >"$RESULTS_DIR/environment.txt"
cat "$RESULTS_DIR/environment.txt"

echo
echo "== Same results"
compare_results "queries" dump tsv
compare_results "type resolution" resolve tsv "$RESOLVE_CHILDREN"

echo
echo "== Retained heap after suite (-Xmx$RETAINED_HEAP)"
for variant in "$BASELINE" "$CANDIDATE"; do
    run "$variant" "$PROJECT_DIR" "$RETAINED_HEAP" suite --histogram "$RESULTS_DIR/$variant-histogram.txt" \
        >"$RESULTS_DIR/$variant-suite.txt" 2>&1
    echo "$variant: $(grep -E '^CHECKPOINT suite' "$RESULTS_DIR/$variant-suite.txt" | head -1)"
    grep -E '^ENV' "$RESULTS_DIR/$variant-suite.txt" >"$RESULTS_DIR/$variant-environment.txt"
done
retained() {
    grep -E '^CHECKPOINT suite' "$RESULTS_DIR/$1-suite.txt" | head -1 | sed -E 's/.*usedAfterGcMb=([0-9.]+).*/\1 MB/'
}
summary_row "Heap retained after the suite (-Xmx$RETAINED_HEAP)" "$(retained "$BASELINE")" "$(retained "$CANDIDATE")"
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

if [ "$MODULES" = true ]; then
    echo
    echo "== Module scopes created one after another (-Xmx$RETAINED_HEAP)"
    for variant in "$BASELINE" "$CANDIDATE"; do
        run "$variant" "$PROJECT_DIR" "$RETAINED_HEAP" modules >"$RESULTS_DIR/$variant-modules.txt" 2>&1
        echo "$variant: $(grep -E '^COUNT' "$RESULTS_DIR/$variant-modules.txt")"
    done
fi
if [ "$MODULES" = true ] && ! grep -q "^CHECKPOINT module" "$RESULTS_DIR/$CANDIDATE-modules.txt"; then
    echo "no modules found (directories with a build file or a src directory)"
    summary_row "Module scopes" "no modules found" ""
elif [ "$MODULES" = true ]; then
    paste <(grep "^CHECKPOINT module" "$RESULTS_DIR/$BASELINE-modules.txt" | awk '{print $3, $4}') \
        <(grep "^CHECKPOINT module" "$RESULTS_DIR/$CANDIDATE-modules.txt" | awk '{print $4}') |
        awk -v b="$BASELINE" -v c="$CANDIDATE" 'BEGIN {printf "%-56s %16s %16s\n", "heap after module", b, c}
            {gsub("usedAfterGcMb=", "", $2); gsub("usedAfterGcMb=", "", $3); printf "%-56s %13s MB %13s MB\n", $1, $2, $3}'
    last_module_heap() {
        grep "^CHECKPOINT module" "$RESULTS_DIR/$1-modules.txt" | tail -1 | sed -E 's/.*usedAfterGcMb=([0-9.]+).*/\1 MB/'
    }
    summary_row "Heap after the last module scope (-Xmx$RETAINED_HEAP)" "$(last_module_heap "$BASELINE")" "$(last_module_heap "$CANDIDATE")"

    if [ "$MIN_HEAP" = true ]; then
        echo
        echo "== Smallest -Xmx that completes the module scopes"
        for variant in "$BASELINE" "$CANDIDATE"; do
            result=$("$ROOT_DIR/scripts/min-heap.sh" "$variant" "$PROJECT_DIR" 16 4096 8 600 modules \
                2>>"$RESULTS_DIR/$variant-modules-min-heap.log")
            echo "$variant: $result"
            eval "MODULES_MIN_HEAP_${variant//[^a-zA-Z0-9]/_}=\"${result#MIN_HEAP }\""
        done
        b="MODULES_MIN_HEAP_${BASELINE//[^a-zA-Z0-9]/_}"
        c="MODULES_MIN_HEAP_${CANDIDATE//[^a-zA-Z0-9]/_}"
        summary_row "Smallest -Xmx, module scopes" "${!b}" "${!c}"
    fi
fi

if [ "$MIN_HEAP" = true ]; then
    echo
    echo "== Smallest -Xmx that completes the suite"
    for variant in "$BASELINE" "$CANDIDATE"; do
        result=$("$ROOT_DIR/scripts/min-heap.sh" "$variant" "$PROJECT_DIR" 2>>"$RESULTS_DIR/$variant-min-heap.log")
        echo "$variant: $result"
        eval "MIN_HEAP_${variant//[^a-zA-Z0-9]/_}=\"${result#MIN_HEAP }\""
    done
    b="MIN_HEAP_${BASELINE//[^a-zA-Z0-9]/_}"
    c="MIN_HEAP_${CANDIDATE//[^a-zA-Z0-9]/_}"
    summary_row "Smallest -Xmx, suite" "${!b}" "${!c}"
fi

if [ "$TIMING_RUNS" -gt 0 ]; then
    echo
    echo "== Suite time in new JVMs (-Xmx$TIMING_HEAP, $TIMING_RUNS runs per variant, run alternately)"
    : >"$RESULTS_DIR/timing.txt"
    for i in $(seq 1 "$TIMING_RUNS"); do
        for variant in "$BASELINE" "$CANDIDATE"; do
            times=$({ /usr/bin/time -p "$ROOT_DIR/scripts/run.sh" "$variant" "$PROJECT_DIR" "$TIMING_HEAP" suite --no-gc >/dev/null; } 2>&1)
            real=$(echo "$times" | awk '/^real/ {print $2}')
            cpu=$(echo "$times" | awk '/^user/ {u = $2} /^sys/ {s = $2} END {print u + s}')
            echo "$variant $real $cpu $(load_average)" >>"$RESULTS_DIR/timing.txt"
        done
    done
    python3 - "$RESULTS_DIR/timing.txt" "$SUMMARY_ROWS" "$BASELINE" "$CANDIDATE" <<'EOF'
import statistics
import sys

runs = {}
loads = []
for line in open(sys.argv[1]):
    variant, real, cpu, load = line.split()
    runs.setdefault(variant, []).append((float(real), float(cpu)))
    loads.append(float(load))
medians = {}
for variant, values in runs.items():
    real = statistics.median(v[0] for v in values)
    cpu = statistics.median(v[1] for v in values)
    medians[variant] = (real, cpu)
    print(f"{variant}: median wall time {real:.1f} s (min {min(v[0] for v in values):.1f} s), "
          f"median CPU time {cpu:.1f} s (min {min(v[1] for v in values):.1f} s)")
print(f"load average during the runs: {min(loads):.1f}-{max(loads):.1f}")
with open(sys.argv[2], "a") as summary:
    baseline, candidate = sys.argv[3], sys.argv[4]
    summary.write(f"Suite wall time, new JVM (median)\t{medians[baseline][0]:.1f} s\t{medians[candidate][0]:.1f} s\n")
    summary.write(f"Suite CPU time, new JVM (median)\t{medians[baseline][1]:.1f} s\t{medians[candidate][1]:.1f} s\n")
EOF
    warn_if_loaded
fi

if [ "$WARM_RUNS" -gt 0 ]; then
    echo
    echo "== Suite after warm-up (-Xmx$TIMING_HEAP, 3 warm-up runs, $WARM_RUNS measured runs per JVM, 2 JVMs per variant)"
    for round in 1 2; do
        for variant in "$BASELINE" "$CANDIDATE"; do
            run "$variant" "$PROJECT_DIR" "$TIMING_HEAP" suite --no-gc --warmup 3 --runs "$WARM_RUNS" \
                >"$RESULTS_DIR/$variant-warm-$round.txt" 2>&1
        done
    done
    python3 - "$RESULTS_DIR" "$SUMMARY_ROWS" "$BASELINE" "$CANDIDATE" <<'EOF'
import re
import statistics
import sys

results_dir, summary_file, baseline, candidate = sys.argv[1:5]
metrics = {}
for variant in (baseline, candidate):
    runs = []
    for round_ in (1, 2):
        for line in open(f"{results_dir}/{variant}-warm-{round_}.txt"):
            if line.startswith("RUN "):
                runs.append({key: float(value) for key, value in re.findall(r"(\w+)=([0-9.]+)", line)})
    metrics[variant] = {key: statistics.median(run[key] for run in runs) for key in runs[0]}
    values = metrics[variant]
    print(f"{variant}: median wall {values['wall']:.2f} s, query CPU {values['queryCpu']:.2f} s, "
          f"JVM CPU {values['cpu']:.2f} s, GC {values['gc']:.2f} s, JIT {values['jit']:.2f} s, "
          f"query allocations {values['queryAllocMb']:.0f} MB ({len(runs)} runs)")
with open(summary_file, "a") as summary:
    b, c = metrics[baseline], metrics[candidate]
    summary.write(f"Suite wall time after warm-up (median)\t{b['wall']:.2f} s\t{c['wall']:.2f} s\n")
    summary.write(f"Suite query thread CPU time after warm-up (median)\t{b['queryCpu']:.2f} s\t{c['queryCpu']:.2f} s\n")
    summary.write(f"Suite query thread allocations after warm-up (median)\t{b['queryAllocMb']:.0f} MB\t{c['queryAllocMb']:.0f} MB\n")
EOF
    warn_if_loaded
fi

if [ "$SCENARIOS" = true ]; then
    echo
    echo "== Scenarios (-Xmx$TIMING_HEAP, budget $SCENARIO_BUDGET s per scenario)"
    for variant in "$BASELINE" "$CANDIDATE"; do
        run "$variant" "$PROJECT_DIR" "$TIMING_HEAP" scenarios "$SCENARIO_BUDGET" --no-gc \
            >"$RESULTS_DIR/$variant-scenarios.txt" 2>&1
    done
    python3 - "$RESULTS_DIR" "$SUMMARY_ROWS" "$BASELINE" "$CANDIDATE" <<'EOF'
import re
import sys

results_dir, summary_file, baseline, candidate = sys.argv[1:5]
PATTERN = re.compile(r"^SCENARIO (.+) items=(\d+)/(\d+) result=(\d+) t=([0-9.]+)s cpu=\S+( \(budget exceeded\))?")


SKIPPED = re.compile(r"^SCENARIO (.+) skipped")


def read(variant):
    scenarios = {}
    for line in open(f"{results_dir}/{variant}-scenarios.txt"):
        match = PATTERN.match(line)
        skipped = SKIPPED.match(line)
        if match:
            name, done, total, result, seconds, exceeded = match.groups()
            text = f"{float(seconds):.2f} s" if not exceeded else f">{float(seconds):.0f} s ({done}/{total} items)"
            scenarios[name] = (text, result if not exceeded else None)
        elif skipped:
            scenarios[skipped.group(1)] = ("skipped", None)
    return scenarios


base, cand = read(baseline), read(candidate)
names = list(base) + [name for name in cand if name not in base]
print(f"{'scenario':48s} {baseline:>24s} {candidate:>24s}")
with open(summary_file, "a") as summary:
    for name in names:
        b, c = base.get(name, ("missing", None)), cand.get(name, ("missing", None))
        mismatch = " (results differ)" if b[1] and c[1] and b[1] != c[1] else ""
        print(f"{name:48s} {b[0]:>24s} {c[0]:>24s}{mismatch}")
        summary.write(f"Scenario: {name}{mismatch}\t{b[0]}\t{c[0]}\n")
EOF
fi

python3 - "$SUMMARY_ROWS" "$RESULTS_DIR/summary.md" "$BASELINE" "$CANDIDATE" "$PROJECT_NAME" <<'EOF'
import sys

rows_file, summary_file, baseline, candidate, project = sys.argv[1:6]
lines = [f"Project: {project}", "", f"| Metric | {baseline} | {candidate} |", "|---|---:|---:|"]
for line in open(rows_file):
    metric, base, cand = line.rstrip("\n").split("\t")
    if not cand:
        lines.append(f"| {metric} | {base} | |")
    else:
        lines.append(f"| {metric} | {base} | {cand} |")
with open(summary_file, "w") as summary:
    summary.write("\n".join(lines) + "\n")
EOF
rm -f "$SUMMARY_ROWS"

if [ "$MARKDOWN" = true ]; then
    echo
    echo "== Summary (results/$PROJECT_NAME/summary.md)"
    cat "$RESULTS_DIR/summary.md"
fi
