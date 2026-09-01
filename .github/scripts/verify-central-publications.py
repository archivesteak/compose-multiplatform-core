#!/usr/bin/env python3
"""Verify Maven Central metadata and documentation for a staged repository."""

from __future__ import annotations

import argparse
import dataclasses
import pathlib
import re
import sys
import xml.etree.ElementTree as ET
import zipfile


MAVEN_NAMESPACE = "http://maven.apache.org/POM/4.0.0"
NS = {"m": MAVEN_NAMESPACE}
DEFAULT_GROUP_PREFIX = "io.github.archivesteak"
EXPECTED_DEVELOPER_ID = "archivesteak"
EXPECTED_DEVELOPER_NAME = "Jack Harrington"
EXPECTED_DEVELOPER_URL = "https://github.com/archivesteak"
PRIMARY_EXTENSIONS = {
    "aar": ".aar",
    "jar": ".jar",
    "klib": ".klib",
}
SIDECAR_SUFFIXES = (".asc", ".md5", ".sha1", ".sha256", ".sha512")


class VerificationError(RuntimeError):
    def __init__(self, issues: list[str]) -> None:
        super().__init__("\n".join(issues))
        self.issues = tuple(issues)


@dataclasses.dataclass(frozen=True)
class VerificationSummary:
    publications: int
    binary_publications: int
    pom_only_publications: int


def _text(element: ET.Element, path: str) -> str:
    child = element.find(path, NS)
    return "" if child is None or child.text is None else child.text.strip()


FORK_REPOSITORY_URLS = (
    re.compile(r"https://github\.com/archivesteak/([A-Za-z0-9_.-]+)/?\Z"),
    re.compile(r"ssh://git@github\.com/archivesteak/([A-Za-z0-9_.-]+)/?\Z"),
)


def _fork_repository(value: str) -> str | None:
    if value.startswith("scm:git:"):
        value = value.removeprefix("scm:git:")
    for pattern in FORK_REPOSITORY_URLS:
        match = pattern.fullmatch(value)
        if match is None:
            continue
        repository = match.group(1)
        if repository.endswith(".git"):
            repository = repository[:-4]
        if repository and repository not in {".", ".."}:
            return repository
    return None


def _check_zip_archive(path: pathlib.Path, label: str, issues: list[str]) -> None:
    if not path.is_file():
        issues.append(f"{label}: missing {path.name}")
        return
    if path.stat().st_size == 0:
        issues.append(f"{label}: {path.name} is empty")
        return

    try:
        with zipfile.ZipFile(path) as archive:
            corrupt_entry = archive.testzip()
            if corrupt_entry is not None:
                issues.append(
                    f"{label}: {path.name} has a corrupt entry: {corrupt_entry}"
                )
                return
            payloads = [
                entry
                for entry in archive.infolist()
                if not entry.is_dir() and entry.file_size > 0
            ]
            if not payloads:
                issues.append(f"{label}: {path.name} has no nonempty payload entry")
    except (OSError, zipfile.BadZipFile, RuntimeError) as error:
        issues.append(f"{label}: {path.name} is not a valid ZIP archive: {error}")


def _underlying_payload_name(name: str) -> str:
    while True:
        for suffix in SIDECAR_SUFFIXES:
            if name.endswith(suffix):
                name = name[: -len(suffix)]
                break
        else:
            return name


def _check_metadata(root: ET.Element, label: str, issues: list[str]) -> None:
    project_repository: str | None = None
    for field in ("name", "description", "url"):
        value = _text(root, f"m:{field}")
        if not value:
            issues.append(f"{label}: missing or blank <{field}>")
        elif field == "url":
            project_repository = _fork_repository(value)
            if project_repository is None:
                issues.append(
                    f"{label}: <url> does not identify an archivesteak GitHub repository: {value}"
                )

    licenses = root.findall("m:licenses/m:license", NS)
    if not any(_text(license, "m:name") and _text(license, "m:url") for license in licenses):
        issues.append(f"{label}: no license has both a nonblank name and URL")

    developers = root.findall("m:developers/m:developer", NS)
    valid_developer = False
    for developer in developers:
        developer_id = _text(developer, "m:id")
        developer_name = _text(developer, "m:name")
        developer_url = _text(developer, "m:url")
        if (
            developer_id == EXPECTED_DEVELOPER_ID
            and developer_name == EXPECTED_DEVELOPER_NAME
            and developer_url.rstrip("/") == EXPECTED_DEVELOPER_URL
        ):
            valid_developer = True
            break
    if not valid_developer:
        issues.append(
            f"{label}: no developer identifies archivesteak / Jack Harrington with a fork URL"
        )

    scm = root.find("m:scm", NS)
    if scm is None:
        issues.append(f"{label}: missing <scm>")
        return
    for field in ("url", "connection", "developerConnection"):
        value = _text(scm, f"m:{field}")
        if not value:
            issues.append(f"{label}: missing or blank <scm><{field}>")
            continue
        scm_repository = _fork_repository(value)
        if scm_repository is None:
            issues.append(
                f"{label}: <scm><{field}> does not identify an archivesteak GitHub "
                f"repository: {value}"
            )
        elif project_repository is not None and scm_repository != project_repository:
            issues.append(
                f"{label}: <scm><{field}> identifies {scm_repository}, "
                f"but <url> identifies {project_repository}"
            )


def verify_repository(
    repository: pathlib.Path,
    group_prefix: str = DEFAULT_GROUP_PREFIX,
) -> VerificationSummary:
    repository = repository.resolve()
    if not repository.is_dir():
        raise VerificationError([f"repository does not exist: {repository}"])

    group_root = repository.joinpath(*group_prefix.split("."))
    pom_files = sorted(group_root.rglob("*.pom")) if group_root.is_dir() else []
    if not pom_files:
        raise VerificationError(
            [f"no Maven POMs found for {group_prefix} under {repository}"]
        )

    issues: list[str] = []
    binary_publications = 0
    pom_only_publications = 0

    for pom_path in pom_files:
        label = pom_path.relative_to(repository).as_posix()
        try:
            root = ET.parse(pom_path).getroot()
        except (OSError, ET.ParseError) as error:
            issues.append(f"{label}: invalid XML: {error}")
            continue

        group = _text(root, "m:groupId")
        artifact = _text(root, "m:artifactId")
        version = _text(root, "m:version")
        for field, value in (
            ("groupId", group),
            ("artifactId", artifact),
            ("version", version),
        ):
            if not value:
                issues.append(f"{label}: missing or blank <{field}>")
        if not group or not artifact or not version:
            continue
        if group != group_prefix and not group.startswith(f"{group_prefix}."):
            issues.append(f"{label}: groupId is outside {group_prefix}: {group}")

        expected_directory = repository.joinpath(*group.split("."), artifact, version)
        if pom_path.parent.resolve() != expected_directory.resolve():
            issues.append(
                f"{label}: path does not match coordinate {group}:{artifact}:{version}"
            )

        _check_metadata(root, label, issues)

        packaging_text = _text(root, "m:packaging")
        packaging = packaging_text.lower() if packaging_text else "jar"
        base_name = f"{artifact}-{version}"
        sources = pom_path.parent / f"{base_name}-sources.jar"
        javadoc = pom_path.parent / f"{base_name}-javadoc.jar"

        if packaging == "pom":
            pom_only_publications += 1
            unexpected = sorted(
                path.name
                for path in pom_path.parent.iterdir()
                if path.is_file()
                and _underlying_payload_name(path.name).startswith(base_name)
                and _underlying_payload_name(path.name) != pom_path.name
            )
            if unexpected:
                issues.append(
                    f"{label}: packaging=pom publication is not POM-only: "
                    + ", ".join(unexpected)
                )
            continue

        binary_publications += 1
        extension = PRIMARY_EXTENSIONS.get(packaging)
        if extension is None:
            issues.append(f"{label}: unsupported non-POM packaging: {packaging}")
            continue

        primary = pom_path.parent / f"{base_name}{extension}"
        if not primary.is_file() or primary.stat().st_size == 0:
            issues.append(f"{label}: missing or empty primary artifact {primary.name}")
        _check_zip_archive(sources, label, issues)
        _check_zip_archive(javadoc, label, issues)

    if issues:
        raise VerificationError(issues)

    return VerificationSummary(
        publications=len(pom_files),
        binary_publications=binary_publications,
        pom_only_publications=pom_only_publications,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", type=pathlib.Path)
    parser.add_argument("--group-prefix", default=DEFAULT_GROUP_PREFIX)
    args = parser.parse_args(argv)

    try:
        summary = verify_repository(args.repository, args.group_prefix)
    except VerificationError as error:
        print("Maven Central publication verification failed:", file=sys.stderr)
        for issue in error.issues:
            print(f"- {issue}", file=sys.stderr)
        return 1

    print(
        "Verified "
        f"{summary.publications} Maven Central publications "
        f"({summary.binary_publications} binary, "
        f"{summary.pom_only_publications} POM-only)."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
