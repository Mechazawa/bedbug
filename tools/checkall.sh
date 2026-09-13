#!/bin/bash
# Every check this repository has, in the order that fails fastest.
#
# The build covers four Minecraft versions, checkstyle, the unit tests and the in-game tests. The
# five censuses say how much of the published Bedrock vocabulary the mod answers, which the in-game
# coverage report cannot: that one sees only what a loaded pack asked for. The harness drives real
# servers and clients, and the scenes open a world with a player in it.
#
#   tools/checkall.sh            # build, censuses and the harness
#   tools/checkall.sh --quick    # build and censuses only
#   tools/checkall.sh --scenes   # and the in-game scenes, which open a window
set -u

cd "$(dirname "$0")/.." || exit 2
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@25}

quick=0
scenes=0
for argument in "$@"; do
    case "$argument" in
        --quick) quick=1 ;;
        --scenes) scenes=1 ;;
        *) echo "unknown option: $argument" >&2; exit 2 ;;
    esac
done

failed=()
step() {
    local name="$1"; shift
    echo "=== $name"
    if "$@"; then
        echo "--- $name ok"
    else
        echo "--- $name FAILED"
        failed+=("$name")
    fi
}

# The censuses measure against material that is not committed, so it is fetched if absent.
bash tools/census/fetch.sh >/dev/null 2>&1

step "script modules link" python3 tools/modules/check.py
step "build, four versions" ./gradlew build --console=plain -q
step "script surface census" python3 tools/census/script.py
step "component census" python3 tools/census/components.py
step "model census" python3 tools/census/model.py
step "command census" python3 tools/census/commands.py
step "particle census" python3 tools/census/particles.py

if [ "$quick" -eq 0 ]; then
    # The packs this repository owns, rebuilt so a stale archive cannot pass for a fresh one.
    for version in 1.20.1 1.21.1 26.1.2 26.2; do
        python3 tools/testpack/build.py --version "$version" >/dev/null
        python3 tools/testpack/build_modules.py --version "$version" >/dev/null
        python3 tools/testpack/build_gametests.py --version "$version" >/dev/null
        python3 tools/testpack/build_blockprobe.py --version "$version" >/dev/null
    done
    step "servers and clients" python3 tools/verify/verify.py all
fi

if [ "$scenes" -eq 1 ]; then
    step "scenes in a world" python3 tools/scene/scene.py --version 26.2
fi

echo
if [ ${#failed[@]} -eq 0 ]; then
    echo "PASS  everything"
    exit 0
fi
echo "FAIL  ${failed[*]}"
exit 1
