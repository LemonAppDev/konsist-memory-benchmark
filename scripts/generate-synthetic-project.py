#!/usr/bin/env python3
"""Generates a synthetic Kotlin project with deep and diamond-shaped class hierarchies, where queries following the
hierarchy (indirect parents, children, type resolution) get slow when their time grows faster than linearly.

Usage: scripts/generate-synthetic-project.py <output-dir> [--chain-depth N] [--diamond-depth N] [--classes N] [--packages N]
  --chain-depth     length of the class chain C0 <- C1 <- ... (default 18)
  --diamond-depth   levels of the interface lattice; every interface extends both interfaces of the level above,
                    so the number of paths to the top doubles with every level (default 8)
  --classes         leaf classes extending the chain and the lattice, one file each (default 2000)
  --packages        packages the leaf classes are spread over (default 20)

The project is deterministic, so results of different Konsist versions can be compared.
"""
import argparse
import os
import shutil


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as file:
        file.write(text)


def main():
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("output")
    parser.add_argument("--chain-depth", type=int, default=18)
    parser.add_argument("--diamond-depth", type=int, default=8)
    parser.add_argument("--classes", type=int, default=2000)
    parser.add_argument("--packages", type=int, default=20)
    args = parser.parse_args()

    output = args.output
    if os.path.exists(output):
        shutil.rmtree(output)

    source = os.path.join(output, "src", "main", "kotlin", "synthetic")

    # Konsist uses the directory containing 'gradlew' file as the project root
    write(os.path.join(output, "gradlew"), "")

    chain = ["package synthetic.chain", "", "open class Chain0"]
    chain += [f"open class Chain{level} : Chain{level - 1}()" for level in range(1, args.chain_depth + 1)]
    write(os.path.join(source, "chain", "Chain.kt"), "\n".join(chain) + "\n")

    lattice = ["package synthetic.diamond", "", "interface Diamond0A", "", "interface Diamond0B"]
    for level in range(1, args.diamond_depth + 1):
        for name in ("A", "B"):
            lattice += ["", f"interface Diamond{level}{name} : Diamond{level - 1}A, Diamond{level - 1}B"]
    write(os.path.join(source, "diamond", "Diamond.kt"), "\n".join(lattice) + "\n")

    for index in range(args.classes):
        package = f"p{index % args.packages}"
        chain_level = index % (args.chain_depth + 1)
        diamond_level = index % (args.diamond_depth + 1)
        diamond = "A" if index % 2 == 0 else "B"
        # Reference to a class of the same package, resolved by searching the project (not the classpath)
        previous = max(index - args.packages, 0) if index >= args.packages else index
        text = f"""package synthetic.{package}

import synthetic.chain.Chain{chain_level}
import synthetic.diamond.Diamond{diamond_level}{diamond}

class Leaf{index} : Chain{chain_level}(), Diamond{diamond_level}{diamond} {{
    val previous: Leaf{previous}? = null

    fun next(input: Leaf{previous}): Chain{chain_level}? = null
}}
"""
        write(os.path.join(source, package, f"Leaf{index}.kt"), text)

    print(f"synthetic: {args.classes + 2} Kotlin files (chain depth {args.chain_depth}, diamond depth {args.diamond_depth})")


if __name__ == "__main__":
    main()
