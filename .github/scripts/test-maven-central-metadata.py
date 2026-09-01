#!/usr/bin/env python3

import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
HELPER = ROOT / "buildSrc/private/src/main/kotlin/org/jetbrains/androidx/build/MavenUploadHelper.kt"
CENTRAL = (
    ROOT
    / "buildSrc/private/src/main/kotlin/org/jetbrains/androidx/build/MavenCentralPublication.kt"
)
DESKTOP = ROOT / "compose/desktop/desktop/build.gradle"
ATTRIBUTES = ROOT / ".gitattributes"
JAVADOC_README = (
    ROOT / "buildSrc/private/src/main/resources/maven-central-javadoc/README.md"
)
ALLOWED_LICENSES = ROOT / "buildSrc/allowedLicenses"


class MavenCentralMetadataTest(unittest.TestCase):
    def test_helper_uses_fork_metadata_and_reproducible_documentation(self) -> None:
        text = HELPER.read_text(encoding="utf-8") + CENTRAL.read_text(encoding="utf-8")
        for expected in (
            "https://github.com/archivesteak/compose-multiplatform-core",
            "scm:git:https://github.com/archivesteak/compose-multiplatform-core.git",
            "scm:git:ssh://git@github.com/archivesteak/compose-multiplatform-core.git",
            "dev.id.set(FORK_DEVELOPER_ID)",
            "dev.name.set(FORK_DEVELOPER_NAME)",
            "dev.url.set(FORK_DEVELOPER_URL)",
            "effectiveMavenDescription(",
            "artifact.classifier.isNullOrBlank()",
            "task.isPreserveFileTimestamps = false",
            "task.isReproducibleFileOrder = true",
            "task.entryCompression = ZipEntryCompression.STORED",
            'task.filePermissions { permissions -> permissions.unix("0644") }',
            'task.dirPermissions { permissions -> permissions.unix("0755") }',
            "task.from(rootProject.layout.projectDirectory.file(JAVADOC_README_PATH))",
            'internal const val FORK_DEVELOPER_NAME = "Jack Harrington"',
        ):
            self.assertIn(expected, text)
        self.assertNotIn("pom.description.set(extension.description)", text)

    def test_javadoc_source_is_explicitly_lf_normalized(self) -> None:
        attributes = ATTRIBUTES.read_text(encoding="utf-8")
        self.assertIn(
            "buildSrc/private/src/main/resources/maven-central-javadoc/README.md text eol=lf",
            attributes.splitlines(),
        )
        self.assertIn(
            "buildSrc/allowedLicenses/**/LICENSE.txt text eol=lf",
            attributes.splitlines(),
        )
        readme = JAVADOC_README.read_bytes()
        self.assertTrue(readme.endswith(b"\n"))
        self.assertNotIn(b"\r", readme)
        self.assertIn(b"Compose Multiplatform fork documentation", readme)
        for license_file in ALLOWED_LICENSES.glob("*/LICENSE.txt"):
            with self.subTest(license=license_file.parent.name):
                self.assertNotIn(b"\r", license_file.read_bytes())

    def test_publication_artifacts_are_inspected_after_project_evaluation(self) -> None:
        central = CENTRAL.read_text(encoding="utf-8")
        projects_evaluated = central.index("gradle.projectsEvaluated {")
        artifact_inspection = central.index("publications.configureEach")
        self.assertGreater(artifact_inspection, projects_evaluated)

    def test_compose_desktop_has_an_explicit_description(self) -> None:
        text = DESKTOP.read_text(encoding="utf-8")
        self.assertIn(
            'description = "Compose Multiplatform APIs and runtime integration for desktop applications"',
            text,
        )


if __name__ == "__main__":
    unittest.main()
