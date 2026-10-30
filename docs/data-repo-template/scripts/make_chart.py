#!/usr/bin/env python3
"""Draw progress.png from data/gym/*.csv and data/treadmill/*.csv. Needs only Python 3 and matplotlib."""
import csv
import glob
import os
import sys
from collections import defaultdict
from datetime import date, datetime

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
OUT = os.path.join(ROOT, "progress.png")
LIFTS = [("squat", "Squat", "#4cc9f0"), ("deadlift", "Deadlift", "#f9c74f"), ("rdl", "Romanian deadlift", "#90be6d")]
BG, FG, GRID = "#14161a", "#e6e8eb", "#2a2e35"


def rows(pattern):
    for path in sorted(glob.glob(os.path.join(ROOT, pattern))):
        with open(path, newline="", encoding="utf-8") as f:
            yield from csv.DictReader(f)


def week_start(d):
    return date.fromordinal(d.toordinal() - d.weekday())


def parse(s):
    return datetime.strptime(s, "%Y-%m-%d").date()


def load():
    top = defaultdict(dict)       # lift -> date -> top weight that day
    sessions = defaultdict(set)   # week start -> set of (type, day)
    tread = defaultdict(float)    # week start -> completed minutes
    for r in rows("data/gym/*.csv"):
        try:
            d, w, reps = parse(r["date"]), float(r["weight_kg"]), int(r["reps"])
        except (KeyError, ValueError, TypeError):
            continue
        if reps <= 0:
            continue
        sessions[week_start(d)].add(("gym", d))
        cur = top[r["exercise"]]
        cur[d] = max(w, cur.get(d, 0))
    for r in rows("data/treadmill/*.csv"):
        try:
            d, m = parse(r["date"]), float(r["completed_min"])
        except (KeyError, ValueError, TypeError):
            continue
        sessions[week_start(d)].add(("treadmill", d))
        tread[week_start(d)] += m
    return top, sessions, tread


def style(ax):
    ax.set_facecolor(BG)
    for s in ax.spines.values():
        s.set_color(GRID)
    ax.tick_params(colors=FG)
    ax.yaxis.label.set_color(FG)
    ax.title.set_color(FG)
    ax.grid(color=GRID, linewidth=0.6)
    ax.set_axisbelow(True)


def main():
    top, sessions, tread = load()
    fig, axes = plt.subplots(3, 1, figsize=(10, 11), facecolor=BG)
    fig.suptitle("Training progress", color=FG, fontsize=16)
    if not top and not sessions and not tread:
        for ax in axes:
            ax.axis("off")
        axes[1].text(0.5, 0.5, "First sessions coming soon", ha="center", va="center",
                     color=FG, fontsize=22, transform=axes[1].transAxes)
    else:
        ax = axes[0]
        style(ax)
        ax.set_title("Top working weight (kg)")
        for key, label, colour in LIFTS:
            pts = sorted(top.get(key, {}).items())
            if pts:
                ax.plot([p[0] for p in pts], [p[1] for p in pts], marker="o", color=colour, label=label, linewidth=2)
        if ax.lines:
            ax.legend(facecolor=BG, edgecolor=GRID, labelcolor=FG)
        else:
            ax.text(0.5, 0.5, "Lifts coming soon", ha="center", va="center", color=FG, transform=ax.transAxes)
        ax = axes[1]
        style(ax)
        ax.set_title("Sessions done per week")
        weeks = sorted(sessions)
        ax.bar(weeks, [len(sessions[w]) for w in weeks], width=5, color="#4cc9f0")
        ax.yaxis.get_major_locator().set_params(integer=True)
        ax = axes[2]
        style(ax)
        ax.set_title("Treadmill minutes per week")
        weeks = sorted(tread)
        ax.bar(weeks, [tread[w] for w in weeks], width=5, color="#90be6d")
        for ax in axes:
            for lbl in ax.get_xticklabels():
                lbl.set_rotation(30)
    fig.tight_layout(rect=(0, 0, 1, 0.97))
    fig.savefig(OUT, dpi=110, facecolor=BG)
    print("wrote", OUT)


if __name__ == "__main__":
    main()
