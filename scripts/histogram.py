#!/usr/bin/env python3
"""Summarizes a class histogram (written by the --histogram option) by object category.

Usage: scripts/histogram.py <histogram-file> [top-classes]
  top-classes   number of the biggest classes to print (default 20)
"""
import re
import sys
from collections import defaultdict

ROW = re.compile(r"^\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)")

CATEGORIES = [
    ("Lazy delegates + not invoked lambdas", lambda n: n == "kotlin.SynchronizedLazyImpl" or (n.startswith("com.lemonappdev") and "$$Lambda" in n)),
    ("Declaration cache", lambda n: n in ("kotlin.Pair", "com.lemonappdev.konsist.core.cache.KoDeclarationCache$Entry",
                                          "org.jetbrains.kotlin.com.intellij.util.keyFMap.OneElementFMap") or "java.util.concurrent.ConcurrentHashMap" in n),
    ("Konsist declarations", lambda n: n.startswith("com.lemonappdev")),
    ("FqName / Name", lambda n: n.startswith("org.jetbrains.kotlin.name.")),
    ("PSI tree nodes + PSI elements", lambda n: n.startswith(("org.jetbrains.kotlin.com.intellij.psi.impl.source", "org.jetbrains.kotlin.psi.", "org.jetbrains.kotlin.kdoc."))),
    ("Text (String / byte[])", lambda n: n in ("[B", "[C", "java.lang.String")),
]


def category(name):
    return next((label for label, matches in CATEGORIES if matches(name)), "Other")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    top = int(sys.argv[2]) if len(sys.argv) > 2 else 20
    sizes = defaultdict(int)
    counts = defaultdict(int)
    classes = []

    with open(sys.argv[1]) as histogram:
        for line in histogram:
            match = ROW.match(line)
            if not match:
                continue
            count, size, name = int(match.group(1)), int(match.group(2)), match.group(3).split(" (")[0]
            classes.append((size, count, name))
            sizes[category(name)] += size
            counts[category(name)] += count

    total = sum(sizes.values())
    print(f"{'Total':40s} {total / 1048576:9.1f} MB")
    for label, size in sorted(sizes.items(), key=lambda item: -item[1]):
        print(f"  {label:38s} {size / 1048576:9.1f} MB {100 * size / total:5.1f}% {counts[label]:>12,d} objects")

    if top:
        print()
        for size, count, name in sorted(classes, reverse=True)[:top]:
            print(f"  {size / 1048576:9.1f} MB {count:>12,d}  {name}")


if __name__ == "__main__":
    main()
