#!/usr/bin/env python3
"""
B-157: how long the packaged window takes to have an engine, with an AOT cache and without.

    python3 -I scripts/aot_startup.py <launcher> <cache> <runs> <out.csv>

"Has an engine" is the window's single-instance lock answering `kachok/mcp` rather than
`kachok/no-engine`: the engine is built on the first composition, so this is the first frame plus
what the first frame needs, measured from outside without touching the code. Each run gets a home
of its own (`-Duser.home` through `JAVA_TOOL_OPTIONS`, `XDG_CONFIG_HOME`), so nothing it finds is
left over from the run before, and is stopped with SIGTERM. The arms are interleaved, alternating
which goes first, after one discarded warm-up of each — the page cache is warm for both.

`mapped` is not `Opened AOT cache`: the VM prints that line and *then* refuses a cache whose jars
have changed (research §1.3h), so a run counts as mapped only when the log has the line and no
`[error]` from `aot`.

Linux and macOS: it sends SIGTERM and reads the configuration directory the way `configDirectory`
does on those two.
"""
import os
import signal
import socket
import subprocess
import sys
import tempfile
import time

launcher, cache, runs, out = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]


def ready(lock):
    try:
        port, secret = open(lock).read().split()[:2]
        with socket.create_connection(("127.0.0.1", int(port)), timeout=2) as s:
            s.sendall(f"{secret}\nmcp\n".encode())
            f = s.makefile()
            return f.readline().strip() == "kachok" and f.readline().strip() == "kachok/mcp"
    except (OSError, ValueError):
        return False


def one(with_cache):
    home = tempfile.mkdtemp(prefix="kachok-aot-")
    log = os.path.join(home, "aot.log")
    options = [f"-Duser.home={home}"]
    if with_cache:
        options += [f"-XX:AOTCache={cache}", f"-Xlog:aot=info:file={log}"]
    env = dict(os.environ, JAVA_TOOL_OPTIONS=" ".join(options), XDG_CONFIG_HOME=os.path.join(home, ".config"))
    config = os.path.join(home, "Library", "Application Support") if sys.platform == "darwin" else env["XDG_CONFIG_HOME"]
    lock = os.path.join(config, "kachok", "instance")
    start = time.monotonic()
    window = subprocess.Popen([launcher], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    while not ready(lock):
        if window.poll() is not None:
            raise SystemExit(f"the window exited with {window.returncode}")
        time.sleep(0.01)
    took = time.monotonic() - start
    window.send_signal(signal.SIGTERM)
    window.wait(60)
    text = open(log).read() if os.path.exists(log) else ""
    mapped = "Opened AOT cache" in text and "[error" not in text
    return took, mapped


rows = []
one(False)
one(True)
for i in range(runs):
    for with_cache in (False, True) if i % 2 == 0 else (True, False):
        took, mapped = one(with_cache)
        rows.append((i, with_cache, round(took * 1000), mapped))
        print(rows[-1], flush=True)
with open(out, "w") as f:
    f.write("run,cache,ms_to_engine,mapped\n")
    f.writelines(",".join(map(str, row)) + "\n" for row in rows)
for arm in (False, True):
    ms = sorted(row[2] for row in rows if row[1] == arm)
    mapped = sum(row[3] for row in rows if row[1] == arm)
    print(f"cache={arm}: min {ms[0]} median {ms[len(ms) // 2]} max {ms[-1]} ms, mapped {mapped}/{len(ms)}")
