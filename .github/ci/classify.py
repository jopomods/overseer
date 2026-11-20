"""Which CI jobs a change needs (ADR-0015).

Reads changed paths (one per line) on stdin and prints lines for $GITHUB_OUTPUT: one
`name=true|false` per job, `build=` (anything but docs), `workflows=` (a workflow or action
changed) and `suites=` (JSON list of the GameTest runs). Unknown paths need everything: when in doubt, test more.

`base` and `profiler` load the same mods (none), so CI runs them in one server as `bare`
(ADR-0023); the rules still name them separately.

    git diff --name-only origin/main...HEAD | python3 .github/ci/classify.py
    python3 .github/ci/classify.py --all-suites     # suites= line with every CI run
"""
import re
import sys

SUITES = ["base", "profiler", "flux", "ae2", "teams", "ae2-teams"]
JOBS = ["web", "build_web"] + [f"gt_{s.replace('-', '_')}" for s in SUITES]

PLATFORM = "platform/forge-1.20.1/"
GAMETEST = PLATFORM + "src/gametest/java/io/github/jopojp/overseer/forge/gametest/"
INTEGRATION = PLATFORM + "src/main/java/io/github/jopojp/overseer/forge/integration/"

# Integration -> suites that must run when it changes. AE2 access uses FTB Teams parties, so a
# change to teams also runs ae2-teams; an AE2 change runs ae2 and ae2-teams.
INTEGRATION_SUITES = {
    "flux": {"base", "flux"},
    "ae2": {"base", "ae2", "ae2-teams"},
    "teams": {"base", "teams", "ae2-teams"},
    "profiler": {"base", "profiler"},
    "server": {"base"},
}
TEST_CLASS_SUITES = {
    "FluxGameTests.java": {"flux"},
    "Ae2GameTests.java": {"ae2"},
    "Ae2ActionGameTests.java": {"ae2"},
    "TeamGameTests.java": {"teams"},
    "Ae2TeamGameTests.java": {"ae2-teams"},
    "ProfilerGameTests.java": {"profiler"},
    "IconGameTests.java": {"base", "ae2"},
    "ServerGameTests.java": set(SUITES),
    "GameTestSuites.java": set(SUITES),
}
EVERYTHING = set(SUITES) | {"web", "build_web"}
BUILD_FILES = re.compile(
    r"^(gradlew(\.bat)?|gradle/.*|gradle\.properties|settings\.gradle\.kts|build\.gradle\.kts"
    r"|[^/]+/build\.gradle\.kts|platform/[^/]+/build\.gradle\.kts|version\.txt|\.github/.*)$")
DOCS = re.compile(r"^(docs/.*|[^/]+\.md|LICENSE|assets/.*\.(svg|png))$")
WORKFLOWS = re.compile(r"^\.github/(workflows|actions)/.*$")
# GameTest suites with the same mods share one server in CI.
CI_RUNS = {"base": "bare", "profiler": "bare"}


def ci_suites(suites) -> list[str]:
    """The GameTest runs CI starts for these suites, in a stable order."""
    runs: list[str] = []
    for suite in SUITES:
        if suite in suites:
            run = CI_RUNS.get(suite, suite)
            if run not in runs:
                runs.append(run)
    return runs


def suites_line(suites) -> str:
    return "suites=[" + ",".join(f'"{s}"' for s in ci_suites(suites)) + "]"


def needs(path: str) -> set[str]:
    """What one changed path requires."""
    if DOCS.match(path):
        return set()
    if BUILD_FILES.match(path):
        return EVERYTHING
    if path.startswith("web/"):
        return {"web", "build_web"}
    if path == "api/openapi.yaml":
        return EVERYTHING
    if path.startswith("core/"):
        return EVERYTHING
    if path.startswith(GAMETEST):
        return TEST_CLASS_SUITES.get(path[len(GAMETEST):].split("/")[0], set(SUITES))
    if path.startswith(INTEGRATION):
        package = path[len(INTEGRATION):].split("/")[0]
        if package in INTEGRATION_SUITES:
            return INTEGRATION_SUITES[package]
        return set(SUITES)  # Integrations.java and friends: every integration
    if path.startswith(PLATFORM):
        return set(SUITES)  # lifecycle, mixin, icons, client, commands, resources
    return EVERYTHING  # unknown path: test everything


def classify(paths: list[str]) -> dict[str, bool]:
    required: set[str] = set()
    for path in paths:
        required |= needs(path.strip())
    result = {"web": "web" in required, "build_web": "build_web" in required}
    for suite in SUITES:
        result[f"gt_{suite.replace('-', '_')}"] = suite in required
    return result


if __name__ == "__main__":
    if sys.argv[1:] == ["--all-suites"]:
        print(suites_line(SUITES))
        sys.exit(0)
    lines = [line.strip() for line in sys.stdin.read().splitlines() if line.strip()]
    result = classify(lines)
    for name, value in result.items():
        print(f"{name}={'true' if value else 'false'}")
    print(f"build={'true' if any(result.values()) else 'false'}")
    print(f"workflows={'true' if any(WORKFLOWS.match(p) for p in lines) else 'false'}")
    print(suites_line([s for s in SUITES if result[f"gt_{s.replace('-', '_')}"]]))
