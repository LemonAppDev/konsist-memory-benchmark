#!/usr/bin/env bash
# Clones (or generates) the benchmarked projects into the projects/ directory (pinned commits, so results are
# comparable).
#
# Usage: scripts/setup-projects.sh [ktor] [kotlin] [konsist] [synthetic]
#   ktor        ktorio/ktor (2,435 Kotlin files, ~100 MB)
#   kotlin      JetBrains/kotlin (67,000 Kotlin files, ~800 MB) and kotlin-compiler: its 'compiler' directory without
#               test data (6,802 Kotlin files)
#   konsist     LemonAppDev/konsist (1,700 Kotlin files): deep interface hierarchies and many @Suppress annotations
#   synthetic   generated project with deep and diamond-shaped hierarchies (2,002 Kotlin files), see
#               scripts/generate-synthetic-project.py
# Without arguments all projects are set up.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PROJECTS_DIR="$ROOT_DIR/projects"

KTOR_URL="https://github.com/ktorio/ktor.git"
KTOR_COMMIT="4717dd5fe8270a40fc97ba0b03bb63f722717786"
KOTLIN_URL="https://github.com/JetBrains/kotlin.git"
KOTLIN_COMMIT="80437e615b5980af4b501ecd970c949bca593e58"
KONSIST_URL="https://github.com/LemonAppDev/konsist.git"
KONSIST_COMMIT="26d7b4a97f63e9f89ef669fb5a51cb484efe66d1"

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

create_synthetic() {
    local dir="$PROJECTS_DIR/synthetic"

    if [ -d "$dir" ]; then
        echo "synthetic: already created"
        return
    fi

    python3 "$ROOT_DIR/scripts/generate-synthetic-project.py" "$dir"
}

projects=("$@")
[ ${#projects[@]} -eq 0 ] && projects=(ktor kotlin konsist synthetic)

for project in "${projects[@]}"; do
    case "$project" in
    ktor)
        clone ktor "$KTOR_URL" "$KTOR_COMMIT"
        ;;
    kotlin)
        clone kotlin "$KOTLIN_URL" "$KOTLIN_COMMIT"
        create_kotlin_compiler
        ;;
    konsist)
        clone konsist "$KONSIST_URL" "$KONSIST_COMMIT"
        ;;
    synthetic)
        create_synthetic
        ;;
    *)
        echo "Unknown project: $project (use ktor, kotlin, konsist or synthetic)" >&2
        exit 1
        ;;
    esac
done
