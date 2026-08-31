#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("verify-workflow-supply-chain.py")
SPEC = importlib.util.spec_from_file_location("verify_workflow_supply_chain", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


CHECKOUT = "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1"
COMPILER_URL = (
    "https://github.com/JetBrains/kotlin/releases/download/v2.3.10/"
    "kotlin-compiler-2.3.10.zip"
)
COMPILER_SHA256 = "c8d546f9ff433b529fb0ad43feceb39831040cae2ca8d17e7df46364368c9a9e"
GSON_URL = (
    "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/"
    "gson-2.10.1.jar"
)
GSON_SHA256 = "4241c14a7727c34feea6507ec801318a3d4a90f070e4525681079fb94ee4c593"
WINLIBS_URL = (
    "https://netix.dl.sourceforge.net/project/winlibs-mingw/"
    "14.2.0posix-12.0.0-ucrt-r3/"
    "winlibs-x86_64-posix-seh-gcc-14.2.0-llvm-19.1.7-mingw-w64ucrt-12.0.0-r3.7z"
)
WINLIBS_SHA256 = "75bc86c76d085dbd193fe6ab7facb09bbfaf31cb02384c3aeda7050495d7c7d9"
EMSDK_URL = "https://github.com/emscripten-core/emsdk.git"
EMSDK_COMMIT = "c69d433d8509c5c64564c2f0d054bf102a5cf67e"
RELEASE_NOTES_SOURCE_COMMIT = "9a876be6c1c0f97162b93c152abde14a7493098d"


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def make_repository(root: Path) -> Path:
    repository = root / "repository"
    write(
        repository / ".github" / "workflows" / "check-release-notes.yml",
        f"""env:
  KOTLIN_COMPILER_SHA256: {COMPILER_SHA256}
  GSON_SHA256: {GSON_SHA256}
steps:
  - uses: {CHECKOUT}
    with:
      repository: JetBrains/compose-multiplatform
      ref: {RELEASE_NOTES_SOURCE_COMMIT}
  - run: |
      curl --output archive.zip '{COMPILER_URL}'
      curl --output gson.jar '{GSON_URL}'
  - run: |
      printf '%s  %s\\n%s  %s\\n' \\
        "$KOTLIN_COMPILER_SHA256" archive.zip \\
        "$GSON_SHA256" gson.jar | sha256sum --check --strict
      unzip archive.zip
      kotlin -classpath "$gson_jar" changelog.offline.main.kts
""",
    )
    write(
        repository / ".github" / "workflows" / "publish-web-android.yml",
        f"""steps:
  - run: |
      git clone {EMSDK_URL} emsdk
      test "$(git -C emsdk rev-parse HEAD)" = {EMSDK_COMMIT}
      ./emsdk/emsdk install 4.0.7
""",
    )
    write(
        repository / ".github" / "workflows" / "publish-windows.yml",
        f"""env:
  WINLIBS_SHA256: {WINLIBS_SHA256}
steps:
  - run: |
      curl --output winlibs.7z '{WINLIBS_URL}'
      printf '%s  %s\\n' "$WINLIBS_SHA256" winlibs.7z | sha256sum --check --strict
      7z x winlibs.7z
""",
    )
    write(
        repository / "gradle" / "wrapper" / "gradle-wrapper.properties",
        "distributionSha256Sum=" + "a" * 64 + "\n",
    )
    return repository


class VerifyWorkflowSupplyChainTest(unittest.TestCase):
    def test_valid_pinned_fixture_passes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = make_repository(Path(directory))
            self.assertEqual((1, 3, 1), VERIFIER.verify_repository(repository))

    def test_rejects_mutable_remote_action(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = make_repository(Path(directory))
            workflow = repository / ".github" / "workflows" / "mutable.yml"
            write(workflow, "steps:\n  - uses: actions/cache@v4\n")
            with self.assertRaisesRegex(ValueError, "not pinned to a full commit"):
                VERIFIER.verify_repository(repository)

    def test_rejects_mutable_action_inside_local_action(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = make_repository(Path(directory))
            workflow = repository / ".github" / "workflows" / "local.yml"
            write(workflow, "steps:\n  - uses: ./.github/actions/example\n")
            write(
                repository / ".github" / "actions" / "example" / "action.yml",
                "runs:\n  using: composite\n  steps:\n    - uses: actions/checkout@v4\n",
            )
            with self.assertRaisesRegex(ValueError, "not pinned to a full commit"):
                VERIFIER.verify_repository(repository)

    def test_rejects_unreviewed_external_action_even_when_commit_pinned(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = make_repository(Path(directory))
            workflow = repository / ".github" / "workflows" / "unknown.yml"
            write(workflow, "steps:\n  - uses: example/action@" + "b" * 40 + "\n")
            with self.assertRaisesRegex(ValueError, "has not been reviewed"):
                VERIFIER.verify_repository(repository)

    def test_rejects_unreviewed_download_in_another_workflow(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = make_repository(Path(directory))
            workflow = repository / ".github" / "workflows" / "download.yml"
            write(
                workflow,
                "steps:\n  - run: curl --output tool.zip "
                "https://example.invalid/tool.zip\n",
            )
            with self.assertRaisesRegex(ValueError, "unreviewed download command"):
                VERIFIER.verify_repository(repository)

    def test_rejects_unreviewed_powershell_download(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = make_repository(Path(directory))
            workflow = repository / ".github" / "workflows" / "download.yml"
            write(
                workflow,
                "steps:\n  - run: Invoke-RestMethod "
                "https://example.invalid/tool.zip -OutFile tool.zip\n",
            )
            with self.assertRaisesRegex(ValueError, "unreviewed download command"):
                VERIFIER.verify_repository(repository)

    def test_rejects_archive_without_strict_digest_verification(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = make_repository(Path(directory))
            workflow = repository / ".github" / "workflows" / "check-release-notes.yml"
            text = workflow.read_text(encoding="utf-8")
            workflow.write_text(
                text.replace("sha256sum --check --strict", "echo unchecked"),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "not strictly SHA-256 verified"):
                VERIFIER.verify_repository(repository)

    def test_rejects_dependency_jar_without_exact_digest_anchor(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = make_repository(Path(directory))
            workflow = repository / ".github" / "workflows" / "check-release-notes.yml"
            text = workflow.read_text(encoding="utf-8")
            workflow.write_text(text.replace(GSON_SHA256, "0" * 64), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "does not match its trust anchor"):
                VERIFIER.verify_repository(repository)

    def test_rejects_windows_toolchain_without_exact_digest_anchor(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = make_repository(Path(directory))
            workflow = repository / ".github" / "workflows" / "publish-windows.yml"
            text = workflow.read_text(encoding="utf-8")
            workflow.write_text(text.replace(WINLIBS_SHA256, "0" * 64), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "does not match its trust anchor"):
                VERIFIER.verify_repository(repository)

    def test_rejects_mutable_release_note_checker_source(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = make_repository(Path(directory))
            workflow = repository / ".github" / "workflows" / "check-release-notes.yml"
            text = workflow.read_text(encoding="utf-8")
            workflow.write_text(
                text.replace(RELEASE_NOTES_SOURCE_COMMIT, "master"),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "source is not commit-pinned"):
                VERIFIER.verify_repository(repository)

    def test_rejects_archive_consumed_before_digest_verification(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = make_repository(Path(directory))
            workflow = repository / ".github" / "workflows" / "check-release-notes.yml"
            text = workflow.read_text(encoding="utf-8")
            text = text.replace(
                "printf '%s  %s\\n%s  %s\\n' \\\n"
                "        \"$KOTLIN_COMPILER_SHA256\" archive.zip \\\n"
                "        \"$GSON_SHA256\" gson.jar | sha256sum --check --strict\n"
                "      unzip archive.zip",
                "unzip archive.zip\n"
                "      printf '%s  %s\\n%s  %s\\n' \\\n"
                "        \"$KOTLIN_COMPILER_SHA256\" archive.zip \\\n"
                "        \"$GSON_SHA256\" gson.jar | sha256sum --check --strict",
            )
            workflow.write_text(text, encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "downloaded, verified, then consumed"):
                VERIFIER.verify_repository(repository)

    def test_rejects_cloned_repository_without_commit_verification(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = make_repository(Path(directory))
            workflow = repository / ".github" / "workflows" / "publish-web-android.yml"
            text = workflow.read_text(encoding="utf-8")
            workflow.write_text(text.replace(EMSDK_COMMIT, "unverified"), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "not commit-verified"):
                VERIFIER.verify_repository(repository)

    def test_rejects_reintroduction_of_privileged_junie_workflow(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = make_repository(Path(directory))
            write(
                repository / ".github" / "workflows" / "junie.yml",
                "name: Junie\n",
            )
            with self.assertRaisesRegex(ValueError, "Junie workflow must remain removed"):
                VERIFIER.verify_repository(repository)


if __name__ == "__main__":
    unittest.main()
