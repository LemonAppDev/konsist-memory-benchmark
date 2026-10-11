#!/usr/bin/env python3
"""Shows which objects keep sampled objects in memory (reference chains to GC roots) from a JFR recording.

Record (old object samples with paths to GC roots):
  BENCHMARK_JAVA_OPTS="-XX:StartFlightRecording=settings=profile,path-to-gc-roots=true,filename=$PWD/results/recording.jfr \
    -XX:FlightRecorderOptions=old-object-queue-size=2048" scripts/run.sh <variant> <project-dir> 8g suite
  jfr print --events jdk.OldObjectSample results/recording.jfr > results/old-objects.txt

Usage: scripts/jfr-retention-paths.py <old-objects.txt> <class-name-part> [chain-length]
  Example: scripts/jfr-retention-paths.py results/old-objects.txt FqName 6
"""
import sys
from collections import Counter


def read_samples(path):
    samples, chain, in_object = [], None, False
    for line in open(path):
        text = line.strip()
        if text.startswith("jdk.OldObjectSample"):
            chain = []
        elif text.startswith("object =  ["):
            in_object = True
        elif in_object and text == "]":
            in_object = False
            samples.append(chain)
        elif in_object:
            chain.append(text.split(" Class Name")[0])
    return samples


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)

    samples = read_samples(sys.argv[1])
    target = sys.argv[2]
    length = int(sys.argv[3]) if len(sys.argv) > 3 else 5

    print("Sampled classes:")
    for name, count in Counter(chain[0].split(" ")[0] for chain in samples if chain).most_common(15):
        print(f"  {count:5d} {name}")

    print(f"\nReference chains of '{target}' (object <- referenced by):")
    chains = Counter(" <- ".join(chain[: length + 1]) for chain in samples if chain and target in chain[0])
    for chain, count in chains.most_common(15):
        print(f"  {count:5d} {chain}")


if __name__ == "__main__":
    main()
