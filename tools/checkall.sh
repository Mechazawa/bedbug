#!/usr/bin/env bash
#
# Every check in this repository, in the order that fails fastest.
#
# `--quick` stops before the Gradle build, which is the only part that needs the game and the only
# part that takes minutes. Everything before it runs on a checkout with no add-on and no JDK.
#
#   bash tools/checkall.sh --quick
#   bash tools/checkall.sh
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

quick=0
[ "${1:-}" = "--quick" ] && quick=1

failed=()

run() {
    local name="$1"
    shift
    printf '\n=== %s\n' "$name"
    if "$@"; then
        return 0
    fi
    failed+=("$name")
}

# Parses every tool, which no compiler covers: these scripts are run by hand and could otherwise
# sit broken for weeks.
parse_tools() {
    local broken=0
    while IFS= read -r script; do
        python3 -m py_compile "$script" || broken=1
    done < <(find tools -name '*.py')
    return $broken
}

run "tool scripts parse" parse_tools
run "script modules link" python3 tools/modules/check.py
run "entity component fields" python3 tools/census/fields.py --strict
run "test pack builds" python3 tools/testpack/build.py
run "module pack builds" python3 tools/testpack/build_modules.py

if [ "$quick" -eq 0 ]; then
    export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25}"
    run "all four versions build" ./gradlew build
fi

printf '\n'
if [ ${#failed[@]} -eq 0 ]; then
    echo "All checks passed."
    exit 0
fi

printf 'Failed: %s\n' "${failed[@]}"
exit 1
