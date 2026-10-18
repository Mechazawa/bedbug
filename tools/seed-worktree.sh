#!/usr/bin/env bash
# Seeds a fresh worktree from the main checkout so its first build skips the slow Loom setup.
# Safe to run more than once, and on a worktree that already holds a copy of the main checkout.
set -euo pipefail

worktree=$(git rev-parse --show-toplevel)
main=$(cd "$(git rev-parse --git-common-dir)/.." && pwd -P)
worktree=$(cd "$worktree" && pwd -P)

if [ "$worktree" = "$main" ]; then
    echo "seed-worktree: this is the main checkout, nothing to seed" >&2
    exit 0
fi

# Files Loom and Gradle generate with absolute paths in them. They are rebuilt by the next build.
rm -rf "$worktree/.gradle/loom-cache/projects"
for version_dir in "$worktree"/versions/*/; do
    rm -rf "${version_dir}build/loom-cache" "${version_dir}build/reports"
done

# Worlds, run directories and the converted-pack cache copied in are state of the checkout that made
# them. Only the first run in this worktree removes them, so a later run leaves the worlds this
# worktree made alone. The marker names the worktree, because `wt add` run from another worktree
# copies that worktree's marker along with everything else.
seeded="$worktree/.gradle/seeded-worktree"
if [ "$(cat "$seeded" 2>/dev/null)" != "$worktree" ]; then
    for version_dir in "$worktree"/versions/*/; do
        rm -rf "${version_dir}build/run" "${version_dir}run/saves" "${version_dir}run/world" \
            "${version_dir}run/.mcaddon" "${version_dir}run/logs" "${version_dir}run/crash-reports"
    done
fi

if cp --reflink=auto --version >/dev/null 2>&1; then
    clone() { cp --reflink=auto -a -n "$@"; }
else
    clone() { cp -c -R -p -n "$@"; }
fi

# Downloads and remapped jars hold no absolute paths and are the same for every checkout.
for cache in minecraftMaven remapped_mods natives; do
    source_dir="$main/.gradle/loom-cache/$cache"
    [ -d "$source_dir" ] || continue
    mkdir -p "$worktree/.gradle/loom-cache/$cache"
    clone "$source_dir/." "$worktree/.gradle/loom-cache/$cache/"
done

echo "$worktree" > "$seeded"
echo "seed-worktree: seeded $worktree from $main"
