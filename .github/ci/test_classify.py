"""Fixtures for the path rules (ADR-0015). Run: python3 -m unittest discover -s .github/ci"""
import unittest

from classify import SUITES, classify

ALL = {f"gt_{s.replace('-', '_')}" for s in SUITES} | {"web", "build_web"}


def on(paths):
    return {name for name, value in classify(paths).items() if value}


class PathRules(unittest.TestCase):
    def test_docs_need_nothing(self):
        self.assertEqual(on(["docs/ROADMAP.md", "README.md", "docs/adr/0014-tick-profiler.md"]), set())

    def test_web_needs_web_and_the_jar(self):
        self.assertEqual(on(["web/src/pages/grid.tsx"]), {"web", "build_web"})

    def test_core_and_contract_need_everything(self):
        self.assertEqual(on(["core/src/main/java/x/Y.java"]), ALL)
        self.assertEqual(on(["api/openapi.yaml"]), ALL)

    def test_build_files_need_everything(self):
        for path in ["gradlew", "gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties",
                     "settings.gradle.kts", "platform/forge-1.20.1/build.gradle.kts", ".github/workflows/ci.yml"]:
            self.assertEqual(on([path]), ALL, path)

    def test_an_integration_needs_its_suites_and_base(self):
        base = "platform/forge-1.20.1/src/main/java/io/github/jopojp/overseer/forge/integration/"
        self.assertEqual(on([base + "ae2/Ae2Reader.java"]), {"gt_base", "gt_ae2", "gt_ae2_teams"})
        self.assertEqual(on([base + "flux/FluxReader.java"]), {"gt_base", "gt_flux"})
        self.assertEqual(on([base + "teams/Teams.java"]), {"gt_base", "gt_teams", "gt_ae2_teams"})
        self.assertEqual(on([base + "Integrations.java"]), ALL - {"web", "build_web"})

    def test_a_test_class_needs_its_suite(self):
        base = "platform/forge-1.20.1/src/gametest/java/io/github/jopojp/overseer/forge/gametest/"
        self.assertEqual(on([base + "FluxGameTests.java"]), {"gt_flux"})
        self.assertEqual(on([base + "Ae2ActionGameTests.java"]), {"gt_ae2"})
        self.assertEqual(on([base + "GameTestSuites.java"]), ALL - {"web", "build_web"})

    def test_other_platform_code_needs_every_suite(self):
        self.assertEqual(on(["platform/forge-1.20.1/src/main/java/io/github/jopojp/overseer/forge/mixin/X.java"]),
                         ALL - {"web", "build_web"})

    def test_unknown_paths_need_everything(self):
        self.assertEqual(on(["scripts/new-tool.sh"]), ALL)
        self.assertEqual(on(["monitoring/dashboard.json"]), ALL)

    def test_changes_add_up(self):
        self.assertEqual(on(["web/src/x.tsx", "docs/a.md"]), {"web", "build_web"})


if __name__ == "__main__":
    unittest.main()
