#!/usr/bin/env bash
#
# B-187: the files a tag publishes, and the refusal of a tag that does not name them.
#
#   scripts/release_assets.sh v0.1.<n> <directory the three installers were downloaded into>
#
# Prints the three paths, one per line, and exits 0 — or names what disagrees and exits 1, before
# anything is published. The package version is `0.1.<n>` on Windows and Linux and `1.0.<n>` on
# macOS, where `<n>` is `git rev-list --count HEAD` of the built commit (`ui/build.gradle.kts`,
# B-82, B-90). So a tag `v0.1.<n>` pushed on the commit whose count is `<n>` names all three, and
# one pushed anywhere else names none: the release would carry a name its files contradict.
#
# Exactly one file of each kind, too: a directory holding two `.msi`s is a download that mixed
# runs, and guessing which one is meant is how a release ships the wrong build.

set -euo pipefail

tag="${1:?usage: release_assets.sh v0.1.<n> <dir>}"
dir="${2:?usage: release_assets.sh v0.1.<n> <dir>}"

if [[ ! "$tag" =~ ^v0\.1\.([0-9]+)$ ]]; then
    echo "the tag is '$tag'; a release tag is v0.1.<n>, <n> being the commit count of the tagged commit" >&2
    exit 1
fi
n="${BASH_REMATCH[1]}"

failed=0
found=()
one() {
    local what="$1" pattern="$2" expected="$3"
    local matches=()
    while IFS= read -r -d '' f; do matches+=("$f"); done < <(find "$dir" -type f -name "$pattern" -print0)
    if [ "${#matches[@]}" -ne 1 ]; then
        echo "expected one $what, found ${#matches[@]}: ${matches[*]:-none}" >&2
        failed=1
        return
    fi
    local name
    name="$(basename "${matches[0]}")"
    if [ "$name" != "$expected" ]; then
        echo "the $what is $name; the tag $tag names $expected" >&2
        failed=1
        return
    fi
    found+=("${matches[0]}")
}

one ".msi" '*.msi' "kachok-0.1.$n.msi"
one ".deb" '*.deb' "kachok_0.1.${n}_amd64.deb"
one ".dmg" '*.dmg' "kachok-1.0.$n.dmg"

if [ "$failed" -ne 0 ]; then
    echo "nothing is published: the tag and the packages disagree" >&2
    exit 1
fi
printf '%s\n' "${found[@]}"
