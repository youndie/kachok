#!/usr/bin/env python3
"""
B-98: one run of one client, sampled and then stopped. Written to run on the measuring machine.

    python run_reach.py --client after --minutes 30 --out runs/after-1.csv

It starts the client, hands its process id to peer_reach.py, waits, and stops **that process and
no other**. The client is found and killed by its own handle, never by image name: the machine this
runs on is somebody's desktop, and `taskkill /IM qbittorrent.exe` would take their client down with
the benchmark's. That has already happened once.

Each run downloads into a directory of its own. Reusing one would make the second run of a variant
start as a seed, and a client with nothing left to want meets a different swarm than one that is
still asking — which is not the thing being compared.
"""
import argparse
import os
import platform
import shutil
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.abspath(__file__))
BENCH = os.environ.get("KACHOK_BENCH", r"C:\kachok-bench" if platform.system() == "Windows" else "/tmp/kachok-bench")
TORRENT = os.path.join(BENCH, "data", "ubuntu.torrent")
QBT = r"C:\Program Files\qBittorrent\qbittorrent.exe"


def kachok(variant, directory, log, dht):
    """The headless client, from the jars of one side of the comparison.

    **`--dht` is a parameter and not a constant, and the first smoke run is why.** Ubuntu's tracker
    hands out exactly one peer per announce whatever `numwant` asks for — measured, at 50 and at
    200 — so on that swarm a client with the DHT off has one address to work with and nothing this
    stage changed can matter. A run with the DHT off measures the default; a run with it on is the
    only one in which the dial loop has a swarm to dial.
    """
    classpath = os.path.join(BENCH, variant, "lib", "*")
    command = ["java", "-XX:+UseG1GC", "-cp", classpath, "io.github.youndie.kachok.cli.MainKt", "download"]
    if dht:
        command.append("--dht")
    command += ["--dir", directory, TORRENT]
    return subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)


def qbittorrent(directory, log):
    profile = os.path.join(BENCH, "qbt-profile")
    command = [
        QBT, "--profile=" + profile, "--save-path=" + directory,
        "--skip-dialog=true", TORRENT,
    ]
    return subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--client", required=True, choices=["before", "after", "qbt"])
    ap.add_argument("--minutes", type=float, default=30)
    ap.add_argument("--every", type=float, default=10)
    ap.add_argument("--out", required=True)
    ap.add_argument("--label", default=None)
    ap.add_argument("--swarm", default="?")
    ap.add_argument("--dht", action="store_true", help="kachok only: join the DHT")
    ap.add_argument("--warmup", type=float, default=20, help="seconds before sampling starts")
    args = ap.parse_args()

    label = args.label or args.client
    directory = os.path.join(BENCH, "dl", os.path.splitext(os.path.basename(args.out))[0])
    shutil.rmtree(directory, ignore_errors=True)
    os.makedirs(directory, exist_ok=True)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)) or ".", exist_ok=True)

    log_path = os.path.splitext(args.out)[0] + ".log"
    with open(log_path, "w", encoding="utf-8", errors="replace") as log:
        client = qbittorrent(directory, log) if args.client == "qbt" else kachok(args.client, directory, log, args.dht)
        print("started {0} as pid {1}".format(label, client.pid))
        # qBittorrent hands the torrent to an instance that is already up and then exits, so the
        # process to sample is not always the one just launched. Everything else starts and stays.
        time.sleep(args.warmup)
        pid = client.pid
        if client.poll() is not None:
            pid = qbt_instance()
            if pid is None:
                raise SystemExit("the client exited during warm-up; see " + log_path)
            print("the launcher handed over; sampling the running instance, pid {0}".format(pid))

        sampler = [
            sys.executable, os.path.join(BENCH, "peer_reach.py"), "sample",
            "--pid", str(pid), "--minutes", str(args.minutes), "--every", str(args.every),
            "--out", args.out, "--client", label, "--torrent", "ubuntu-24.04.3-desktop-amd64.iso",
            "--swarm", args.swarm,
        ]
        try:
            subprocess.run(sampler, check=False)
        finally:
            stop(pid)
            if client.poll() is None:
                stop(client.pid)
    print("run finished: {0}".format(args.out))


def qbt_instance():
    """The running qBittorrent **of this benchmark**, by its command line and not by its name."""
    if platform.system() != "Windows":
        return None
    script = (
        "Get-CimInstance Win32_Process -Filter \"Name='qbittorrent.exe'\" "
        "| Where-Object { $_.CommandLine -like '*kachok-bench*' } "
        "| Select-Object -First 1 -ExpandProperty ProcessId"
    )
    out = subprocess.run(["powershell", "-NoProfile", "-Command", script],
                         capture_output=True, text=True)
    value = (out.stdout or "").strip()
    return int(value) if value.isdigit() else None


def stop(pid):
    if platform.system() == "Windows":
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True)
    else:
        subprocess.run(["kill", "-TERM", str(pid)], capture_output=True)


if __name__ == "__main__":
    sys.exit(main())
