#!/usr/bin/env python3
"""Measure the running Compose service with a verified read-only corpus mount."""
import argparse
import concurrent.futures
import csv
import hashlib
import http.client
import json
import os
import platform
import random
import subprocess
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

QUERIES = ["satellite orbit", "electric train", "ocean water", "violin", "search index",
           "garden soil", "battery", "radar antenna"]


def command(*args):
    return subprocess.check_output(args, stderr=subprocess.STDOUT, text=True).strip()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def corpus_info(directory):
    files = sorted(p for p in directory.rglob("*.txt") if p.is_file() and not p.is_symlink())
    digest = hashlib.sha256()
    total = 0
    for path in files:
        with path.open("rb") as stream:
            checksum = hashlib.file_digest(stream, "sha256").digest()
        digest.update(path.relative_to(directory).as_posix().encode() + b"\0" + checksum)
        total += path.stat().st_size
    result = dict(documents=len(files), bytes=total, corpus_sha256=digest.hexdigest())
    manifest = directory / "manifest.json"
    if manifest.exists():
        recorded = json.loads(manifest.read_text())
        if any(recorded[k] != v for k, v in result.items()):
            raise ValueError("Corpus differs from generator manifest")
        result.update(recorded)
    if not files:
        raise ValueError("No .txt files")
    return result


def post(connection, route, payload):
    connection.request("POST", "/api/" + route, json.dumps(payload),
                       {"Content-Type": "application/json"})
    response = connection.getresponse()
    data = json.loads(response.read())
    if response.status != 200:
        raise RuntimeError((response.status, data))
    return data


def environment(args):
    container = command("docker", "compose", "ps", "-q", "server")
    inspected = json.loads(command("docker", "inspect", container))[0]
    mount = next(m for m in inspected["Mounts"] if m["Destination"] == "/data")
    if Path(mount["Source"]).resolve() != args.data.resolve() or mount["RW"]:
        raise ValueError("Mount /data read-only from the selected --data directory first.")
    binding = inspected["NetworkSettings"]["Ports"]["8080/tcp"][0]["HostPort"]
    if int(binding) != args.port:
        raise ValueError(f"Compose exposes port {binding}, but --port is {args.port}")
    docker = json.loads(command("docker", "info", "--format", "{{json .}}"))
    cpu = platform.processor()
    if platform.system() == "Darwin":
        cpu = command("sysctl", "-n", "machdep.cpu.brand_string")
    elif Path("/proc/cpuinfo").exists():
        for line in Path("/proc/cpuinfo").read_text().splitlines():
            if line.startswith("model name"):
                cpu = line.split(":", 1)[1].strip()
                break
    return dict(started_utc=datetime.now(timezone.utc).isoformat(), os=platform.platform(),
                cpu=cpu, host_logical_cpus=os.cpu_count(), architecture=platform.machine(),
                python=platform.python_version(), docker_engine=docker["ServerVersion"],
                docker_cpus=docker["NCPU"], docker_memory_bytes=docker["MemTotal"],
                compose=command("docker", "compose", "version", "--short"),
                java=command("docker", "exec", container, "java", "-version"),
                server_image=inspected["Image"], jar=command("docker", "exec", container, "sha256sum", "/app/search.jar"),
                git_commit=command("git", "rev-parse", "HEAD"),
                corpus=corpus_info(args.data), queries=QUERIES, max_threads=args.max_threads,
                clients=args.clients, repeats=args.repeats, seed=args.seed, seconds=args.seconds,
                client_host="127.0.0.1", port=args.port, protocol="HTTP/1.1 keep-alive",
                cache="one unmeasured build; OS file cache is not cleared")


def throughput(port, clients, seconds):
    start = []
    gate = threading.Barrier(clients, action=lambda: start.append(time.perf_counter()), timeout=60)

    def worker(worker_id):
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=60)
        def search(i):
            result = post(connection, "search", {"query": QUERIES[i % len(QUERIES)], "mode": "keyword"})
            if not isinstance(result["results"], list) or not result["results"]:
                raise ValueError("Expected a matching document for each benchmark query")
        try:
            search(worker_id)
            gate.wait()
            n = 0
            while time.perf_counter() < start[0] + seconds:
                search(worker_id + n)
                n += 1
            return n, time.perf_counter()
        except BaseException:
            gate.abort()
            raise
        finally:
            connection.close()

    with concurrent.futures.ThreadPoolExecutor(max_workers=clients) as pool:
        runs = list(pool.map(worker, range(clients)))
    total = sum(n for n, _ in runs)
    wall = max(end for _, end in runs) - start[0]
    return total, wall


def run(args):
    info = environment(args)
    args.out.mkdir(parents=True, exist_ok=False)
    write_json(args.out / "environment.json", info)
    connection = http.client.HTTPConnection("127.0.0.1", args.port, timeout=1800)
    def build(threads):
        before = time.perf_counter()
        data = post(connection, "index", {"threads": threads})
        wall = time.perf_counter() - before
        if data["documents_indexed"] != info["corpus"]["documents"]:
            raise ValueError("Document count differs from verified corpus")
        return wall, data["time_ms"]

    rng = random.Random(args.seed)
    try:
        build(min(4, args.max_threads))
        with (args.out / "raw.csv").open("x", newline="") as output:
            writer = csv.writer(output)
            writer.writerow(["kind", "repeat", "parallelism", "operations", "wall_s", "index_ms", "requests_s"])
            for kind, levels in [("index", list(range(1, args.max_threads + 1))), ("search", args.clients.copy())]:
                for repeat in range(1, args.repeats + 1):
                    rng.shuffle(levels)
                    for level in levels:
                        if kind == "index":
                            wall, ms = build(level)
                            row = [kind, repeat, level, 1, f"{wall:.6f}", ms, ""]
                        else:
                            total, wall = throughput(args.port, level, args.seconds)
                            row = [kind, repeat, level, total, f"{wall:.6f}", "", f"{total / wall:.4f}"]
                        writer.writerow(row)
                        output.flush()
                        print(*row, flush=True)
        info["finished_utc"] = datetime.now(timezone.utc).isoformat()
        write_json(args.out / "environment.json", info)
    finally:
        connection.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=Path("data/generated-full"))
    parser.add_argument("--out", type=Path, default=Path("results/local-full"))
    parser.add_argument("--max-threads", type=int, default=6)
    parser.add_argument("--clients", type=int, nargs="+", default=[1, 2, 4, 8, 16])
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--seconds", type=float, default=3)
    parser.add_argument("--seed", type=int, default=2026)
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()
    if not 1 <= args.max_threads <= 64 or min(args.clients) < 1 or args.seconds <= 0 or args.repeats < 1:
        parser.error("Use 1..64 workers and positive counts/duration")
    run(args)
