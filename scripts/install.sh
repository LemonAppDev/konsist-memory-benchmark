#!/usr/bin/env bash
# Installs the benchmark with the given Konsist version into build/install/<variant>, so multiple Konsist versions
# can be benchmarked side by side.
#
# Usage: scripts/install.sh <variant> <konsist-version|konsist-jar>
#   konsist-version   Released version from Maven Central (or version published to Maven Local), e.g. 0.18.1
#   konsist-jar       Jar built by scripts/build-konsist-jar.sh, e.g. konsist-jars/baseline.jar
#
# Example: scripts/install.sh release 0.18.1
set -euo pipefail

if [ $# -ne 2 ]; then
    sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
fi

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VARIANT="$1"
KONSIST="$2"

if [[ "$KONSIST" == *.jar ]]; then
    [ -f "$KONSIST" ] || { echo "Konsist jar not found: $KONSIST" >&2; exit 1; }
    KONSIST="$(cd "$(dirname "$KONSIST")" && pwd)/$(basename "$KONSIST")"
fi

"$ROOT_DIR/gradlew" -p "$ROOT_DIR" -q installDist -Pvariant="$VARIANT" -Pkonsist="$KONSIST"
echo "Installed '$VARIANT' (Konsist $2) into build/install/$VARIANT"
