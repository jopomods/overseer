"""Timings of CI runs, for before/after comparisons (ADR-0023).

    python3 .github/ci/timings.py <run id> [<run id> ...]

Per run: wall time (first job start to last job end), the slowest job, the sum of job times and
the billed minutes (GitHub rounds every job up to a full minute; free for public repositories,
but a fair measure of runner use). Per job: duration and its longest step. Needs `gh`.
"""
import json
import math
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


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    for arg in sys.argv[1:]:
        report(arg)
