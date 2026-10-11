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
| Module scopes | Heap after scopes of the biggest modules created one after another (with type resolution) and their smallest `-Xmx`. Shows whether Konsist releases files of previous modules and parses only files it needs |
| Memory leak | Heap after creating scope from the same directory repeatedly (heap should stay flat) |
| Time | Wall time and CPU time of the suite in a new JVM |
| Time after warm-up | Wall time, CPU time of the query thread, GC and JIT time and allocations of suite runs after warm-up runs in the same JVM (stable even when JIT time varies) |
| Slow queries | Time of queries that were slow in some Konsist versions (indirect parents, children, type resolution, ...), each stopped after a time budget |
| Same results | Query and type resolution results of two Konsist versions compared line by line (behavior did not change) |

## Requirements

- JDK 17+ (results in Konsist PR #1960 were measured with JDK 25)
- `git`, `rsync`, `python3`, `perl`
- `jfr` tool of the JDK (in `$JAVA_HOME/bin` or on `PATH`) for `scripts/jfr-summary.py`
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
scripts/compare.sh release develop projects/ktor --min-heap --modules --warm 5 --scenarios --markdown
```

Example output of `scripts/compare.sh develop pr-1975 projects/ktor --min-heap --modules --scenarios` (Konsist
`develop` vs Konsist PR #1975, shortened):

```
== Same results
queries: identical (134889 lines)
develop: RESULT OK t=62.4s
pr-1975: RESULT OK t=4.6s
type resolution: DIFFERENT (16 changed lines), first differences:
3512c3512
< import	.../ktor/client/plugins/SaveBody.kt	11	kotlin.jvm.JvmName	null
---
> import	.../ktor/client/plugins/SaveBody.kt	11	kotlin.jvm.JvmName	kotlin:kotlin.jvm.JvmName

== Retained heap after suite (-Xmx16g)
develop: CHECKPOINT suite usedAfterGcMb=154.6 t=3.0s
pr-1975: CHECKPOINT suite usedAfterGcMb=154.5 t=3.3s
...

== Module scopes created one after another (-Xmx16g)
develop: COUNT resolved types 11287
pr-1975: COUNT resolved types 11287
heap after module                                                 develop          pr-1975
ktor-server/ktor-server-core                                     113.9 MB          36.3 MB
ktor-utils                                                       114.7 MB          38.7 MB
...
ktor-server/ktor-server-plugins/ktor-server-sessions             120.0 MB          83.7 MB

== Smallest -Xmx that completes the module scopes
develop: MIN_HEAP 112m
pr-1975: MIN_HEAP 32m

== Smallest -Xmx that completes the suite
develop: MIN_HEAP 160m
pr-1975: MIN_HEAP 160m

== Scenarios (-Xmx4g, budget 60 s per scenario)
scenario                                                          develop                  pr-1975
parents().sourceDeclaration                                        0.45 s                   0.34 s
import sourceDeclaration                                          59.82 s                   0.04 s (results differ)
children()                                                         3.33 s                   0.11 s
children(indirectChildren = true)                                  8.88 s                   0.00 s
...
```

## Benchmarked Projects

`scripts/setup-projects.sh` clones the projects into the `projects` directory:

| Project | Kotlin files | Kotlin code | Source |
|---|---:|---:|---|
| `projects/ktor` | 2,435 | 10 MB | [ktorio/ktor](https://github.com/ktorio/ktor) at `4717dd5` |
| `projects/kotlin-compiler` | 6,802 | 38 MB | `compiler` directory of JetBrains/kotlin without test data |
| `projects/kotlin` | 67,000+ | 146 MB | [JetBrains/kotlin](https://github.com/JetBrains/kotlin) at `80437e6`, incl. test data (useful for the same results check) |
| `projects/konsist` | 1,733 | 7 MB | [LemonAppDev/konsist](https://github.com/LemonAppDev/konsist) at `26d7b4a`: deep interface hierarchies (providers) and many `@Suppress` annotations |
| `projects/synthetic` | 2,002 | 0.4 MB | Generated by `scripts/generate-synthetic-project.py`: chain of 18 classes, lattice of interfaces 8 levels deep (every interface extends both interfaces of the level above), 2,000 classes in 20 packages extending them |

Any other project can be benchmarked too. Konsist creates the project scope from the working directory: it uses the
nearest directory (current or parent) containing `gradlew`, `mvnw` or `.git` as the project root.

## Benchmark Modes

`scripts/run.sh <variant> <project-dir> <max-heap> <mode> [mode arguments] [options]`

| Mode | Description |
|---|---|
| `suite` | Typical Konsist test suite: 20 rules (classes, interfaces, objects, functions, properties, imports, annotations, files, parents, parameter types, KDoc, `declarations()`, architecture). Every rule calls `Konsist.scopeFromProject()`, like separate tests. Some rules fail on purpose (e.g. not every function has KDoc), which exercises failure messages |
| `steps` | Runs queries one by one and prints heap retained after each of them (values are cumulative, because Konsist caches declarations) |
| `directory <path> [count]` | Creates scope from the same directory (relative to the project root) repeatedly, 5 times by default |
| `modules [count \| path...]` | Creates scopes of the 10 (or `count`) biggest modules, or of the given directories, one after another and resolves source declarations of parents and of parameter, return and property types, like test classes checking one module each. No project scope is created. A module is a leaf directory with a Gradle or Maven build file (or with a `src` directory when the project has no build files) |
| `scenarios [budget-sec]` | Runs queries that were slow in some Konsist versions: source declarations of parents, types and imports, `assertTrue` on all declarations, `children()`, `parents(indirectParents = true)` and `children(indirectChildren = true)`. Every scenario stops after the time budget (60 s by default); when the budget is exceeded during a single query, the remaining scenarios are skipped |
| `dump <file>` | Writes results of common queries for every file (one line per declaration), so results of two Konsist versions can be compared with `cmp` or `diff` |
| `resolve <file> [children]` | Writes type resolution results for every file: fully qualified names, direct and indirect parents, source declarations of parameter, return and property types, imports and annotations, and children of the 10 (or `children`) most common parents. Compare two Konsist versions with `cmp` or `diff` |

| Option | Description |
|---|---|
| `--histogram <file>` | Writes class histogram of the heap at the end (summarize it with `scripts/histogram.py`) |
| `--no-gc` | Does not run full GC at checkpoints (use when measuring time or searching for the smallest heap) |
| `--warmup <count>` | Runs `suite`, `modules` or `scenarios` the given number of times before the measured runs |
| `--runs <count>` | Number of measured runs of `suite`, `modules` or `scenarios` (1 by default) |
| `--jfr <file>` | Records CPU samples of the measured runs to a JFR file (summarize it with `scripts/jfr-summary.py`) |

Output lines:

```
ENV variant=<variant> konsist=<version> kotlinCompiler=<version> java=<version> os=<os> cpus=<count> maxHeapMb=<-Xmx> load=<load average>
CHECKPOINT <label> usedAfterGcMb=<heap used after full GC> t=<seconds since start>
RUN <index> wall=<s> queryCpu=<s> cpu=<s> gc=<s> jit=<s> queryAllocMb=<MB>
RUNS median wall=<s> queryCpu=<s> cpu=<s> gc=<s> jit=<s> queryAllocMb=<MB>
SCENARIO <name> items=<processed>/<total> result=<sum of results> t=<s> cpu=<s> [(budget exceeded)]
LOAD end=<load average>
WARNING load average <load> is higher than the number of CPUs (<count>), timing is not reliable
RESULT OK | RESULT OOM | RESULT ERROR <exception>
```

`RUN` metrics: `queryCpu` and `queryAllocMb` are CPU time and allocations of the thread running the queries (files
parsed in parallel and scenario queries, which run in a separate thread, are not included), `cpu` is CPU time of the
whole JVM, including JIT compiler and GC threads.

Exit code is `0` on success, `3` on `OutOfMemoryError` and `1` on other errors.

## Scripts

| Script | Description |
|---|---|
| `scripts/setup-projects.sh [ktor] [kotlin] [konsist] [synthetic]` | Clones (or generates) benchmarked projects (all by default) |
| `scripts/build-konsist-jar.sh <konsist-repo-dir> <git-ref> <name>` | Builds Konsist jar from a branch, tag or commit of a local Konsist clone into `konsist-jars/<name>.jar` (in a temporary git worktree, so the Konsist working tree stays untouched) |
| `scripts/install.sh <variant> <konsist-version\|konsist-jar> [kotlin-compiler-version]` | Installs the benchmark with Konsist version from Maven Central (or Maven Local) or with Konsist jar into `build/install/<variant>`, optionally with the given `kotlin-compiler-embeddable` version |
| `scripts/run.sh <variant> <project-dir> <max-heap> <mode> ...` | Runs the benchmark (extra JVM options can be passed in `BENCHMARK_JAVA_OPTS`) |
| `scripts/min-heap.sh <variant> <project-dir> [low-mb] [high-mb] [step-mb] [timeout-sec] [mode arguments]` | Finds the smallest `-Xmx` that completes the suite (or another mode, e.g. `modules`) by binary search |
| `scripts/compare.sh <baseline> <candidate> <project-dir> [--min-heap] [--modules] [--timing <runs>] [--warm <runs>] [--scenarios] [--markdown]` | Compares two variants: same results (queries and type resolution), retained heap, histogram, retained heap per query, module scopes (`--modules`), smallest heap (`--min-heap`), time in new JVMs (`--timing`) and after warm-up (`--warm`), slow queries (`--scenarios`). Results, environment and the `summary.md` table (`--markdown` prints it) are stored in `results/<project>`. Environment variables: `RESOLVE_CHILDREN` (children sample, 10), `RESULTS_TIMEOUT` (same results run timeout, 900 s), `SCENARIO_BUDGET` (60 s), `RETAINED_HEAP` (16g), `TIMING_HEAP` (4g) |
| `scripts/histogram.py <histogram-file> [top-classes]` | Summarizes heap histogram by object category |
| `scripts/jfr-summary.py <recording.jfr> [top-methods]` | Summarizes a recording of the `--jfr` option: CPU time by thread kind (query thread, JIT compiler, parsing workers, ...), methods using the most CPU time and Konsist methods by total time |
| `scripts/jfr-retention-paths.py <old-objects.txt> <class>` | Shows which objects keep instances of the class in memory (reference chains from a JFR recording, see the script for recording steps) |
| `scripts/generate-synthetic-project.py <output-dir> [options]` | Generates the synthetic project (deep and diamond-shaped hierarchies); options change the depth and size |

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

Module scopes one after another (heap after each module) and their smallest heap:

```bash
scripts/run.sh develop projects/ktor 4g modules
scripts/min-heap.sh develop projects/ktor 16 1024 8 600 modules
```

Same type resolution results (release vs branch):

```bash
scripts/run.sh release projects/ktor 8g resolve results/release-resolve.tsv --no-gc
scripts/run.sh develop projects/ktor 8g resolve results/develop-resolve.tsv --no-gc
diff results/release-resolve.tsv results/develop-resolve.tsv
```

Where the CPU time goes (runs after warm-up, deeper stacks show more Konsist callers):

```bash
BENCHMARK_JAVA_OPTS="-XX:FlightRecorderOptions=stackdepth=256" \
    scripts/run.sh develop projects/ktor 4g suite --no-gc --warmup 3 --runs 5 --jfr results/suite.jfr
scripts/jfr-summary.py results/suite.jfr
```

Slow queries on deep hierarchies:

```bash
scripts/run.sh release projects/synthetic 4g scenarios 30 --no-gc
```

Konsist with another Kotlin compiler version (Konsist users get `kotlin-compiler-embeddable` aligned with their Kotlin
version):

```bash
scripts/install.sh develop-kotlin-2.3 konsist-jars/develop.jar 2.3.21
scripts/compare.sh develop develop-kotlin-2.3 projects/ktor --warm 5
```

## Notes

- **Smallest `-Xmx` is the most reliable metric.** Retained heap measured with a big heap (`-Xmx16g`) includes caches
  referenced by soft references, which JVM clears only when memory runs low.
- **Retained heap includes everything Konsist cached so far.** Later checkpoints include memory of earlier queries
  (unless Konsist releases cached files when memory runs low).
- **The suite can't show some memory gains.** Every rule uses the whole project scope and `declarations()` needs every
  parsed file at once, so releasing unused files or parsing fewer files doesn't change its smallest `-Xmx`. Use
  `--modules` (module scopes one after another, like most real test suites).
- **Time of a single run is mostly JIT compilation.** On ktor about 40% of the CPU time of a suite run is the JIT
  compiler, and JIT time of the same version varied from 3.4 s to 8.4 s between runs. Compare runs after warm-up
  (`--warm`), especially CPU time of the query thread and allocations, which vary the least.
- **Time depends on machine load.** Other processes (e.g. builds or other agents) make CPU and wall time noisy. The
  benchmark prints the load average (`ENV`, `LOAD`) and `compare.sh` warns when the machine is loaded. Run variants
  alternately (`compare.sh` does).
- **Results depend on JDK, GC and Kotlin compiler version.** Benchmark uses the default GC (G1), the same as Gradle test
  workers. Konsist runs with the `kotlin-compiler-embeddable` version of the project using it (`install.sh` sets it).
- **Earlier queries change later ones.** Konsist caches parsed files and declarations, and parsing function bodies
  (e.g. by `declarations()`) turns the text of the file into a rope (`ImmutableText`, 246 of 2,435 ktor files) with
  slow access to single characters. Compare variants with the same sequence of queries (the suite and scenarios run
  in a fixed order).
- **Some queries are exponential in some Konsist versions** (e.g. indirect parents on deep diamond hierarchies), so
  `scenarios` has a time budget and `compare.sh` stops the same results runs after `RESULTS_TIMEOUT`.

## Results

Konsist `develop` (2026-10-10) vs [Konsist PR #1960](https://github.com/LemonAppDev/konsist/pull/1960)
(JDK 25, G1, Apple M3 Max):

| Metric | ktor<br>before | ktor<br>after | Kotlin compiler<br>before | Kotlin compiler<br>after |
|---|---:|---:|---:|---:|
| Smallest `-Xmx` that completes the suite | 224 MB | 160 MB (−29%) | 928 MB | 640 MB (−31%) |
| Heap retained after the suite | 216 MB | 151 MB (−30%) | 942 MB | 619 MB (−34%) |
| Suite CPU time (median of 5 runs) | 13.6 s | 12.4 s (−9%) | 58.7 s | 51.1 s (−13%) |

Konsist `develop` (2026-10-11, `9aea7a2`) vs [Konsist PR #1975](https://github.com/LemonAppDev/konsist/pull/1975)
(JDK 25, G1, Apple M3 Max):

| Metric | Before | After |
|---|---:|---:|
| Smallest `-Xmx`, 10 biggest ktor module scopes (`modules`) | 112 MB | 32 MB (−71%) |
| Heap after the first ktor module scope (`ktor-server-core`) | 113.9 MB | 36.3 MB (−68%) |
| Smallest `-Xmx`, suite (ktor and Kotlin compiler sources) | 160 MB / 640 MB | 160 MB / 640 MB |
| Type resolution results of ktor (`resolve`) | 62–78 s | 4–5 s |
| `sourceDeclaration` of 15,301 ktor imports (`scenarios`) | 59.8 s | 0.04 s |
| `children()` / `children(indirectChildren = true)` of 3,368 ktor classes and interfaces | 3.3 s / 8.9 s | 0.11 s / <0.01 s |
| `parents(indirectParents = true)` of 2,037 classes of `projects/synthetic` | >10 s (stopped after 66 classes) | 0.04 s |
| Memory allocated by the queries per suite run after warm-up (ktor) | 1,016–1,032 MB | 912–913 MB (−11%) |

Query results are identical, type resolution results differ in 8 of 15,301 ktor imports (`kotlin.jvm.JvmName` and
`kotlin.jvm.JvmMultifileClass` resolve to Kotlin types instead of `null`). Wall time of the suite after warm-up is the
same within noise.

## License

[Apache License 2.0](LICENSE), the same as Konsist.
