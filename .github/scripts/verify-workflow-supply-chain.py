#!/usr/bin/env python3
"""Verify immutable GitHub Actions and direct workflow download trust anchors."""

from __future__ import annotations

import argparse
import re
from pathlib import Path
from typing import NamedTuple


IMMUTABLE_ACTION = re.compile(
    r"^[^/@\s]+/[^/@\s]+(?:/[^@\s]+)*@[0-9a-fA-F]{40}$"
)
IMMUTABLE_DOCKER_IMAGE = re.compile(r"^docker://.+@sha256:[0-9a-fA-F]{64}$")
USES_LINE = re.compile(r"^\s*(?:-\s*)?uses:\s*([^\s#]+)")
DOWNLOAD_COMMAND = re.compile(
    r"(?:\b(?:curl|wget|aria2c|Invoke-WebRequest|Invoke-RestMethod|"
    r"Start-BitsTransfer)\b|\bgh\s+release\s+download\b)",
    re.IGNORECASE,
)
GIT_CLONE_COMMAND = re.compile(r"\bgit\s+clone\b")


# Every external action is both content-addressed and reviewed as a non-composite action at
# this exact revision. A new external action must be added here deliberately after its action
# manifest has been inspected; reusable composite logic belongs in .github/actions instead.
AUDITED_EXTERNAL_ACTIONS = frozenset(
    {
        "actions/cache@0057852bfaa89a56745cba8c7296529d2fc39830",
        "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1",
        "actions/setup-java@dd06d9cba3e5552c54d9f8ea23572deb30010f7c",
        "actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a",
        "android-actions/setup-android@40fd30fb8d7440372e1316f5d1809ec01dcd3699",
        "browser-actions/setup-chrome@48ad923757ca74d66703209fe939badbdf80f2f4",
        "browser-actions/setup-firefox@0bc507ddf224827e3b1af68e014d5e42ab93e795",
        "gradle/actions/setup-gradle@9c971963bec38e04b3d30dcc455b5382be2fdbfb",
        "irgaly/xcode-cache@4141f139f00e335c6e1031fb93e667181f86146f",
        "test-summary/action@37b508cfee6d4d080eedd00b5bb240a6a784a6a5",
    }
)
AUDITED_DOCKER_ACTIONS: frozenset[str] = frozenset()


class ArchivePin(NamedTuple):
    url: str
    sha256: str
    sha256_environment: str
    consumer: str


class GitPin(NamedTuple):
    url: str
    commit: str
    consumer: str


# GitHub publishes the Kotlin asset's SHA-256 directly (80,764,672 bytes). Maven
# Central publishes Gson's SHA-1; the downloaded 283,367-byte payload was first
# matched to that checksum, then independently hashed with SHA-256 for this anchor.
PINNED_ARCHIVES = {
    ".github/workflows/check-release-notes.yml": (
        ArchivePin(
            url=(
                "https://github.com/JetBrains/kotlin/releases/download/v2.3.10/"
                "kotlin-compiler-2.3.10.zip"
            ),
            sha256=(
                "c8d546f9ff433b529fb0ad43feceb39831040cae2ca8d17e7df46364368c9a9e"
            ),
            sha256_environment="KOTLIN_COMPILER_SHA256",
            consumer="unzip",
        ),
        ArchivePin(
            url=(
                "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/"
                "gson-2.10.1.jar"
            ),
            sha256=(
                "4241c14a7727c34feea6507ec801318a3d4a90f070e4525681079fb94ee4c593"
            ),
            sha256_environment="GSON_SHA256",
            consumer='-classpath "$gson_jar"',
        ),
    ),
    ".github/workflows/publish-windows.yml": (
        ArchivePin(
            url=(
                "https://netix.dl.sourceforge.net/project/winlibs-mingw/"
                "14.2.0posix-12.0.0-ucrt-r3/"
                "winlibs-x86_64-posix-seh-gcc-14.2.0-llvm-19.1.7-"
                "mingw-w64ucrt-12.0.0-r3.7z"
            ),
            sha256=(
                "75bc86c76d085dbd193fe6ab7facb09bbfaf31cb02384c3aeda7050495d7c7d9"
            ),
            sha256_environment="WINLIBS_SHA256",
            consumer="7z x",
        ),
    ),
}

PINNED_GIT_REPOSITORIES = {
    ".github/workflows/publish-web-android.yml": GitPin(
        url="https://github.com/emscripten-core/emsdk.git",
        commit="c69d433d8509c5c64564c2f0d054bf102a5cf67e",
        consumer="./emsdk/emsdk install",
    )
}

RELEASE_NOTES_SOURCE = "JetBrains/compose-multiplatform"
RELEASE_NOTES_SOURCE_COMMIT = "9a876be6c1c0f97162b93c152abde14a7493098d"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "repository",
        nargs="?",
        default=Path.cwd(),
        type=Path,
        help="Repository root to verify (defaults to the current directory)",
    )
    return parser.parse_args()


def workflow_sources(repository: Path) -> list[Path]:
    workflows = repository / ".github" / "workflows"
    actions = repository / ".github" / "actions"
    paths = [
        path
        for path in workflows.glob("*")
        if path.is_file() and path.suffix in {".yml", ".yaml"}
    ]
    paths.extend(
        path
        for path in actions.rglob("*")
        if path.is_file() and path.name in {"action.yml", "action.yaml"}
    )
    return sorted(paths)


def relative(path: Path, repository: Path) -> str:
    return path.resolve().relative_to(repository.resolve()).as_posix()


def active_command_lines(text: str, pattern: re.Pattern[str]) -> list[str]:
    return [
        line
        for line in text.splitlines()
        if not line.lstrip().startswith("#") and pattern.search(line)
    ]


def active_text(text: str) -> str:
    return "\n".join(
        line for line in text.splitlines() if not line.lstrip().startswith("#")
    )


def verify_local_reference(reference: str, repository: Path, source: Path) -> None:
    target = (repository / reference[2:]).resolve()
    try:
        target.relative_to(repository.resolve())
    except ValueError as error:
        raise ValueError(f"{source}: local action escapes the repository: {reference}") from error

    if target.is_file() and target.suffix in {".yml", ".yaml"}:
        return
    manifests = [target / "action.yml", target / "action.yaml"]
    existing = [manifest for manifest in manifests if manifest.is_file()]
    if len(existing) != 1:
        raise ValueError(
            f"{source}: local action {reference} must contain exactly one action manifest"
        )


def verify_action_references(repository: Path, sources: list[Path]) -> int:
    references = 0
    for source in sources:
        for line_number, line in enumerate(
            source.read_text(encoding="utf-8").splitlines(), 1
        ):
            match = USES_LINE.match(line)
            if match is None:
                continue
            references += 1
            reference = match.group(1).strip("'\"")
            if reference.startswith("./"):
                verify_local_reference(reference, repository, source)
            elif reference.startswith("docker://"):
                if IMMUTABLE_DOCKER_IMAGE.fullmatch(reference) is None:
                    raise ValueError(
                        f"{source}:{line_number}: Docker action is not digest-pinned: "
                        f"{reference}"
                    )
                if reference not in AUDITED_DOCKER_ACTIONS:
                    raise ValueError(
                        f"{source}:{line_number}: Docker action has not been reviewed: "
                        f"{reference}"
                    )
            elif IMMUTABLE_ACTION.fullmatch(reference) is None:
                raise ValueError(
                    f"{source}:{line_number}: action is not pinned to a full commit: "
                    f"{reference}"
                )
            elif reference not in AUDITED_EXTERNAL_ACTIONS:
                raise ValueError(
                    f"{source}:{line_number}: external action has not been reviewed: "
                    f"{reference}"
                )
    return references


def verify_archive_downloads(repository: Path, sources: list[Path]) -> int:
    downloads = 0
    seen_paths: set[str] = set()
    for source in sources:
        text = source.read_text(encoding="utf-8")
        commands = active_command_lines(text, DOWNLOAD_COMMAND)
        if not commands:
            continue
        source_name = relative(source, repository)
        pins = PINNED_ARCHIVES.get(source_name)
        if pins is None or len(commands) != len(pins):
            raise ValueError(
                f"{source}: found {len(commands)} unreviewed download command(s)"
            )
        seen_paths.add(source_name)
        downloads += len(pins)
        commands_text = active_text(text)

        if re.search(r"\b(?:curl|wget)\b[^\n]*\|\s*(?:ba)?sh\b", commands_text):
            raise ValueError(f"{source}: downloaded content must never be piped to a shell")
        if "sha256sum --check --strict" not in commands_text:
            raise ValueError(f"{source}: downloaded archive is not strictly SHA-256 verified")

        verify_at = commands_text.index("sha256sum --check --strict")
        for pin in pins:
            assignment = f"{pin.sha256_environment}: {pin.sha256}"
            verification_reference = f'"${pin.sha256_environment}"'
            if (
                pin.url not in commands_text
                or assignment not in commands_text
                or verification_reference not in commands_text
            ):
                raise ValueError(
                    f"{source}: archive URL or SHA-256 does not match its trust anchor"
                )
            download_at = commands_text.index(pin.url)
            consume_at = commands_text.index(pin.consumer)
            if not download_at < verify_at < consume_at:
                raise ValueError(
                    f"{source}: archive must be downloaded, verified, then consumed in that order"
                )

    missing = set(PINNED_ARCHIVES) - seen_paths
    if missing:
        raise ValueError(f"missing pinned archive download(s): {', '.join(sorted(missing))}")
    return downloads


def verify_git_downloads(repository: Path, sources: list[Path]) -> int:
    clones = 0
    seen_paths: set[str] = set()
    for source in sources:
        text = source.read_text(encoding="utf-8")
        commands = active_command_lines(text, GIT_CLONE_COMMAND)
        if not commands:
            continue
        source_name = relative(source, repository)
        pin = PINNED_GIT_REPOSITORIES.get(source_name)
        if pin is None or len(commands) != 1:
            raise ValueError(f"{source}: found {len(commands)} unreviewed git clone(s)")
        seen_paths.add(source_name)
        clones += 1
        commands_text = active_text(text)

        if (
            pin.url not in commands_text
            or pin.commit not in commands_text
            or "rev-parse HEAD" not in commands_text
        ):
            raise ValueError(f"{source}: cloned repository is not commit-verified")
        clone_at = commands_text.index(commands[0])
        verify_at = commands_text.index(pin.commit)
        consume_at = commands_text.index(pin.consumer)
        if not clone_at < verify_at < consume_at:
            raise ValueError(
                f"{source}: repository must be cloned, commit-verified, then used in that order"
            )

    missing = set(PINNED_GIT_REPOSITORIES) - seen_paths
    if missing:
        raise ValueError(f"missing pinned git clone(s): {', '.join(sorted(missing))}")
    return clones


def verify_gradle_wrapper(repository: Path) -> None:
    properties_path = repository / "gradle" / "wrapper" / "gradle-wrapper.properties"
    text = properties_path.read_text(encoding="utf-8")
    matches = re.findall(r"^distributionSha256Sum=([0-9a-fA-F]{64})$", text, re.MULTILINE)
    if len(matches) != 1:
        raise ValueError(f"{properties_path}: Gradle distribution needs one SHA-256 pin")


def verify_release_notes_source(repository: Path) -> None:
    workflow = repository / ".github" / "workflows" / "check-release-notes.yml"
    text = active_text(workflow.read_text(encoding="utf-8"))
    required = (
        f"repository: {RELEASE_NOTES_SOURCE}",
        f"ref: {RELEASE_NOTES_SOURCE_COMMIT}",
        "changelog.offline.main.kts",
    )
    if any(fragment not in text for fragment in required):
        raise ValueError(f"{workflow}: release-note checker source is not commit-pinned")


def verify_repository(repository: Path) -> tuple[int, int, int]:
    repository = repository.resolve()
    if not repository.is_dir():
        raise ValueError(f"not a repository directory: {repository}")
    forbidden = repository / ".github" / "workflows" / "junie.yml"
    if forbidden.exists():
        raise ValueError(f"privileged Junie workflow must remain removed: {forbidden}")

    sources = workflow_sources(repository)
    references = verify_action_references(repository, sources)
    archives = verify_archive_downloads(repository, sources)
    clones = verify_git_downloads(repository, sources)
    verify_gradle_wrapper(repository)
    verify_release_notes_source(repository)
    return references, archives, clones


def main() -> None:
    references, archives, clones = verify_repository(parse_args().repository)
    print(
        f"verified workflow supply chain: {references} action reference(s), "
        f"{archives} pinned archive download(s), {clones} pinned git clone(s)"
    )


if __name__ == "__main__":
    main()
