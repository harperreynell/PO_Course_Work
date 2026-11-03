#!/usr/bin/env python3
"""Create an exactly sized, reproducible English corpus without replacing user data."""
import argparse
import hashlib
import json
import random
import shutil
import tempfile
from pathlib import Path

TOPICS = [
    ("astronomy", "telescope mirror|comet tail|satellite orbit|distant galaxy", "observatory"),
    ("railway", "electric train|railway bridge|station clock|signal cable", "station"),
    ("ocean", "ocean current|tidal pool|coral reef|water temperature", "coast"),
    ("music", "violin string|cello bow|concert recording|orchestra score", "concert hall"),
    ("computing", "search index|parallel worker|thread queue|database record", "laboratory"),
    ("garden", "garden soil|basil plant|compost pile|water supply", "greenhouse"),
    ("energy", "wind turbine|solar panel|power meter|battery cell", "power station"),
    ("aviation", "radar antenna|aircraft wing|flight record|radio signal", "airport"),
]
RESERVE = 512 * 1024**2


def paragraphs(nouns, site):
    sections = []
    for noun in nouns.split("|"):
        for role in ("team", "observer", "researcher", "visitor"):
            for verb in ("examines", "describes", "studies", "records"):
                for when in ("at dawn", "in the evening", "after lunch", "on Monday"):
                    sections.append((f"The {role} {verb} the {noun} {when}. "
                                     f"Field notes from the {site} describe the changes in detail.\n").encode())
    return sections


def write_document(path, index, size, seed, samples):
    rng = random.Random(seed + index)
    topic = index % len(TOPICS)
    pending = bytearray(f"Field report {index:06d} on {TOPICS[topic][0]}.\n".encode())
    remaining = size - len(pending)
    digest = hashlib.sha256()
    with path.open("wb") as stream:
        while True:
            # A few references cross topic boundaries.
            pool = samples[topic if rng.randrange(12) else rng.randrange(len(TOPICS))]
            paragraph = rng.choice(pool)
            if len(paragraph) > remaining:
                if remaining:
                    pending.extend(b" " * (remaining - 1) + b"\n")
                break
            pending.extend(paragraph)
            remaining -= len(paragraph)
            if len(pending) >= 64 * 1024:
                stream.write(pending)
                digest.update(pending)
                pending.clear()
        stream.write(pending)
        digest.update(pending)
    return digest.digest()


def generate(destination, count, total, seed):
    if count < 1 or total // count < 128:
        raise ValueError("Use at least one document and 128 bytes per document.")
    if destination.exists():
        raise ValueError(f"Output exists: {destination}. Choose a new directory.")
    destination.parent.mkdir(parents=True, exist_ok=True)
    required = total + count * 4096 + RESERVE
    if shutil.disk_usage(destination.parent).free < required:
        raise ValueError(f"Need {required:,} free bytes, including the 512 MiB reserve.")
    samples = [paragraphs(nouns, site) for _, nouns, site in TOPICS]
    digest = hashlib.sha256()
    staging = Path(tempfile.mkdtemp(prefix=".generating-", dir=destination.parent))
    try:
        for i in range(count):
            name = f"field-{i:06d}.txt"
            size = total // count + (i < total % count)
            checksum = write_document(staging / name, i, size, seed, samples)
            digest.update(name.encode() + b"\0" + checksum)
        manifest = dict(documents=count, bytes=total, seed=seed, generator_version=1,
                        corpus_sha256=digest.hexdigest())
        (staging / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        staging.rename(destination)
    finally:
        if staging.exists():
            shutil.rmtree(staging)
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=Path("data/generated-full"))
    parser.add_argument("--documents", type=int, default=10_000)
    parser.add_argument("--bytes", type=int, default=2**31)
    parser.add_argument("--seed", type=int, default=2026)
    args = parser.parse_args()
    try:
        generate(args.out, args.documents, args.bytes, args.seed)
    except (ValueError, OSError) as error:
        parser.exit(1, f"{error}\n")
