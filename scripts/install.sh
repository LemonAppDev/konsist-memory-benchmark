#!/usr/bin/env bash
# Installs the benchmark with the given Konsist version into build/install/<variant>, so multiple Konsist versions
# can be benchmarked side by side.
#
# Usage: scripts/install.sh <variant> <konsist-version|konsist-jar> [kotlin-compiler-version]
#   konsist-version           Released version from Maven Central (or version published to Maven Local), e.g. 0.18.1
#   konsist-jar               Jar built by scripts/build-konsist-jar.sh, e.g. konsist-jars/baseline.jar
#   kotlin-compiler-version   Version of kotlin-compiler-embeddable used by Konsist at runtime (default: version from
#                             Konsist POM, or 2.4.21 for a jar). Konsist users get the version aligned with their
#                             Kotlin version, and performance can differ between versions.
#
# Examples: scripts/install.sh release 0.18.1
#           scripts/install.sh develop-kotlin-2.3 konsist-jars/develop.jar 2.3.21
set -euo pipefail

if [ $# -lt 2 ] || [ $# -gt 3 ]; then
    sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
fi

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VARIANT="$1"
KONSIST="$2"
KOTLIN_COMPILER_VERSION="${3:-}"

if [[ "$KONSIST" == *.jar ]]; then
    [ -f "$KONSIST" ] || { echo "Konsist jar not found: $KONSIST" >&2; exit 1; }
    KONSIST="$(cd "$(dirname "$KONSIST")" && pwd)/$(basename "$KONSIST")"
fi

COMPILER_ARGS=()
[ -n "$KOTLIN_COMPILER_VERSION" ] && COMPILER_ARGS=(-PkotlinCompilerVersion="$KOTLIN_COMPILER_VERSION")

"$ROOT_DIR/gradlew" -p "$ROOT_DIR" -q installDist -Pvariant="$VARIANT" -Pkonsist="$KONSIST" ${COMPILER_ARGS[@]+"${COMPILER_ARGS[@]}"}
echo "Installed '$VARIANT' (Konsist $2${KOTLIN_COMPILER_VERSION:+, Kotlin compiler $KOTLIN_COMPILER_VERSION}) into build/install/$VARIANT"
