#!/bin/bash
# Fetches what the two censuses measure against, so a fresh checkout can run them.
#
# Neither body of reference material is committed: one is Mojang's `bedrock-samples` repository and
# the other is the published `@minecraft/*` npm packages, and both are theirs rather than ours. They
# land under `scratchpad/`, which git ignores.
#
# Already-present material is left alone unless --refresh is given, so this is cheap to call from a
# script that runs often.
set -u

cd "$(dirname "$0")/../.." || exit 2
scratch="scratchpad"
samples="$scratch/bedrock/bedrock-samples"
definitions="$scratch/mcapi"

refresh=0
[ "${1:-}" = "--refresh" ] && refresh=1

mkdir -p "$scratch"
[ -f "$scratch/.gitignore" ] || echo '*' > "$scratch/.gitignore"

# The Bedrock vocabulary: every entity, block and item component the engine knows, and the Molang
# query list. One shallow clone; the history is of no use here.
if [ "$refresh" -eq 1 ] || [ ! -d "$samples/metadata" ]; then
    echo "fetching bedrock-samples"
    rm -rf "$samples"
    mkdir -p "$(dirname "$samples")"
    if ! git clone --depth 1 --quiet https://github.com/Mojang/bedrock-samples "$samples"; then
        echo "could not clone bedrock-samples; the component census will fall back to the vanilla packs" >&2
    fi
else
    echo "bedrock-samples already present"
fi

# The script API: each package's `index.d.ts` under the name the census looks for.
mkdir -p "$definitions"
for module in server server-ui server-gametest server-admin common math vanilla-data; do
    target="$definitions/$module.d.ts"
    if [ "$refresh" -eq 0 ] && [ -s "$target" ]; then
        continue
    fi
    # server-admin is published only as a beta, and its `latest` tag names an old build.
    spec="@minecraft/$module"
    [ "$module" = "server-admin" ] && spec="$spec@beta"
    echo "fetching $spec"
    staging=$(mktemp -d)
    if (cd "$staging" && npm pack "$spec" --silent >/dev/null 2>&1); then
        archive=$(find "$staging" -name '*.tgz' | head -1)
        tar xzf "$archive" -C "$staging" 2>/dev/null
        # vanilla-data splits its tables over many files; the rest ship one index.
        found=$(find "$staging/package" -name 'index.d.ts' | head -1)
        if [ "$module" = "vanilla-data" ]; then
            cat "$staging"/package/lib/*.d.ts > "$target" 2>/dev/null
        elif [ -n "$found" ]; then
            cp "$found" "$target"
        else
            find "$staging/package" -name '*.d.ts' | head -1 | xargs -I {} cp {} "$target"
        fi
    else
        echo "could not fetch @minecraft/$module; that module will report as unmeasured" >&2
    fi
    rm -rf "$staging"
done

# The oldest vanilla data package, whose block and item tables still carry the family ids (`wool`,
# `log`, `stone_block_slab`) Bedrock used before it gave every member an id of its own, which is
# what packs from that time write. The id census reads it beside the newest.
older="$definitions/vanilla-data-1.20.0.d.ts"
if [ "$refresh" -eq 1 ] || [ ! -s "$older" ]; then
    echo "fetching @minecraft/vanilla-data@1.20.0"
    staging=$(mktemp -d)
    if (cd "$staging" && npm pack "@minecraft/vanilla-data@1.20.0" --silent >/dev/null 2>&1); then
        tar xzf "$(find "$staging" -name '*.tgz' | head -1)" -C "$staging" 2>/dev/null
        cat "$staging"/package/lib/*.d.ts > "$older" 2>/dev/null
    else
        echo "could not fetch @minecraft/vanilla-data@1.20.0; the id census will leave it out" >&2
    fi
    rm -rf "$staging"
fi

echo "reference material is under $scratch"
