# Konsist Memory Benchmark

Measures memory usage and speed of [Konsist](https://github.com/LemonAppDev/konsist) on real Kotlin projects and
compares two Konsist versions, e.g. the latest release and a local branch.

The benchmark was created to investigate `java.lang.OutOfMemoryError: Java heap space` reported for large projects.
Results and optimizations: [Konsist PR #1960](https://github.com/LemonAppDev/konsist/pull/1960).

## What It Measures

| Metric | Description |
|---|---|
| Smallest `-Xmx` | Smallest max heap that completes a typical Konsist test suite, i.e. the smallest `maxHeapSize` of the Gradle `Test` task that does not fail with `OutOfMemoryError` |
| Retained heap | Heap used after the suite (after full GC) and heap histogram by object category (PSI, Konsist declarations, lazy delegates, ...) |
| Retained heap per query | Heap used after each query kind (`classes()`, `properties()`, `declarations()`, ...) |
| Memory leak | Heap after creating scope from the same directory repeatedly (heap should stay flat) |
| Time | Wall time and CPU time of the suite |
| Same results | Query results of two Konsist versions compared line by line (behavior did not change) |

## Requirements

- JDK 17+ (results in Konsist PR #1960 were measured with JDK 25)
- `git`, `rsync`, `python3`, `perl`
- ~1 GB of disk space for the benchmarked projects
- Building a Konsist jar from source (`scripts/build-konsist-jar.sh`) needs the same setup as the Konsist build

## Quick Start

```bash
# 1. Clone benchmarked projects (pinned commits)
scripts/setup-projects.sh

# 2. Install benchmark variants: Konsist release (Maven Central) and Konsist branch (built from local Konsist clone)
scripts/install.sh release 0.18.1
scripts/build-konsist-jar.sh ~/IdeaProjects/konsist develop develop
scripts/install.sh develop konsist-jars/develop.jar

# 3. Compare variants
scripts/compare.sh release develop projects/ktor --min-heap --timing 3
```

Example output of `scripts/compare.sh` (Konsist `develop` vs Konsist PR #1960, `projects/ktor`, shortened):

```
== Same results
identical (134889 result lines)

== Retained heap after suite (-Xmx16g)
develop: CHECKPOINT suite usedAfterGcMb=215.6 t=8.6s
pr-1960: CHECKPOINT suite usedAfterGcMb=151.5 t=5.7s
-- develop heap histogram
Total                                        216.1 MB
  PSI tree nodes + PSI elements              105.5 MB  48.8%    2,771,769 objects
  Lazy delegates + not invoked lambdas        39.9 MB  18.4%    2,037,776 objects
  Text (String / byte[])                      38.2 MB  17.7%      539,766 objects
  ...
-- pr-1960 heap histogram
Total                                        152.0 MB
  PSI tree nodes + PSI elements               95.1 MB  62.6%    2,440,107 objects
  Text (String / byte[])                      36.1 MB  23.8%      469,282 objects
  ...

== Retained heap per query (cumulative)
query                                     develop          pr-1960
classes(includeLocal=false)              126.0 MB         108.7 MB
properties()                             165.1 MB         110.6 MB
declarations()                           165.1 MB         136.9 MB
...

== Smallest -Xmx that completes the suite
develop: MIN_HEAP 224m
pr-1960: MIN_HEAP 160m

== Suite time (-Xmx4g, 3 runs per variant, run alternately)
develop: median wall time 5.6 s, median CPU time 13.9 s
pr-1960: median wall time 3.9 s, median CPU time 12.1 s
```

## Benchmarked Projects

`scripts/setup-projects.sh` clones the projects into the `projects` directory:

| Project | Kotlin files | Kotlin code | Source |
|---|---:|---:|---|
| `projects/ktor` | 2,435 | 10 MB | [ktorio/ktor](https://github.com/ktorio/ktor) at `4717dd5` |
| `projects/kotlin-compiler` | 6,802 | 38 MB | `compiler` directory of JetBrains/kotlin without test data |
| `projects/kotlin` | 67,000+ | 146 MB | [JetBrains/kotlin](https://github.com/JetBrains/kotlin) at `80437e6`, incl. test data (useful for the same results check) |

Any other project can be benchmarked too. Konsist creates the project scope from the working directory: it uses the
nearest directory (current or parent) containing `gradlew`, `mvnw` or `.git` as the project root.

## Benchmark Modes

`scripts/run.sh <variant> <project-dir> <max-heap> <mode> [mode arguments] [options]`

| Mode | Description |
|---|---|
| `suite` | Typical Konsist test suite: 20 rules (classes, interfaces, objects, functions, properties, imports, annotations, files, parents, parameter types, KDoc, `declarations()`, architecture). Every rule calls `Konsist.scopeFromProject()`, like separate tests. Some rules fail on purpose (e.g. not every function has KDoc), which exercises failure messages |
| `steps` | Runs queries one by one and prints heap retained after each of them (values are cumulative, because Konsist caches declarations) |
| `directory <path> [count]` | Creates scope from the same directory (relative to the project root) repeatedly, 5 times by default |
| `dump <file>` | Writes results of common queries for every file (one line per declaration), so results of two Konsist versions can be compared with `cmp` or `diff` |

| Option | Description |
|---|---|
| `--histogram <file>` | Writes class histogram of the heap at the end (summarize it with `scripts/histogram.py`) |
| `--no-gc` | Does not run full GC at checkpoints (use when measuring time or searching for the smallest heap) |

Output lines:

```
CHECKPOINT <label> usedAfterGcMb=<heap used after full GC> t=<seconds since start>
RESULT OK | RESULT OOM | RESULT ERROR <exception>
```

Exit code is `0` on success, `3` on `OutOfMemoryError` and `1` on other errors.

## Scripts

| Script | Description |
|---|---|
| `scripts/setup-projects.sh [ktor] [kotlin]` | Clones benchmarked projects (all by default) |
| `scripts/build-konsist-jar.sh <konsist-repo-dir> <git-ref> <name>` | Builds Konsist jar from a branch, tag or commit of a local Konsist clone into `konsist-jars/<name>.jar` (in a temporary git worktree, so the Konsist working tree stays untouched) |
| `scripts/install.sh <variant> <konsist-version\|konsist-jar>` | Installs the benchmark with Konsist version from Maven Central (or Maven Local) or with Konsist jar into `build/install/<variant>` |
| `scripts/run.sh <variant> <project-dir> <max-heap> <mode> ...` | Runs the benchmark (extra JVM options can be passed in `BENCHMARK_JAVA_OPTS`) |
| `scripts/min-heap.sh <variant> <project-dir> [low-mb] [high-mb] [step-mb] [timeout-sec]` | Finds the smallest `-Xmx` that completes the suite (binary search) |
| `scripts/compare.sh <baseline> <candidate> <project-dir> [--min-heap] [--timing <runs>]` | Compares two variants: same results, retained heap, histogram, retained heap per query, smallest heap and time. Results are stored in `results/<project>` |
| `scripts/histogram.py <histogram-file> [top-classes]` | Summarizes heap histogram by object category |
| `scripts/jfr-retention-paths.py <old-objects.txt> <class>` | Shows which objects keep instances of the class in memory (reference chains from a JFR recording, see the script for recording steps) |

## Examples

Smallest heap for the Kotlin compiler sources:

```bash
scripts/min-heap.sh develop projects/kotlin-compiler 256 2048 32
```

Which objects take the memory:

```bash
scripts/run.sh develop projects/kotlin-compiler 16g suite --histogram results/histogram.txt
scripts/histogram.py results/histogram.txt
```

Same results on 67,000+ files (Konsist release vs branch):

```bash
scripts/run.sh release projects/kotlin 24g dump results/release.tsv --no-gc
scripts/run.sh develop projects/kotlin 24g dump results/develop.tsv --no-gc
cmp results/release.tsv results/develop.tsv && echo "same results"
```

Memory leak of directory scopes:

```bash
scripts/run.sh develop projects/ktor 8g directory ktor-client/ktor-client-core 10
```

## Notes

- **Smallest `-Xmx` is the most reliable metric.** Retained heap measured with a big heap (`-Xmx16g`) includes caches
  referenced by soft references, which JVM clears only when memory runs low.
- **Retained heap includes everything Konsist cached so far.** Konsist keeps parsed files and created declarations for
  the whole process, so later checkpoints include memory of earlier queries.
- **Wall time depends on machine load.** Compare CPU time and run variants alternately (`scripts/compare.sh --timing`
  does both).
- **Results depend on JDK and GC.** Benchmark uses the default GC (G1), the same as Gradle test workers.

## Results

Konsist `develop` (2026-10-10) vs [Konsist PR #1960](https://github.com/LemonAppDev/konsist/pull/1960)
(JDK 25, G1, Apple M3 Max):

| Metric | ktor<br>before | ktor<br>after | Kotlin compiler<br>before | Kotlin compiler<br>after |
|---|---:|---:|---:|---:|
| Smallest `-Xmx` that completes the suite | 224 MB | 160 MB (−29%) | 928 MB | 640 MB (−31%) |
| Heap retained after the suite | 216 MB | 151 MB (−30%) | 942 MB | 619 MB (−34%) |
| Suite CPU time (median of 5 runs) | 13.6 s | 12.4 s (−9%) | 58.7 s | 51.1 s (−13%) |

## License

[Apache License 2.0](LICENSE), the same as Konsist.
