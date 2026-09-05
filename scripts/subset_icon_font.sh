#!/usr/bin/env bash
#
# Material Symbols Rounded, cut down to the glyphs this UI actually draws.
#
# The full variable font is 15 MB. The design uses two dozen names and the inventory says fifteen;
# shipping the rest in a 35 MB distribution would be most of a megabyte per glyph nobody draws.
# The names come from `ui/.../icons/Icons.kt`, so adding one there and forgetting this script
# produces a missing glyph rather than a silently wrong one.
#
#   VENV=/path/to/venv ./scripts/subset_icon_font.sh path/to/MaterialSymbolsRounded.ttf
#
set -euo pipefail

here=$(cd "$(dirname "$0")/.." && pwd)
source_font=${1:?usage: subset_icon_font.sh <full MaterialSymbolsRounded.ttf>}
out="$here/ui/src/desktopMain/resources/fonts/MaterialSymbolsRounded.ttf"
names_file="$here/ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/icons/Icons.kt"
subsetter=${PYFTSUBSET:-pyftsubset}

command -v "$subsetter" >/dev/null || { echo "no pyftsubset; set PYFTSUBSET" >&2; exit 1; }

# The codepoints the UI asks for, read out of the one file that names them.
#
# By codepoint and not by ligature: Material Symbols' substitution table maps letters to every icon
# in the font, so subsetting by the ligature *names* with layout features kept keeps all four
# thousand glyphs — measured, the "subset" came out at the full 15 MB.
codes=$(grep -oE '\\u[0-9a-f]{4}' "$names_file" | sed 's/\\u/U+/' | sort -u | tr '\n' ',' | sed 's/,$//')
[ -n "$codes" ] || { echo "no codepoints found in $names_file" >&2; exit 1; }

"$subsetter" "$source_font" \
  --output-file="$out" \
  --unicodes="$codes" \
  --layout-features='' \
  --no-hinting

size=$(stat -f%z "$out" 2>/dev/null || stat -c%s "$out")
count=$(echo "$codes" | tr ',' '\n' | grep -c .)
printf 'subset to %s (%s bytes) from %s codepoints\n' "$out" "$size" "$count"
# A subset that is still most of the original is a subset that did not happen, and the failure is
# invisible: the font works, it is simply enormous.
[ "$size" -lt 200000 ] || { echo "the subset is $size bytes — it kept glyphs it was not asked for" >&2; exit 1; }
