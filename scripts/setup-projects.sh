#!/usr/bin/env bash
# Clones the benchmarked projects into the projects/ directory (pinned commits, so results are comparable).
#
# Usage: scripts/setup-projects.sh [ktor] [kotlin]
#   ktor     ktorio/ktor (2,435 Kotlin files, ~100 MB)
#   kotlin   JetBrains/kotlin (67,000 Kotlin files, ~800 MB) and kotlin-compiler: its 'compiler' directory without
#            test data (6,802 Kotlin files)
# Without arguments all projects are cloned.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PROJECTS_DIR="$ROOT_DIR/projects"

KTOR_URL="https://github.com/ktorio/ktor.git"
KTOR_COMMIT="4717dd5fe8270a40fc97ba0b03bb63f722717786"
KOTLIN_URL="https://github.com/JetBrains/kotlin.git"
KOTLIN_COMMIT="80437e615b5980af4b501ecd970c949bca593e58"

count_kotlin_files() {
    find "$1" -name '*.kt' | wc -l | tr -d ' '
}

clone() {
    local name="$1" url="$2" commit="$3"
    local dir="$PROJECTS_DIR/$name"

    if [ -d "$dir/.git" ]; then
        echo "$name: already cloned"
        return
    fi

    echo "$name: cloning $url at $commit"
    mkdir -p "$dir"
    git -C "$dir" init -q
    git -C "$dir" remote add origin "$url"
    git -C "$dir" fetch -q --depth 1 origin "$commit"
    git -C "$dir" checkout -q FETCH_HEAD
    echo "$name: $(count_kotlin_files "$dir") Kotlin files"
}

create_kotlin_compiler() {
    local dir="$PROJECTS_DIR/kotlin-compiler"

    if [ -d "$dir" ]; then
        echo "kotlin-compiler: already created"
        return
    fi

    rsync -a --prune-empty-dirs --exclude='testData/' --exclude='testdata/' --include='*/' --include='*.kt' --exclude='*' \
        "$PROJECTS_DIR/kotlin/compiler/" "$dir/"

    # Konsist uses directory containing 'gradlew' file as the project root
    touch "$dir/gradlew"
    echo "kotlin-compiler: $(count_kotlin_files "$dir") Kotlin files"
}

projects=("$@")
[ ${#projects[@]} -eq 0 ] && projects=(ktor kotlin)

for project in "${projects[@]}"; do
    case "$project" in
    ktor)
        clone ktor "$KTOR_URL" "$KTOR_COMMIT"
        ;;
    kotlin)
        clone kotlin "$KOTLIN_URL" "$KOTLIN_COMMIT"
        create_kotlin_compiler
        ;;
    *)
        echo "Unknown project: $project (use ktor or kotlin)" >&2
        exit 1
        ;;
    esac
done
