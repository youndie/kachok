#!/usr/bin/env bash
#
# The design's own screens, as PNG files to compare an implementation against.
#
# The design is a `.dc.html` document that renders itself with React; it cannot be read as an
# image and a screenshot of it taken by hand is not re-creatable. This renders it in headless
# Chrome and cuts out the mock windows, so `docs/design/screens/` is a *derived* directory that
# any change to the design regenerates — and so that a comparison is against the design rather
# than against somebody's memory of it.
#
#   ./scripts/capture_design.sh
#
set -euo pipefail

here=$(cd "$(dirname "$0")/.." && pwd)
design="$here/docs/design"
out="$design/screens"
chrome=${CHROME:-"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"}
port=${PORT:-8731}

[ -x "$chrome" ] || { echo "no headless browser at $chrome; set CHROME" >&2; exit 1; }

# The document loads `support.js` beside itself, so it has to be served rather than opened as a
# file: a file:// page cannot fetch its sibling in a way every Chrome build agrees about.
# A port already in use is the failure this script is most likely to meet, and the one that hides
# best: Chrome would fetch somebody else's document, get a 404, and screenshot it happily.
if nc -z 127.0.0.1 "$port" 2>/dev/null; then
  echo "port $port is already in use; set PORT to a free one" >&2
  exit 1
fi
python3 -m http.server "$port" --directory "$design" >/dev/null 2>&1 &
server=$!
trap 'kill $server 2>/dev/null || true' EXIT
ready=""
for _ in $(seq 1 40); do
  if curl -sf -o /dev/null "http://127.0.0.1:$port/kachok%20Phase%202%20Desktop.dc.html"; then ready=yes; break; fi
  sleep 0.25
done
[ -n "$ready" ] || { echo "the design never became reachable on port $port" >&2; exit 1; }

mkdir -p "$out"
page="$out/.full.png"
# Tall enough for the whole document: Chrome's --screenshot captures the window, and the window is
# what --window-size says. The height comes from the document and is checked below.
"$chrome" --headless --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
  --window-size=1340,6800 --virtual-time-budget=8000 \
  --screenshot="$page" "http://127.0.0.1:$port/kachok%20Phase%202%20Desktop.dc.html" >/dev/null 2>&1

# name:x:y:width:height — the mock windows, not the whole section, so an implementation screenshot
# has the same frame to be compared against.
while IFS=: read -r name x y w h; do
  [ -n "$name" ] || continue
  sips --cropToHeightWidth "$h" "$w" --cropOffset "$y" "$x" "$page" --out "$out/$name.png" >/dev/null
  # A headless screenshot succeeds just as readily on a page that did not render, and a uniform
  # crop is what that looks like. Counting distinct colours is cheap and is the difference between
  # a reference image and a rectangle of background.
  #
  # Through a real file, not a pipe: `sips` cannot write to /dev/stdout and says so on stderr,
  # which the first version of this check read as "no colours" and reported as a failed render on
  # an image that was perfectly fine.
  sips -s format bmp "$out/$name.png" --out "$out/.probe.bmp" >/dev/null 2>&1
  colours=$(python3 - "$out/.probe.bmp" <<'PYEOF'
import collections, pathlib, sys
raw = pathlib.Path(sys.argv[1]).read_bytes()
print(len(collections.Counter(raw[i:i + 3] for i in range(54, len(raw), 3))))
PYEOF
)
  rm -f "$out/.probe.bmp"
  [ "${colours:-0}" -gt 200 ] || { echo "$name.png has $colours distinct colours — it did not render" >&2; exit 1; }
  printf '  %-16s %sx%s\n' "$name" "$w" "$h"
done <<'REGIONS'
main-window:40:410:1202:762
row-states:40:1414:1200:457
details-tabs:40:2020:1200:522
add-torrent:40:2639:1202:762
empty-state:40:3764:1202:562
settings:40:4475:1200:636
degraded:40:5208:1200:420
REGIONS

rm -f "$page"
echo "design screens in $out"
