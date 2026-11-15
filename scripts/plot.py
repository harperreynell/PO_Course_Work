#!/usr/bin/env python3
"""Plot every raw run and the median for each client/worker count."""
import argparse
import csv
import json
from pathlib import Path
from statistics import median
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


def plot(directory):
    with (directory / "raw.csv").open() as stream:
        rows = list(csv.DictReader(stream))
    environment = json.loads((directory / "environment.json").read_text())
    if "finished_utc" not in environment:
        raise ValueError("Benchmark did not finish")
    metrics = [("index", "index_ms", "Потоки індексації", "Час побудови, с", 0.001),
               ("search", "requests_s", "Одночасні клієнти", "Запити за секунду", 1)]
    summary = {}
    for kind, key, xlabel, ylabel, scale in metrics:
        groups = {}
        for row in rows:
            if row["kind"] == kind:
                groups.setdefault(int(row["parallelism"]), []).append(float(row[key]) * scale)
        counts = sorted(groups)
        mid = [median(groups[n]) for n in counts]
        fig, ax = plt.subplots(figsize=(7.1, 2.9), layout="constrained")
        ax.plot(counts, mid, color="#146457", marker="o", label="Медіана", zorder=2)
        for n, values in groups.items():
            ax.scatter([n] * len(values), values, color="#a85924", alpha=.8, s=20,
                       label="Окремий запуск" if n == counts[0] else None, zorder=3)
        ax.set(xlabel=xlabel, ylabel=ylabel, xticks=counts, ylim=(0, max(max(v) for v in groups.values()) * 1.15))
        ax.spines[["top", "right"]].set_visible(False)
        ax.grid(axis="y", alpha=.23)
        ax.legend(frameon=False, fontsize=9, ncols=2)
        fig.savefig(directory / (kind + ".png"), dpi=180)
        plt.close(fig)
        summary[kind] = [{"parallelism": n, "median": median(groups[n]), "min": min(groups[n]),
                          "max": max(groups[n])} for n in counts]
    (directory / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    plot(parser.parse_args().directory)
