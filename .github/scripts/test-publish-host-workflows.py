#!/usr/bin/env python3

from __future__ import annotations

import re
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
WORKFLOWS = {
    "apple": REPOSITORY / ".github" / "workflows" / "publish-apple.yml",
    "web": REPOSITORY / ".github" / "workflows" / "publish-web-android.yml",
    "windows": REPOSITORY / ".github" / "workflows" / "publish-windows.yml",
}


class PublishHostWorkflowsTest(unittest.TestCase):
    def texts(self) -> dict[str, str]:
        return {name: path.read_text(encoding="utf-8") for name, path in WORKFLOWS.items()}

    def test_producers_are_exact_source_pinned_artifact_only_jobs(self) -> None:
        expected = {
            "apple": ({"compose_ref", "skiko_ref"}, "maven-apple"),
            "web": ({"compose_ref", "skiko_ref"}, "maven-web-android"),
            "windows": ({"compose_ref", "skia_ref", "skiko_ref"}, "maven-windows"),
        }
        for owner, text in self.texts().items():
            with self.subTest(owner=owner):
                inputs = text.split("    inputs:\n", 1)[1].split("\npermissions:", 1)[0]
                declared = set(re.findall(r"^      ([a-z_]+):$", inputs, re.MULTILINE))
                self.assertEqual(expected[owner][0], declared)
                self.assertNotIn("default:", inputs)
                self.assertIn("must be a full lowercase 40-character commit SHA", text)
                self.assertIn("persist-credentials: false", text)
                self.assertIn("permissions:\n  contents: read", text)
                self.assertIn(f"--owner {owner}", text)
                self.assertIn(f"name: {expected[owner][1]}", text)
                self.assertIn("retention-days: 7", text)
                self.assertNotIn("ossrh", text.lower())
                self.assertNotIn("maven central", text.lower())

    def test_compose_publication_uses_only_host_closure_aggregates(self) -> None:
        texts = self.texts()
        expected_tasks = {
            "apple": ":mpp:publishAppleConsumerClosureToMavenLocal",
            "web": ":mpp:publishWebAndroidConsumerClosureToMavenLocal",
            "windows": ":mpp:publishWindowsConsumerClosureToMavenLocal",
        }
        for owner, task in expected_tasks.items():
            with self.subTest(owner=owner):
                self.assertIn(task, texts[owner])
                self.assertIn("MAVEN_REPOSITORY:", texts[owner])
                self.assertIn("-Dmaven.repo.local=", texts[owner])
        self.assertTrue(
            all("publishComposeJbToMavenLocal" not in text for text in texts.values())
        )

    def test_skiko_scope_and_linux_common_metadata_fix_are_explicit(self) -> None:
        texts = self.texts()
        for owner, text in texts.items():
            with self.subTest(owner=owner):
                self.assertIn(":publishToMavenLocal", text)
        web = texts["web"]
        web_targets = web.split(
            "- name: Build and publish skiko (js + wasmJs + Android)", 1
        )[1].split("- name: Verify skiko web and Android publications", 1)[0]
        linux_targets = web.split(
            "- name: Build and publish skiko (linuxX64 + linuxArm64)", 1
        )[1].split("- name: Merge and verify skiko Linux module metadata", 1)[0]
        self.assertIn("-Pskiko.awt.enabled=false", web_targets)
        self.assertNotIn("-Pskiko.awt.enabled=true", web_targets)
        self.assertIn("-Pskiko.awt.enabled=true", linux_targets)
        self.assertIn("publishLinuxArm64PublicationToMavenLocal", web)
        self.assertIn("publishLinuxX64PublicationToMavenLocal", web)

    def test_fork_coordinates_and_provenance_contract_match(self) -> None:
        texts = self.texts()
        for owner, text in texts.items():
            with self.subTest(owner=owner):
                self.assertNotIn(":1.1.1-mingw", text)
                self.assertIn(":1.1.1-beta01-mingw", text)
                self.assertIn(f"provenance/{owner}.json", text)
                self.assertIn("--source \"compose=", text)
        self.assertIn("--source \"skia=", texts["windows"])
        self.assertNotIn("--source \"skia=", texts["apple"])
        self.assertNotIn("--source \"skia=", texts["web"])


if __name__ == "__main__":
    unittest.main()
