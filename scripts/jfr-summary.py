#!/usr/bin/env python3
"""Summarizes CPU usage from a JFR recording written by the --jfr option: CPU time by thread kind (query thread,
JIT compiler, parsing workers, ...), methods using the most CPU time and Konsist methods by total time (including
the methods they call).

Record (only the measured runs are recorded, deeper stacks show more Konsist callers):
  BENCHMARK_JAVA_OPTS="-XX:FlightRecorderOptions=stackdepth=256" \\
    scripts/run.sh <variant> <project-dir> 4g suite --no-gc --warmup 3 --runs 5 --jfr results/suite.jfr

Usage: scripts/jfr-summary.py <recording.jfr> [top-methods]
  top-methods   number of methods to print in each list (default 20)

Requires the `jfr` tool of the JDK (found in $JAVA_HOME/bin or on PATH).
"""
import json
import os
import re
import shutil
import subprocess
import sys
from collections import Counter

KONSIST_PACKAGE = "com/lemonappdev/konsist/"


def find_jfr_tool():
    java_home = os.environ.get("JAVA_HOME")
    if java_home and os.path.exists(os.path.join(java_home, "bin", "jfr")):
        return os.path.join(java_home, "bin", "jfr")
    tool = shutil.which("jfr")
    if not tool:
        sys.exit("jfr tool not found, set JAVA_HOME or add the JDK bin directory to PATH")
    return tool


def read_events(recording, event_names):
    output = subprocess.run(
        [find_jfr_tool(), "print", "--json", "--events", ",".join(event_names), recording],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    return json.loads(output)["recording"]["events"]


def thread_kind(thread):
    name = (thread or {}).get("javaName") or (thread or {}).get("osName") or "unknown"
    # Group pool threads, e.g. "DefaultDispatcher-worker-12" or "C2 CompilerThread1"
    return re.sub(r"[-#\s]*\d+$", "", re.sub(r"-worker.*", "-worker", name))


def method_name(frame):
    method = frame["method"]
    return method["type"]["name"].replace("/", ".") + "." + method["name"]


def print_cpu_by_thread_kind(events):
    loads = Counter()
    for event in events:
        if event["type"] == "jdk.ThreadCPULoad":
            values = event["values"]
            loads[thread_kind(values.get("eventThread"))] += values["user"] + values["system"]

    total = sum(loads.values())
    if not total:
        return

    print("CPU time by thread kind")
    for kind, load in loads.most_common(8):
        print(f"  {100 * load / total:5.1f}%  {kind}")
    print()


def print_methods(events, top):
    self_samples = Counter()
    konsist_samples = Counter()
    samples = 0

    for event in events:
        if event["type"] != "jdk.ExecutionSample":
            continue
        frames = (event["values"].get("stackTrace") or {}).get("frames") or []
        if not frames:
            continue

        samples += 1
        self_samples[method_name(frames[0])] += 1

        for name in {method_name(frame) for frame in frames if frame["method"]["type"]["name"].startswith(KONSIST_PACKAGE)}:
            konsist_samples[name] += 1

    if not samples:
        print("No CPU samples (record with the --jfr option)")
        return

    print(f"Methods using the most CPU time ({samples} samples)")
    for name, count in self_samples.most_common(top):
        print(f"  {100 * count / samples:5.1f}%  {name}")

    print()
    print("Konsist methods by total time (including called methods)")
    for name, count in konsist_samples.most_common(top):
        print(f"  {100 * count / samples:5.1f}%  {name}")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    top = int(sys.argv[2]) if len(sys.argv) > 2 else 20
    events = read_events(sys.argv[1], ["jdk.ExecutionSample", "jdk.ThreadCPULoad"])
    print_cpu_by_thread_kind(events)
    print_methods(events, top)


if __name__ == "__main__":
    main()
