#!/usr/bin/env bash
#
# B-29's acceptance criterion: the run-time image downloads a torrent on a machine with no JDK.
#
# "No JDK" is the whole claim, and it cannot be checked on the machine that built the image —
# a `java` on the PATH, a JAVA_HOME, a JDK the launcher could fall back on. So the client runs in
# `debian:stable-slim`, which has none of those, against a swarm served from this machine.
#
# Two things are deliberately not shared with the Gradle build and are read from it instead: the
# module set and the JVM flags come out of `cli/build/kachok/image.properties`, so this script
# cannot drift into checking an image the build would not produce.
#
#   ./scripts/verify_runtime_image.sh [megabytes]
#
set -euo pipefail

here=$(cd "$(dirname "$0")/.." && pwd)
megabytes=${1:-16}
work=$(mktemp -d)
swarm_pid=""

cleanup() {
  [ -n "$swarm_pid" ] && kill "$swarm_pid" 2>/dev/null || true
  rm -rf "$work"
}
trap cleanup EXIT

echo "==> building the image the distribution ships"
"$here/gradlew" --quiet --console=plain :cli:runtimeImage

properties="$here/cli/build/kachok/image.properties"
modules=$(grep '^modules=' "$properties" | cut -d= -f2-)
flags=$(grep '^flags=' "$properties" | cut -d= -f2-)
main_class=$(grep '^mainClass=' "$properties" | cut -d= -f2-)
echo "    modules: $modules"
echo "    flags:   $flags"

echo "==> linking the same image for linux/amd64, where the container will run it"
mkdir -p "$work/image/lib"
cp "$here"/cli/build/kachok/lib/*.jar "$work/image/lib/"
docker run --rm --platform linux/amd64 \
  -v "$work/image:/out" \
  eclipse-temurin:25-jdk \
  jlink --add-modules "$modules" --strip-debug --no-man-pages --no-header-files \
        --compress zip-6 --output /out/runtime

mkdir -p "$work/image/bin"
cat > "$work/image/bin/kachok" <<LAUNCHER
#!/bin/sh
set -e
here=\$(cd "\$(dirname "\$0")/.." && pwd)
exec "\$here/runtime/bin/java" $flags -cp "\$here/lib/*" $main_class "\$@"
LAUNCHER
chmod +x "$work/image/bin/kachok"
echo "    image: $(du -sh "$work/image" | cut -f1)"

echo "==> serving a $megabytes MB torrent from this machine"
mkdir -p "$work/torrent" "$work/out"
"$here/gradlew" --quiet --console=plain :cli:swarmHost \
  -PswarmArgs="--dir $work/torrent --megabytes $megabytes --announce-host host.docker.internal" \
  >"$work/swarm.log" 2>&1 &
swarm_pid=$!
for _ in $(seq 1 60); do
  [ -f "$work/torrent/fixture.torrent" ] && break
  sleep 1
done
if [ ! -f "$work/torrent/fixture.torrent" ]; then
  echo "the swarm never came up:"; cat "$work/swarm.log"; exit 1
fi

echo "==> downloading it in debian:stable-slim, which has no java at all"
docker run --rm --platform linux/amd64 \
  --add-host host.docker.internal:host-gateway \
  -v "$work/image:/opt/kachok:ro" \
  -v "$work/torrent:/torrent:ro" \
  -v "$work/out:/out" \
  debian:stable-slim \
  sh -c '
    set -e
    if command -v java >/dev/null 2>&1; then echo "this container has a java; the check is void"; exit 1; fi
    /opt/kachok/bin/kachok download /torrent/fixture.torrent --dir /out
    cd /out && sha256sum -c /torrent/expected.sha256
  '

echo "==> ok: the image downloaded and the bytes are the torrent's"
