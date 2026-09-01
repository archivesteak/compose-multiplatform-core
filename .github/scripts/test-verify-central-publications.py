#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import pathlib
import sys
import tempfile
import unittest
import zipfile


SCRIPT_PATH = pathlib.Path(__file__).with_name("verify-central-publications.py")
SPEC = importlib.util.spec_from_file_location("verify_central_publications", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


POM_TEMPLATE = """\
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>{group}</groupId>
  <artifactId>{artifact}</artifactId>
  <version>{version}</version>
  {packaging}
  <name>Example publication</name>
  <description>Example Compose Multiplatform publication</description>
  <url>https://github.com/archivesteak/compose-multiplatform-core</url>
  <licenses>
    <license>
      <name>The Apache License, Version 2.0</name>
      <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
    </license>
  </licenses>
  <developers>
    <developer>
      <id>archivesteak</id>
      <name>Jack Harrington</name>
      <url>https://github.com/archivesteak</url>
    </developer>
  </developers>
  <scm>
    <url>https://github.com/archivesteak/compose-multiplatform-core</url>
    <connection>scm:git:https://github.com/archivesteak/compose-multiplatform-core.git</connection>
    <developerConnection>scm:git:ssh://git@github.com/archivesteak/compose-multiplatform-core.git</developerConnection>
  </scm>
</project>
"""


class VerifyCentralPublicationsTest(unittest.TestCase):
    def write_zip(self, path: pathlib.Path, entry: str, contents: str = "content") -> None:
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(entry, contents)

    def write_publication(
        self,
        repository: pathlib.Path,
        *,
        artifact: str = "example",
        packaging: str | None = "jar",
        primary: bool = True,
        sources: bool = True,
        javadoc: bool = True,
    ) -> pathlib.Path:
        group = "io.github.archivesteak.compose.test"
        version = "1.0.0-mingw"
        directory = repository.joinpath(*group.split("."), artifact, version)
        directory.mkdir(parents=True)
        packaging_xml = "" if packaging is None else f"<packaging>{packaging}</packaging>"
        pom = directory / f"{artifact}-{version}.pom"
        pom.write_text(
            POM_TEMPLATE.format(
                group=group,
                artifact=artifact,
                version=version,
                packaging=packaging_xml,
            ),
            encoding="utf-8",
        )
        if primary:
            extension = MODULE.PRIMARY_EXTENSIONS.get(packaging or "jar", ".jar")
            self.write_zip(directory / f"{artifact}-{version}{extension}", "payload.bin")
        if sources:
            self.write_zip(
                directory / f"{artifact}-{version}-sources.jar",
                "commonMain/Example.kt",
            )
        if javadoc:
            self.write_zip(directory / f"{artifact}-{version}-javadoc.jar", "README.md")
        return pom

    def assert_fails(self, repository: pathlib.Path, expected: str) -> None:
        with self.assertRaises(MODULE.VerificationError) as caught:
            MODULE.verify_repository(repository)
        self.assertIn(expected, str(caught.exception))

    def test_valid_binary_and_explicit_pom_only_publications_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = pathlib.Path(directory)
            self.write_publication(repository, artifact="binary")
            self.write_publication(
                repository,
                artifact="redirect",
                packaging="pom",
                primary=False,
                sources=False,
                javadoc=False,
            )

            summary = MODULE.verify_repository(repository)

            self.assertEqual(2, summary.publications)
            self.assertEqual(1, summary.binary_publications)
            self.assertEqual(1, summary.pom_only_publications)

    def test_only_an_explicit_pom_packaging_is_exempt(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = pathlib.Path(directory)
            self.write_publication(
                repository,
                packaging=None,
                primary=False,
                sources=False,
                javadoc=False,
            )
            self.assert_fails(repository, "missing or empty primary artifact")

        with tempfile.TemporaryDirectory() as directory:
            repository = pathlib.Path(directory)
            pom = self.write_publication(
                repository,
                packaging="pom",
                primary=False,
                sources=False,
                javadoc=False,
            )
            (pom.parent / "example-1.0.0-mingw-extra.zip").write_bytes(b"unexpected")
            self.assert_fails(repository, "packaging=pom publication is not POM-only")

    def test_required_metadata_and_full_scm_triple_are_enforced(self) -> None:
        replacements = {
            "name": ("<name>Example publication</name>", "<name> </name>"),
            "description": (
                "<description>Example Compose Multiplatform publication</description>",
                "<description> </description>",
            ),
            "project URL": (
                "<url>https://github.com/archivesteak/compose-multiplatform-core</url>",
                "<url>https://github.com/upstream/project</url>",
            ),
            "license": (
                "<name>The Apache License, Version 2.0</name>",
                "<name> </name>",
            ),
            "developer": ("<id>archivesteak</id>", "<id> </id>"),
            "SCM connection": (
                "<connection>scm:git:https://github.com/archivesteak/compose-multiplatform-core.git</connection>",
                "<connection> </connection>",
            ),
            "SCM developer connection": (
                "<developerConnection>scm:git:ssh://git@github.com/archivesteak/compose-multiplatform-core.git</developerConnection>",
                "<developerConnection> </developerConnection>",
            ),
        }
        for label, (before, after) in replacements.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                repository = pathlib.Path(directory)
                pom = self.write_publication(repository)
                text = pom.read_text(encoding="utf-8")
                self.assertIn(before, text)
                pom.write_text(text.replace(before, after, 1), encoding="utf-8")
                self.assert_fails(repository, label.split()[0].lower())

    def test_repository_urls_are_anchored_and_scm_repositories_must_match(self) -> None:
        replacements = {
            "query injection": (
                "https://github.com/archivesteak/compose-multiplatform-core",
                "https://evil.example/?next=github.com/archivesteak/repo",
            ),
            "hostname suffix": (
                "scm:git:https://github.com/archivesteak/compose-multiplatform-core.git",
                "scm:git:https://evilgithub.com/archivesteak/repo.git",
            ),
            "repository mismatch": (
                "scm:git:ssh://git@github.com/archivesteak/compose-multiplatform-core.git",
                "scm:git:ssh://git@github.com/archivesteak/different-repository.git",
            ),
        }
        for label, (before, after) in replacements.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                repository = pathlib.Path(directory)
                pom = self.write_publication(repository)
                text = pom.read_text(encoding="utf-8")
                self.assertIn(before, text)
                pom.write_text(text.replace(before, after, 1), encoding="utf-8")
                self.assert_fails(repository, "repository")

    def test_sources_and_javadoc_must_be_valid_nonempty_archives(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = pathlib.Path(directory)
            pom = self.write_publication(repository, sources=False)
            self.assert_fails(repository, "missing example-1.0.0-mingw-sources.jar")

            sources = pom.parent / "example-1.0.0-mingw-sources.jar"
            sources.write_bytes(b"not a zip")
            self.assert_fails(repository, "is not a valid ZIP archive")

            sources.unlink()
            self.write_zip(sources, "commonMain/Example.kt")
            javadoc = pom.parent / "example-1.0.0-mingw-javadoc.jar"
            javadoc.unlink()
            with zipfile.ZipFile(javadoc, "w"):
                pass
            self.assert_fails(repository, "has no nonempty payload entry")

    def test_every_host_workflow_runs_the_gate_before_upload(self) -> None:
        workflows = SCRIPT_PATH.parents[1] / "workflows"
        for name in ("publish-windows.yml", "publish-apple.yml", "publish-web-android.yml"):
            with self.subTest(workflow=name):
                text = (workflows / name).read_text(encoding="utf-8")
                verifier = text.find("verify-central-publications.py")
                upload = text.find("actions/upload-artifact@")
                self.assertNotEqual(-1, verifier)
                self.assertNotEqual(-1, upload)
                self.assertLess(verifier, upload)
                self.assertEqual(1, text.count("verify-central-publications.py"))


if __name__ == "__main__":
    unittest.main()
