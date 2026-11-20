"""Timings of CI runs, for before/after comparisons (ADR-0023).

    python3 .github/ci/timings.py <run id> [<run id> ...]
    python3 .github/ci/timings.py --compare before=<id>,<id>,<id> after=<id>,<id>,<id>

Per run: wall time (first job start to last job end), the slowest job, the sum of job times and
the billed minutes (GitHub rounds every job up to a full minute; free for public repositories,
but a fair measure of runner use). Per job: duration and its longest step. Needs `gh`.

--compare takes groups of runs of the same scope (e.g. manual runs, which run every job) and
prints the median, minimum and maximum of each measure per group: single runs vary by 20-30 s,
so a claim that something got faster needs several runs on each side.
"""
import json
import math
import statistics
import subprocess
import sys
from datetime import datetime


def ts(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def jobs(run_id: str) -> list[dict]:
    out = subprocess.run(
        ["gh", "api", f"repos/{{owner}}/{{repo}}/actions/runs/{run_id}/jobs?per_page=100"],
        check=True, capture_output=True, text=True).stdout
    return [j for j in json.loads(out)["jobs"] if j.get("started_at") and j.get("completed_at")
            and j["conclusion"] != "skipped"]


def report(run_id: str) -> None:
    runs = jobs(run_id)
    if not runs:
        print(f"run {run_id}: no finished jobs")
        return
    durations = {j["name"]: (ts(j["completed_at"]) - ts(j["started_at"])).total_seconds() for j in runs}
    wall = (max(ts(j["completed_at"]) for j in runs) - min(ts(j["started_at"]) for j in runs)).total_seconds()
    billed = sum(max(1, math.ceil(d / 60)) for d in durations.values())
    slowest = max(durations, key=durations.get)
    print(f"run {run_id}: wall {wall / 60:.1f} min, {len(runs)} jobs, job time {sum(durations.values()) / 60:.1f} min, "
          f"billed {billed} min, slowest: {slowest} ({durations[slowest]:.0f} s)")
    for j in sorted(runs, key=lambda j: -durations[j["name"]]):
        steps = [(s["name"], (ts(s["completed_at"]) - ts(s["started_at"])).total_seconds())
                 for s in j.get("steps", []) if s.get("started_at") and s.get("completed_at")]
        top = max(steps, key=lambda s: s[1]) if steps else ("-", 0)
        print(f"  {durations[j['name']]:5.0f} s  {j['name']:40}  longest step: {top[0][:50]} ({top[1]:.0f} s)")


def measures(run_id: str) -> dict[str, float]:
    """Seconds per measure: wall, job time, every job and every job's steps of 3 s or more."""
    runs = jobs(run_id)
    out: dict[str, float] = {}
    for j in runs:
        out[f"job  {j['name']}"] = (ts(j["completed_at"]) - ts(j["started_at"])).total_seconds()
        for s in j.get("steps", []):
            if s.get("started_at") and s.get("completed_at"):
                d = (ts(s["completed_at"]) - ts(s["started_at"])).total_seconds()
                if d >= 3:
                    out[f"step {j['name']} / {s['name'][:45]}"] = d
    out["wall"] = (max(ts(j["completed_at"]) for j in runs) - min(ts(j["started_at"]) for j in runs)).total_seconds()
    out["job time"] = sum(v for k, v in out.items() if k.startswith("job  "))
    return out


def compare(groups: list[str]) -> None:
    named = [(g.split("=", 1)[0], g.split("=", 1)[1].split(",")) for g in groups]
    data = {name: [measures(r) for r in ids] for name, ids in named}
    keys = ["wall", "job time"] + sorted({k for runs in data.values() for m in runs for k in m}
                                         - {"wall", "job time"})
    head = "".join(f"{name + f' (n={len(data[name])})':>24}" for name, _ in named)
    print(f"{'measure (s): median [min-max]':70}{head}")
    for k in keys:
        cells = []
        for name, _ in named:
            values = [m[k] for m in data[name] if k in m]
            cells.append(f"{statistics.median(values):6.0f} [{min(values):.0f}-{max(values):.0f}]"
                         if values else "-")
        print(f"{k[:70]:70}" + "".join(f"{c:>24}" for c in cells))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    if sys.argv[1] == "--compare":
        compare(sys.argv[2:])
    else:
        for arg in sys.argv[1:]:
            report(arg)
