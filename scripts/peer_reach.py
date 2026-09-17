#!/usr/bin/env python3
"""
B-98: how many peers a BitTorrent client actually holds, sampled the same way for any client.

    python3 scripts/peer_reach.py sample --pid 4213 --minutes 30 --out runs/kachok-after-1.csv
    python3 scripts/peer_reach.py summarise runs/*.csv

WHY THIS MEASURES THE WIRE AND NOT THE CLIENT'S OWN NUMBER. The question is whether this client
meets fewer peers than a mature one on the same torrent, and each client counts "peers" its own
way — some include half-open dials, some include peers they are choked by, some count per torrent
and some per session. Two numbers produced by two different definitions cannot be subtracted. An
established TCP connection owned by the process is the same fact on both sides, and the operating
system is the one reporting it.

WHY RUNS ARE COMPARED AND NOT COMBINED. Two clients on one machine are not two independent
samples: they share a NAT binding, an uplink and a public address, and a peer already connected to
one will refuse a second connection from the same address. So they are run one after another and
each run records the swarm size its tracker reported, which is what makes a swarm that emptied
between runs visible instead of invisible.

WHAT IT DOES NOT DO. It does not start anything, and it takes no view on which client is which:
it samples a process id for a while and writes a row every interval. Starting the clients, picking
the torrent and reading the tracker's swarm size are the operator's, and the columns for them are
in the run's header.
"""
import argparse
import csv
import datetime
import os
import platform
import re
import shutil
import subprocess
import sys
import time

# Connections to this client's own peer port and to peers' ports both count; what is excluded is
# everything that is not a peer — a tracker announce over HTTP, a DHT datagram (not TCP at all),
# and the loopback traffic of a client talking to its own web interface.
EXCLUDED_PORTS = {80, 443, 8080, 8081, 8443}


def established(pid):
    """Remote endpoints of this process's established TCP connections, as `ip:port` strings.

    Three platforms and three tools, and the parsing is deliberately forgiving: a row that does not
    look like a row is skipped rather than fatal, because a sampler that dies at minute nineteen of
    a thirty-minute run has measured nothing.
    """
    system = platform.system()
    if system == "Linux":
        return _linux(pid)
    if system == "Darwin":
        return _darwin(pid)
    if system == "Windows":
        return _windows(pid)
    raise SystemExit("unsupported platform: {0}".format(system))


def _run(command):
    try:
        out = subprocess.run(command, capture_output=True, text=True, timeout=20)
    except (OSError, subprocess.SubprocessError) as e:
        return ""
    return out.stdout or ""


def _keep(host, port):
    return port not in EXCLUDED_PORTS and not host.startswith("127.") and host != "::1"


def _linux(pid):
    if not shutil.which("ss"):
        raise SystemExit("ss is not installed; it is in iproute2")
    text = _run(["ss", "-tnp", "state", "established"])
    found = set()
    for line in text.splitlines()[1:]:
        if "pid={0},".format(pid) not in line:
            continue
        parts = line.split()
        if len(parts) < 4:
            continue
        host, _, port = parts[3].rpartition(":")
        host = host.strip("[]")
        if port.isdigit() and _keep(host, int(port)):
            found.add("{0}:{1}".format(host, port))
    return found


def _darwin(pid):
    text = _run(["lsof", "-nP", "-a", "-p", str(pid), "-iTCP", "-sTCP:ESTABLISHED"])
    found = set()
    for line in text.splitlines()[1:]:
        m = re.search(r"->(\[?[0-9a-fA-F:.]+\]?):(\d+)", line)
        if not m:
            continue
        host = m.group(1).strip("[]")
        if _keep(host, int(m.group(2))):
            found.add("{0}:{1}".format(host, m.group(2)))
    return found


def _windows(pid):
    text = _run(["netstat", "-ano", "-p", "TCP"])
    found = set()
    for line in text.splitlines():
        parts = line.split()
        if len(parts) < 5 or parts[3] != "ESTABLISHED" or parts[4] != str(pid):
            continue
        host, _, port = parts[2].rpartition(":")
        host = host.strip("[]")
        if port.isdigit() and _keep(host, int(port)):
            found.add("{0}:{1}".format(host, port))
    return found


def alive(pid):
    if platform.system() == "Windows":
        return str(pid) in _run(["tasklist", "/FI", "PID eq {0}".format(pid), "/NH"])
    try:
        os.kill(pid, 0)
    except (OSError, ProcessLookupError):
        return False
    return True


def sample(args):
    if not alive(args.pid):
        raise SystemExit("no process {0}".format(args.pid))
    os.makedirs(os.path.dirname(os.path.abspath(args.out)) or ".", exist_ok=True)
    started = time.time()
    deadline = started + args.minutes * 60
    seen_ever = set()
    with open(args.out, "w", newline="", encoding="utf-8") as fh:
        # The header rows carry what the numbers cannot: which client, which torrent, and what the
        # tracker said the swarm held. A csv of counts with no idea which run it came from is what
        # makes two measurements uncomparable a week later.
        fh.write("# client,{0}\n".format(args.client))
        fh.write("# torrent,{0}\n".format(args.torrent))
        fh.write("# swarm-reported,{0}\n".format(args.swarm))
        fh.write("# host,{0} {1}\n".format(platform.system(), platform.release()))
        fh.write("# started,{0}\n".format(datetime.datetime.now().isoformat(timespec="seconds")))
        writer = csv.writer(fh)
        writer.writerow(["seconds", "established", "distinct_ips", "seen_ever"])
        while time.time() < deadline:
            if not alive(args.pid):
                print("process {0} exited at {1:.0f}s".format(args.pid, time.time() - started))
                break
            now = established(args.pid)
            seen_ever |= now
            writer.writerow([
                int(time.time() - started),
                len(now),
                len({p.rsplit(":", 1)[0] for p in now}),
                len(seen_ever),
            ])
            fh.flush()
            time.sleep(args.every)
    print("wrote {0}".format(args.out))


def read(path):
    meta, rows = {}, []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("#"):
                key, _, value = line[1:].strip().partition(",")
                meta[key.strip()] = value.strip()
            elif line.startswith("seconds"):
                continue
            else:
                parts = line.strip().split(",")
                if len(parts) == 4 and parts[0].isdigit():
                    rows.append([int(p) for p in parts])
    return meta, rows


def quantile(values, q):
    if not values:
        return 0
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(q * (len(ordered) - 1) + 0.5))]


def summarise(args):
    print("{0:<28} {1:>7} {2:>7} {3:>7} {4:>7} {5:>9} {6:>7}".format(
        "run", "median", "p90", "max", "ever", "half at", "swarm"))
    print("-" * 78)
    for path in args.files:
        meta, rows = read(path)
        if not rows:
            print("{0:<28} {1}".format(os.path.basename(path), "no samples"))
            continue
        held = [r[1] for r in rows]
        peak = max(held)
        # How long until the client was holding half of what it ever held. A client that reaches
        # its ceiling in a minute and one that takes twenty are the same row without this column,
        # and the difference between them is the whole of B-95.
        half = next((r[0] for r in rows if r[1] * 2 >= peak), None)
        print("{0:<28} {1:>7} {2:>7} {3:>7} {4:>7} {5:>9} {6:>7}".format(
            (meta.get("client") or os.path.basename(path))[:28],
            quantile(held, 0.5), quantile(held, 0.9), peak, rows[-1][3],
            "{0}s".format(half) if half is not None else "-",
            meta.get("swarm-reported", "?")))
    print()
    print("Runs are compared, never combined: one machine's NAT, uplink and address are shared, so")
    print("two clients running at once each make the other look worse. Read the swarm column first.")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[1])
    sub = ap.add_subparsers(dest="command", required=True)

    s = sub.add_parser("sample", help="record one run")
    s.add_argument("--pid", type=int, required=True, help="the client's process id")
    s.add_argument("--minutes", type=float, default=30)
    s.add_argument("--every", type=float, default=10, help="seconds between samples")
    s.add_argument("--out", required=True)
    s.add_argument("--client", default="unnamed", help="what was running, e.g. 'kachok after B-97'")
    s.add_argument("--torrent", default="unnamed", help="which torrent, by name")
    s.add_argument("--swarm", default="?", help="seeders+leechers the tracker reported at the start")
    s.set_defaults(func=sample)

    t = sub.add_parser("summarise", help="print the comparison table")
    t.add_argument("files", nargs="+")
    t.set_defaults(func=summarise)

    args = ap.parse_args()
    return args.func(args) or 0


if __name__ == "__main__":
    sys.exit(main())
