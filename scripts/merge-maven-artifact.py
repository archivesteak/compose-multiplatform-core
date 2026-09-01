#!/usr/bin/env python3
"""Assemble split platform publications into one validated Maven repository view.

The Windows, Apple, and web CI jobs publish disjoint Kotlin Multiplatform target
sets under the same coordinates. This tool treats those jobs as named, explicit
owners, builds a fresh staging tree, unions Gradle module metadata and Kotlin
tooling metadata, includes explicitly owner-pinned POM-only publications,
validates the complete graph, and only then installs the validated version
directories into mavenLocal.

Nothing is uploaded. The destination is never changed in ``--dry-run`` mode.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
import zipfile
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence


OWNERS = ("windows", "apple", "web")
PROVENANCE_DIRECTORY = "provenance"
COMMIT_SHA_PATTERN = re.compile(r"[0-9a-f]{40}\Z")
SOURCE_NAME_PATTERN = re.compile(r"[a-z][a-z0-9-]*\Z")
HASH_SUFFIXES = {
    ".md5": "md5",
    ".sha1": "sha1",
    ".sha256": "sha256",
    ".sha512": "sha512",
}
NATIVE_PLATFORMS = {
    "mingw_x64": "mingwX64",
    "linux_x64": "linuxX64",
    "linux_arm64": "linuxArm64",
    "macos_x64": "macosX64",
    "macos_arm64": "macosArm64",
    "ios_x64": "iosX64",
    "ios_arm64": "iosArm64",
    "ios_simulator_arm64": "iosSimulatorArm64",
    "tvos_x64": "tvosX64",
    "tvos_arm64": "tvosArm64",
    "tvos_simulator_arm64": "tvosSimulatorArm64",
    "watchos_x64": "watchosX64",
    "watchos_arm32": "watchosArm32",
    "watchos_arm64": "watchosArm64",
    "watchos_device_arm64": "watchosDeviceArm64",
    "watchos_simulator_arm64": "watchosSimulatorArm64",
}
JVM_RUNTIME_PLATFORMS = {
    ("windows", "x86-64"): "jvmWindowsX64",
    ("windows", "aarch64"): "jvmWindowsArm64",
    ("macos", "x86-64"): "jvmMacosX64",
    ("macos", "aarch64"): "jvmMacosArm64",
    ("linux", "x86-64"): "jvmLinuxX64",
    ("linux", "aarch64"): "jvmLinuxArm64",
}


class MergeError(RuntimeError):
    """A publication is incomplete, inconsistent, or unsafe to install."""


@dataclass(frozen=True, order=True)
class Coordinate:
    group: str
    module: str
    version: str

    def __post_init__(self) -> None:
        if self.group != self.group.strip() or not self.group:
            raise MergeError(f"invalid Maven group {self.group!r}")
        group_parts = self.group.split(".")
        if any(not part or part in {".", ".."} for part in group_parts):
            raise MergeError(f"invalid Maven group {self.group!r}")
        for label, value in (("group", self.group), ("module", self.module), ("version", self.version)):
            if value != value.strip() or not value:
                raise MergeError(f"invalid Maven {label} {value!r}")
            if any(ord(character) < 32 for character in value) or any(
                character in value
                for character in ("/", "\\", ":", "<", ">", '"', "|", "?", "*")
            ) or any(character.isspace() for character in value):
                raise MergeError(f"unsafe Maven {label} {value!r}")
        if self.module in {".", ".."} or self.version in {".", ".."}:
            raise MergeError(f"unsafe Maven coordinate {self.display()!r}")

    @classmethod
    def parse(cls, value: str) -> "Coordinate":
        parts = value.split(":")
        if len(parts) != 3 or not all(parts):
            raise MergeError(f"invalid coordinate {value!r}; expected group:module:version")
        return cls(*parts)

    def display(self) -> str:
        return f"{self.group}:{self.module}:{self.version}"

    def version_dir(self, repository: Path) -> Path:
        return repository.joinpath(*self.group.split("."), self.module, self.version)

    def module_path(self, repository: Path) -> Path:
        return self.version_dir(repository) / f"{self.module}-{self.version}.module"

    def relative_version_dir(self) -> Path:
        return Path(*self.group.split("."), self.module, self.version)


@dataclass
class ModuleRecord:
    owner: str
    coordinate: Coordinate
    path: Path
    data: dict[str, Any]
    variants: dict[str, dict[str, Any]]


@dataclass
class ModuleRequirement:
    coordinate: Coordinate
    required_variants: dict[str, list[str]]
    target_modules: dict[str, str]


@dataclass(frozen=True)
class PomOnlyRequirement:
    coordinate: Coordinate
    owner: str


@dataclass
class Requirements:
    group_prefix: str
    platform_owners: dict[str, str]
    source_provenance: dict[str, dict[str, str]]
    modules: dict[Coordinate, ModuleRequirement]
    pom_only_modules: dict[Coordinate, PomOnlyRequirement]


@dataclass
class Report:
    dry_run: bool
    inputs: dict[str, str]
    destination: str
    requirements: str
    requirements_sha256: str
    source_provenance: dict[str, dict[str, Any]]
    file_manifest: dict[str, dict[str, Any]] = field(default_factory=dict)
    modules: list[dict[str, Any]] = field(default_factory=list)
    version_directories: set[str] = field(default_factory=set)
    synthesized_files: list[str] = field(default_factory=list)
    dropped_signatures: list[str] = field(default_factory=list)
    regenerated_checksums: list[str] = field(default_factory=list)
    equivalent_root_copies: list[dict[str, str]] = field(default_factory=list)
    equivalent_leaf_copies: list[dict[str, str]] = field(default_factory=list)

    def as_json(self) -> dict[str, Any]:
        return {
            "dryRun": self.dry_run,
            "inputs": self.inputs,
            "destination": self.destination,
            "requirements": self.requirements,
            "requirementsSha256": self.requirements_sha256,
            "sourceProvenance": self.source_provenance,
            "fileManifest": self.file_manifest,
            "modules": self.modules,
            "versionDirectories": sorted(self.version_directories),
            "synthesizedFiles": sorted(self.synthesized_files),
            "droppedSignatures": sorted(self.dropped_signatures),
            "regeneratedChecksums": sorted(self.regenerated_checksums),
            "equivalentRootCopies": self.equivalent_root_copies,
            "equivalentLeafCopies": self.equivalent_leaf_copies,
        }


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--windows", required=True, type=Path, help="Windows Maven repository root")
    parser.add_argument("--apple", required=True, type=Path, help="Apple Maven repository root")
    parser.add_argument("--web", required=True, type=Path, help="JS/Wasm/Android/Linux Maven root")
    parser.add_argument("--destination", required=True, type=Path, help="mavenLocal repository root")
    parser.add_argument(
        "--requirements",
        type=Path,
        default=Path(__file__).with_name("maven-variant-requirements.json"),
        help="required coordinates, variants, target modules, POM-only modules, and owners",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="stage and validate, print a report, but do not change the destination",
    )
    parser.add_argument("--report", type=Path, help="write the validation/install report as JSON")
    return parser.parse_args(argv)


def reject_duplicate_json_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise MergeError(f"JSON object repeats key {key!r}")
        result[key] = value
    return result


def reject_non_finite_json_number(value: str) -> None:
    raise MergeError(f"JSON contains forbidden non-finite number {value!r}")


def load_json(path: Path, description: str) -> dict[str, Any]:
    try:
        with path.open(encoding="utf-8") as stream:
            value = json.load(
                stream,
                object_pairs_hook=reject_duplicate_json_keys,
                parse_constant=reject_non_finite_json_number,
            )
    except MergeError as error:
        raise MergeError(f"cannot read {description} {path}: {error}") from error
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise MergeError(f"cannot read {description} {path}: {error}") from error
    if not isinstance(value, dict):
        raise MergeError(f"{description} {path} must contain a JSON object")
    return value


def sha256_file(path: Path, description: str) -> str:
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError as error:
        raise MergeError(f"cannot hash {description} {path}: {error}") from error


def repository_file_manifest(repository: Path) -> dict[str, dict[str, Any]]:
    """Return an exact, deterministic content manifest for a staged repository."""
    repository = repository.resolve()
    manifest: dict[str, dict[str, Any]] = {}
    entries = sorted(
        repository.rglob("*"),
        key=lambda path: path.relative_to(repository).as_posix(),
    )
    for path in entries:
        relative = path.relative_to(repository).as_posix()
        if path.is_symlink():
            raise MergeError(f"staged repository contains a symlink: {relative}")
        if path.is_dir():
            continue
        if not path.is_file():
            raise MergeError(f"staged repository contains a non-regular file: {relative}")
        try:
            size = 0
            digest = hashlib.sha256()
            with path.open("rb") as stream:
                while chunk := stream.read(1024 * 1024):
                    size += len(chunk)
                    digest.update(chunk)
        except OSError as error:
            raise MergeError(f"cannot manifest staged file {path}: {error}") from error
        if size == 0:
            raise MergeError(f"staged file is empty: {path}")
        manifest[relative] = {"size": size, "sha256": digest.hexdigest()}
    return manifest


def validate_commit_sha(value: Any, context: str) -> str:
    if not isinstance(value, str) or COMMIT_SHA_PATTERN.fullmatch(value) is None:
        raise MergeError(f"{context} must be a full lowercase 40-character commit SHA")
    if value == "0" * 40:
        raise MergeError(f"{context} must not be the null commit SHA")
    return value


def prepare_json_atomic(path: Path, value: Mapping[str, Any]) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(value, stream, indent=2, ensure_ascii=False, sort_keys=False)
            stream.write("\n")
        return temporary
    except Exception:
        temporary.unlink(missing_ok=True)
        raise


def write_json_atomic(path: Path, value: Mapping[str, Any]) -> None:
    temporary = prepare_json_atomic(path, value)
    try:
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def stable_version(version: str, context: str) -> None:
    upper = version.upper()
    if "SNAPSHOT" in upper or version.startswith("9999"):
        raise MergeError(f"{context} uses forbidden placeholder/snapshot version {version!r}")
    if (
        any(character.isspace() for character in version)
        or any(token in version for token in ("+", "[", "]", "(", ")", ",", "${"))
        or version.lower().startswith("latest.")
    ):
        raise MergeError(f"{context} uses a non-exact version {version!r}")


def exact_pom_dependency_version(version: str, context: str) -> str:
    """Return an exact Maven dependency version, including singleton ranges.

    Gradle emits ``[1.2.3]`` for a strictly pinned dependency in a Maven POM.
    That syntax selects exactly one version and is therefore as deterministic as
    ``1.2.3``. Every other Maven range remains forbidden.
    """

    normalized = version
    if version.startswith("[") and version.endswith("]"):
        normalized = version[1:-1]
        if not normalized or any(token in normalized for token in ("[", "]", "(", ")", ",")):
            raise MergeError(f"{context} uses a non-exact version {version!r}")
    elif any(token in version for token in ("[", "]", "(", ")", ",")):
        raise MergeError(f"{context} uses a non-exact version {version!r}")
    stable_version(normalized, context)
    return normalized


def coordinate_from_module(
    data: Mapping[str, Any],
    path: Path,
    *,
    require_stable: bool = True,
) -> Coordinate:
    component = data.get("component")
    if not isinstance(component, dict):
        raise MergeError(f"{path} has no component coordinates")
    values = [component.get(key) for key in ("group", "module", "version")]
    if not all(isinstance(value, str) and value for value in values):
        raise MergeError(f"{path} has incomplete component coordinates")
    coordinate = Coordinate(values[0], values[1], values[2])
    if require_stable:
        stable_version(coordinate.version, coordinate.display())
    return coordinate


def coordinate_from_module_path(
    path: Path,
    repository: Path,
    *,
    require_stable: bool = True,
) -> Coordinate:
    """Return the Maven coordinate represented by a stored ``.module`` path.

    Kotlin Multiplatform target publications are intentionally different from root
    publications: a target such as ``foo-mingwx64`` is stored under its own Maven
    coordinate, while the metadata's ``component`` object identifies ``foo`` and
    links back to it with ``component.url``. Consequently the storage coordinate
    must come from the Maven path, not from the component object.
    """
    repository = repository.resolve()
    try:
        resolved = path.resolve(strict=True)
        relative = resolved.relative_to(repository)
    except (OSError, ValueError) as error:
        raise MergeError(f"module metadata escapes its repository: {path}") from error
    if len(relative.parts) < 4:
        raise MergeError(f"module metadata is not stored at a Maven coordinate path: {path}")
    group_parts = relative.parts[:-3]
    module, version, filename = relative.parts[-3:]
    coordinate = Coordinate(".".join(group_parts), module, version)
    expected_name = f"{module}-{version}.module"
    if filename != expected_name:
        raise MergeError(
            f"module metadata filename mismatch at {path}: {filename!r} != {expected_name!r}"
        )
    expected_path = coordinate.module_path(repository).resolve()
    if resolved != expected_path:
        raise MergeError(
            f"module metadata path mismatch for {coordinate.display()}: {path} != {expected_path}"
        )
    if require_stable:
        stable_version(coordinate.version, coordinate.display())
    return coordinate


def resolve_repository_url(repository: Path, base: Path, value: Any, context: str) -> Path:
    if not isinstance(value, str) or not value:
        raise MergeError(f"{context} has an empty/non-string URL")
    if any(token in value for token in (":", "\\", "\0", "?", "#")):
        raise MergeError(f"{context} uses a non-local URL {value!r}")
    candidate = Path(value)
    if candidate.is_absolute():
        raise MergeError(f"{context} uses absolute URL {value!r}")
    repository = repository.resolve()
    resolved = (base / candidate).resolve()
    try:
        resolved.relative_to(repository)
    except ValueError as error:
        raise MergeError(f"{context} URL escapes repository: {value!r}") from error
    return resolved


def component_reference(
    data: Mapping[str, Any],
    path: Path,
    stored_coordinate: Coordinate,
    repository: Path,
    *,
    require_stable: bool = True,
) -> tuple[Coordinate, Path] | None:
    component_coordinate = coordinate_from_module(
        data,
        path,
        require_stable=require_stable,
    )
    component = data["component"]
    assert isinstance(component, dict)
    raw_url = component.get("url")
    if raw_url is None:
        if component_coordinate != stored_coordinate:
            raise MergeError(
                f"{path} is stored as {stored_coordinate.display()} but declares unrelated "
                f"component {component_coordinate.display()} without component.url"
            )
        return None
    if component_coordinate.version != stored_coordinate.version:
        raise MergeError(
            f"{path} component version {component_coordinate.version!r} differs from stored "
            f"version {stored_coordinate.version!r}"
        )
    target = resolve_repository_url(repository, path.parent, raw_url, f"{path} component")
    expected = component_coordinate.module_path(repository).resolve()
    if target != expected:
        raise MergeError(
            f"{path} component.url resolves to {target}, expected {expected} for "
            f"{component_coordinate.display()}"
        )
    return component_coordinate, target


def variants_by_name(data: Mapping[str, Any], path: Path) -> dict[str, dict[str, Any]]:
    variants = data.get("variants")
    if not isinstance(variants, list):
        raise MergeError(f"{path} is not Gradle module metadata (missing variants array)")
    result: dict[str, dict[str, Any]] = {}
    for variant in variants:
        if not isinstance(variant, dict) or not isinstance(variant.get("name"), str):
            raise MergeError(f"{path} contains a variant without a string name")
        name = variant["name"]
        if name in result:
            raise MergeError(f"{path} contains duplicate variant {name!r}")
        result[name] = variant
    return result


def belongs_to_group(group: str, prefix: str) -> bool:
    return group == prefix or group.startswith(prefix + ".")


def paths_overlap(first: Path, second: Path) -> bool:
    first = first.resolve()
    second = second.resolve()
    return first == second or first in second.parents or second in first.parents


def load_requirements(path: Path) -> Requirements:
    value = load_json(path.resolve(), "requirements")
    if type(value.get("schemaVersion")) is not int or value.get("schemaVersion") != 2:
        raise MergeError(f"{path} has unsupported schemaVersion {value.get('schemaVersion')!r}")
    allowed_keys = {
        "schemaVersion",
        "groupPrefix",
        "note",
        "platformOwners",
        "sourceProvenance",
        "modules",
        "pomOnlyModules",
    }
    unexpected_keys = set(value) - allowed_keys
    if unexpected_keys:
        raise MergeError(
            f"{path} has unexpected requirements fields: "
            + ", ".join(sorted(unexpected_keys))
        )
    prefix = value.get("groupPrefix")
    owners = value.get("platformOwners")
    raw_source_provenance = value.get("sourceProvenance")
    raw_modules = value.get("modules")
    raw_pom_only_modules = value.get("pomOnlyModules")
    if not isinstance(prefix, str) or not prefix:
        raise MergeError(f"{path} has no groupPrefix")
    if not isinstance(owners, dict) or not owners:
        raise MergeError(f"{path} has no platformOwners object")
    platform_owners: dict[str, str] = {}
    for platform, owner in owners.items():
        if not isinstance(platform, str) or owner not in OWNERS:
            raise MergeError(f"invalid platform owner {platform!r}: {owner!r} in {path}")
        platform_owners[platform] = owner
    if platform_owners.get("common") != "windows":
        raise MergeError("the common/root publication owner must be windows")
    if not isinstance(raw_source_provenance, dict):
        raise MergeError(f"{path} has no sourceProvenance object")
    missing_provenance = set(OWNERS) - set(raw_source_provenance)
    extra_provenance = set(raw_source_provenance) - set(OWNERS)
    if missing_provenance or extra_provenance:
        details: list[str] = []
        if missing_provenance:
            details.append("missing " + ", ".join(sorted(missing_provenance)))
        if extra_provenance:
            details.append("unexpected " + ", ".join(sorted(extra_provenance)))
        raise MergeError(f"{path} sourceProvenance owners differ: {'; '.join(details)}")
    source_provenance: dict[str, dict[str, str]] = {}
    for owner in OWNERS:
        raw_sources = raw_source_provenance[owner]
        if not isinstance(raw_sources, dict) or not raw_sources:
            raise MergeError(f"{path} sourceProvenance[{owner!r}] must be a non-empty object")
        sources: dict[str, str] = {}
        for source, commit in raw_sources.items():
            if not isinstance(source, str) or SOURCE_NAME_PATTERN.fullmatch(source) is None:
                raise MergeError(
                    f"{path} sourceProvenance[{owner!r}] has invalid source name {source!r}"
                )
            sources[source] = validate_commit_sha(
                commit,
                f"{path} sourceProvenance[{owner!r}][{source!r}]",
            )
        source_provenance[owner] = sources
    if not isinstance(raw_modules, list) or not raw_modules:
        raise MergeError(f"{path} has no modules array")
    if not isinstance(raw_pom_only_modules, list):
        raise MergeError(f"{path} has no pomOnlyModules array")

    modules: dict[Coordinate, ModuleRequirement] = {}
    for raw in raw_modules:
        if not isinstance(raw, dict) or not isinstance(raw.get("coordinate"), str):
            raise MergeError(f"invalid module requirement in {path}: {raw!r}")
        coordinate = Coordinate.parse(raw["coordinate"])
        stable_version(coordinate.version, coordinate.display())
        if not belongs_to_group(coordinate.group, prefix):
            raise MergeError(f"required module {coordinate.display()} is outside {prefix}")
        raw_variants = raw.get("requiredVariants")
        raw_targets = raw.get("targetModules", {})
        if not isinstance(raw_variants, dict) or not raw_variants:
            raise MergeError(f"{coordinate.display()} has no requiredVariants")
        if "common" not in raw_variants:
            raise MergeError(f"{coordinate.display()} has no required common variants")
        if not isinstance(raw_targets, dict):
            raise MergeError(f"{coordinate.display()} targetModules must be an object")
        if "common" in raw_targets:
            raise MergeError(f"{coordinate.display()} must not declare a common target module")
        required_variants: dict[str, list[str]] = {}
        seen_names: set[str] = set()
        for platform, names in raw_variants.items():
            if platform not in platform_owners:
                raise MergeError(f"{coordinate.display()} uses unknown platform {platform!r}")
            if not isinstance(names, list) or not names or not all(isinstance(n, str) and n for n in names):
                raise MergeError(f"{coordinate.display()} has invalid variants for {platform}")
            if len(names) != len(set(names)):
                raise MergeError(f"{coordinate.display()} repeats variants for {platform}")
            duplicates = seen_names.intersection(names)
            if duplicates:
                raise MergeError(
                    f"{coordinate.display()} repeats variant names: {', '.join(sorted(duplicates))}"
                )
            seen_names.update(names)
            required_variants[platform] = list(names)
            if platform != "common":
                target = raw_targets.get(platform)
                if not isinstance(target, str) or not target:
                    raise MergeError(f"{coordinate.display()} has no targetModules[{platform!r}]")
                Coordinate(coordinate.group, target, coordinate.version)
        extra_targets = set(raw_targets) - set(required_variants)
        if extra_targets:
            raise MergeError(
                f"{coordinate.display()} has target modules for unrequired platforms: "
                + ", ".join(sorted(extra_targets))
            )
        requirement = ModuleRequirement(
            coordinate=coordinate,
            required_variants=required_variants,
            target_modules={str(k): str(v) for k, v in raw_targets.items()},
        )
        if coordinate in modules:
            raise MergeError(f"duplicate requirement for {coordinate.display()}")
        modules[coordinate] = requirement

    pom_only_modules: dict[Coordinate, PomOnlyRequirement] = {}
    for raw in raw_pom_only_modules:
        if not isinstance(raw, dict) or set(raw) != {"coordinate", "owner"}:
            raise MergeError(
                f"invalid POM-only module requirement in {path}: {raw!r}; "
                "expected exactly coordinate and owner"
            )
        raw_coordinate = raw["coordinate"]
        owner = raw["owner"]
        if not isinstance(raw_coordinate, str):
            raise MergeError(f"invalid POM-only coordinate in {path}: {raw_coordinate!r}")
        coordinate = Coordinate.parse(raw_coordinate)
        stable_version(coordinate.version, coordinate.display())
        if not belongs_to_group(coordinate.group, prefix):
            raise MergeError(f"POM-only module {coordinate.display()} is outside {prefix}")
        if owner not in OWNERS:
            raise MergeError(
                f"POM-only module {coordinate.display()} has invalid owner {owner!r}"
            )
        if coordinate in modules:
            raise MergeError(
                f"{coordinate.display()} cannot be both a Gradle-metadata module and POM-only"
            )
        if coordinate in pom_only_modules:
            raise MergeError(f"duplicate POM-only requirement for {coordinate.display()}")
        pom_only_modules[coordinate] = PomOnlyRequirement(coordinate, owner)
    return Requirements(
        prefix,
        platform_owners,
        source_provenance,
        modules,
        pom_only_modules,
    )


def validate_source_provenance(
    inputs: Mapping[str, Path], requirements: Requirements
) -> dict[str, dict[str, Any]]:
    group_root_name = requirements.group_prefix.split(".", 1)[0]
    if group_root_name == PROVENANCE_DIRECTORY:
        raise MergeError(
            f"group prefix {requirements.group_prefix!r} conflicts with provenance directory"
        )

    result: dict[str, dict[str, Any]] = {}
    for owner in OWNERS:
        root = inputs[owner]
        if not root.is_dir():
            raise MergeError(f"{owner} repository does not exist: {root}")
        try:
            root_entries = list(root.iterdir())
        except OSError as error:
            raise MergeError(f"cannot inspect {owner} repository {root}: {error}") from error
        for entry in root_entries:
            if entry.is_symlink():
                raise MergeError(f"{owner} repository contains a symlink: {entry}")
        root_names = {entry.name for entry in root_entries}
        expected_root_names = {group_root_name, PROVENANCE_DIRECTORY}
        if root_names != expected_root_names:
            missing = expected_root_names - root_names
            extra = root_names - expected_root_names
            details: list[str] = []
            if missing:
                details.append("missing " + ", ".join(sorted(missing)))
            if extra:
                details.append("unexpected " + ", ".join(sorted(extra)))
            raise MergeError(
                f"{owner} repository root must contain only the scoped Maven tree and "
                f"{PROVENANCE_DIRECTORY}: {'; '.join(details)}"
            )

        provenance_directory = root / PROVENANCE_DIRECTORY
        if provenance_directory.is_symlink() or not provenance_directory.is_dir():
            raise MergeError(
                f"{owner} provenance path is not a regular directory: {provenance_directory}"
            )
        try:
            provenance_entries = list(provenance_directory.iterdir())
        except OSError as error:
            raise MergeError(
                f"cannot inspect {owner} provenance directory {provenance_directory}: {error}"
            ) from error
        marker_name = f"{owner}.json"
        if {entry.name for entry in provenance_entries} != {marker_name}:
            raise MergeError(
                f"{owner} provenance directory must contain exactly {marker_name}"
            )
        marker = provenance_directory / marker_name
        if marker.is_symlink() or not marker.is_file():
            raise MergeError(f"{owner} provenance marker is not a regular file: {marker}")
        marker_sha256 = sha256_file(marker, f"{owner} provenance marker")
        data = load_json(marker, f"{owner} provenance marker")
        allowed_marker_keys = {"schemaVersion", "owner", "sources"}
        if set(data) != allowed_marker_keys:
            missing = allowed_marker_keys - set(data)
            extra = set(data) - allowed_marker_keys
            details = []
            if missing:
                details.append("missing " + ", ".join(sorted(missing)))
            if extra:
                details.append("unexpected " + ", ".join(sorted(extra)))
            raise MergeError(
                f"{owner} provenance marker fields differ: {'; '.join(details)}"
            )
        if type(data["schemaVersion"]) is not int or data["schemaVersion"] != 1:
            raise MergeError(
                f"{owner} provenance marker has unsupported schemaVersion "
                f"{data['schemaVersion']!r}"
            )
        if data["owner"] != owner:
            raise MergeError(
                f"{owner} provenance marker declares owner {data['owner']!r}"
            )
        raw_sources = data["sources"]
        if not isinstance(raw_sources, dict) or not raw_sources:
            raise MergeError(f"{owner} provenance marker has no sources object")
        actual_sources: dict[str, str] = {}
        for source, commit in raw_sources.items():
            if not isinstance(source, str) or SOURCE_NAME_PATTERN.fullmatch(source) is None:
                raise MergeError(
                    f"{owner} provenance marker has invalid source name {source!r}"
                )
            actual_sources[source] = validate_commit_sha(
                commit,
                f"{owner} provenance source {source!r}",
            )
        expected_sources = requirements.source_provenance[owner]
        if actual_sources != expected_sources:
            missing = set(expected_sources) - set(actual_sources)
            extra = set(actual_sources) - set(expected_sources)
            changed = {
                source
                for source in set(expected_sources).intersection(actual_sources)
                if expected_sources[source] != actual_sources[source]
            }
            details = []
            if missing:
                details.append("missing " + ", ".join(sorted(missing)))
            if extra:
                details.append("unexpected " + ", ".join(sorted(extra)))
            if changed:
                details.append("mismatched " + ", ".join(sorted(changed)))
            raise MergeError(
                f"{owner} provenance does not match exact source requirements: "
                + "; ".join(details)
            )
        if sha256_file(marker, f"{owner} provenance marker") != marker_sha256:
            raise MergeError(f"{owner} provenance marker changed while it was validated")
        result[owner] = {
            "marker": f"{PROVENANCE_DIRECTORY}/{marker_name}",
            "sha256": marker_sha256,
            "sources": dict(sorted(actual_sources.items())),
        }
    return result


def variant_platform(variant: Mapping[str, Any]) -> str | None:
    attributes = variant.get("attributes")
    if not isinstance(attributes, dict):
        attributes = {}
    native_target = attributes.get("org.jetbrains.kotlin.native.target")
    if isinstance(native_target, str):
        return NATIVE_PLATFORMS.get(native_target)

    name = str(variant.get("name", ""))
    lower_name = name.lower()
    available = variant.get("available-at")
    available_module = ""
    if isinstance(available, dict) and isinstance(available.get("module"), str):
        available_module = available["module"].lower()
    platform_type = attributes.get("org.jetbrains.kotlin.platform.type")
    if platform_type == "common":
        return "common"
    if platform_type == "js":
        return "js"
    if platform_type == "wasm":
        return "wasmJs"
    if platform_type == "androidJvm":
        return "android"
    if platform_type == "jvm":
        if lower_name.startswith("android") or available_module.endswith("-android"):
            return "android"
        operating_system = attributes.get("org.gradle.native.operatingSystem")
        architecture = attributes.get("org.gradle.native.architecture")
        if operating_system is not None or architecture is not None:
            if not isinstance(operating_system, str) or not isinstance(
                architecture, str
            ):
                return None
            return JVM_RUNTIME_PLATFORMS.get((operating_system, architecture))
        return "jvm"
    if isinstance(platform_type, str):
        return None
    if lower_name.startswith("metadata"):
        return "common"
    if lower_name.startswith("js"):
        return "js"
    if lower_name.startswith("wasmjs"):
        return "wasmJs"
    if lower_name.startswith("android") or available_module.endswith("-android"):
        return "android"
    return None


def platform_is_satisfied(required: str, provided: set[str]) -> bool:
    # A generic JVM dependency can resolve through an OS/architecture selector publication.
    # A host-specific dependency must still match its exact selector platform.
    return required in provided or (
        required == "jvm" and any(value.startswith("jvm") for value in provided)
    )


def available_coordinate(variant: Mapping[str, Any], context: str) -> Coordinate | None:
    available = variant.get("available-at")
    if available is None:
        return None
    if not isinstance(available, dict):
        raise MergeError(f"{context} has invalid available-at")
    values = [available.get(key) for key in ("group", "module", "version")]
    if not all(isinstance(value, str) and value for value in values):
        raise MergeError(f"{context} has incomplete available-at coordinates")
    return Coordinate(values[0], values[1], values[2])


def module_index(owner: str, root: Path, group_prefix: str) -> dict[Coordinate, ModuleRecord]:
    root = root.resolve()
    if not root.is_dir():
        raise MergeError(f"{owner} repository does not exist: {root}")
    group_root = root.joinpath(*group_prefix.split("."))
    if not group_root.is_dir():
        raise MergeError(f"{owner} repository has no {group_prefix} tree: {group_root}")

    if owner != "windows":
        for path in group_root.rglob("*"):
            if any(part.lower().endswith("-mingwx64") for part in path.relative_to(group_root).parts):
                raise MergeError(f"{owner} input contains forbidden mingw publication path: {path}")

    result: dict[Coordinate, ModuleRecord] = {}
    for path in group_root.rglob("*.module"):
        if not path.is_file():
            continue
        relative_path = path.relative_to(root)
        cursor = root
        for part in relative_path.parts:
            cursor /= part
            if cursor.is_symlink():
                raise MergeError(f"{owner} repository contains a symlink: {cursor}")
        try:
            path.resolve(strict=True).relative_to(root)
        except (OSError, ValueError) as error:
            raise MergeError(f"{owner} module escapes its repository: {path}") from error
        data = load_json(path, "Gradle module metadata")
        # Index the whole fork namespace, including unrelated local snapshots. Stability is
        # enforced when a coordinate enters the staged dependency closure.
        coordinate = coordinate_from_module_path(path, root, require_stable=False)
        if not belongs_to_group(coordinate.group, group_prefix):
            raise MergeError(f"{path} is stored under unexpected group {coordinate.group}")
        component = coordinate_from_module(data, path, require_stable=False)
        if not belongs_to_group(component.group, group_prefix):
            raise MergeError(f"{path} declares unexpected component group {component.group}")
        variants = variants_by_name(data, path)
        if owner != "windows":
            for variant in variants.values():
                if variant_platform(variant) == "mingwX64":
                    raise MergeError(
                        f"{owner} input advertises forbidden mingw variant "
                        f"{coordinate.display()}:{variant['name']}"
                    )
        if coordinate in result:
            raise MergeError(f"{owner} input contains duplicate {coordinate.display()}")
        result[coordinate] = ModuleRecord(owner, coordinate, path, data, variants)
    if not result:
        raise MergeError(f"{owner} repository contains no {group_prefix} .module files")
    for record in result.values():
        reference = component_reference(
            record.data,
            record.path,
            record.coordinate,
            root,
            require_stable=False,
        )
        if reference is None:
            continue
        component_coordinate, component_path = reference
        component_record = result.get(component_coordinate)
        if component_record is None or component_record.path.resolve() != component_path:
            raise MergeError(
                f"{owner} target {record.coordinate.display()} has dangling component.url to "
                f"{component_coordinate.display()} at {component_path}"
            )
    return result


def files_equal(first: Path, second: Path) -> bool:
    if first.stat().st_size != second.stat().st_size:
        return False
    with first.open("rb") as left, second.open("rb") as right:
        while True:
            left_chunk = left.read(1024 * 1024)
            right_chunk = right.read(1024 * 1024)
            if left_chunk != right_chunk:
                return False
            if not left_chunk:
                return True


def archive_entries(path: Path) -> dict[str, bytes]:
    try:
        with zipfile.ZipFile(path) as archive:
            result: dict[str, bytes] = {}
            for info in archive.infolist():
                if info.is_dir():
                    continue
                name = info.filename
                if name in result:
                    raise MergeError(f"archive {path} contains duplicate entry {name!r}")
                normalized_parts = Path(name.replace("\\", "/")).parts
                if (
                    name.startswith(("/", "\\"))
                    or ".." in normalized_parts
                    or (normalized_parts and ":" in normalized_parts[0])
                ):
                    raise MergeError(f"archive {path} contains unsafe entry {name!r}")
                result[name] = archive.read(info)
            return result
    except (OSError, zipfile.BadZipFile, RuntimeError) as error:
        if isinstance(error, MergeError):
            raise
        raise MergeError(f"cannot inspect archive {path}: {error}") from error


def files_equivalent(first: Path, second: Path) -> bool:
    if files_equal(first, second):
        return True
    if first.suffix.lower() in {".jar", ".zip"} and second.suffix.lower() == first.suffix.lower():
        return archive_entries(first) == archive_entries(second)
    return False


def checksum(path: Path, algorithm: str) -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_checksum_sidecar(path: Path) -> str:
    suffix = path.suffix.lower()
    algorithm = HASH_SUFFIXES.get(suffix)
    if algorithm is None:
        raise MergeError(f"unsupported checksum sidecar {path}")
    base = path.with_suffix("")
    if not base.is_file():
        raise MergeError(f"orphan checksum sidecar {path}")
    try:
        value = path.read_text(encoding="ascii").strip().lower()
    except (OSError, UnicodeDecodeError) as error:
        raise MergeError(f"cannot read checksum sidecar {path}: {error}") from error
    expected = checksum(base, algorithm)
    if value != expected:
        raise MergeError(f"invalid input checksum {path}: {value!r} != {expected}")
    return algorithm


def comparable_module_header(data: Mapping[str, Any]) -> dict[str, Any]:
    result = copy.deepcopy({key: value for key, value in data.items() if key != "variants"})
    created_by = result.get("createdBy")
    if isinstance(created_by, dict):
        gradle = created_by.get("gradle")
        if isinstance(gradle, dict):
            for key in list(gradle):
                if key.rstrip(":").lower() == "buildid":
                    del gradle[key]
    return result


def xml_child_text(element: ElementTree.Element, name: str) -> str | None:
    for child in element:
        if child.tag.rsplit("}", 1)[-1] == name:
            value = child.text.strip() if child.text else ""
            return value or None
    return None


def load_pom(path: Path) -> ElementTree.Element:
    try:
        data = path.read_bytes()
    except OSError as error:
        raise MergeError(f"cannot read Maven POM {path}: {error}") from error
    if b"<!DOCTYPE" in data.upper():
        raise MergeError(f"Maven POM {path} contains a forbidden DOCTYPE")
    try:
        root = ElementTree.fromstring(data)
    except ElementTree.ParseError as error:
        raise MergeError(f"invalid Maven POM {path}: {error}") from error
    if root.tag.rsplit("}", 1)[-1] != "project":
        raise MergeError(f"Maven POM {path} has no project root")
    return root


def validate_declared_payload(
    payload: Path,
    entry: Mapping[str, Any],
    context: str,
) -> None:
    if not payload.is_file():
        raise MergeError(f"{context} references missing payload {payload}")
    size = entry.get("size")
    if not isinstance(size, int) or size <= 0:
        raise MergeError(f"{context} has invalid declared size for {payload.name}: {size!r}")
    if payload.stat().st_size != size:
        raise MergeError(
            f"{context} size mismatch for {payload}: {payload.stat().st_size} != {size}"
        )
    for field, algorithm in (
        ("md5", "md5"),
        ("sha1", "sha1"),
        ("sha256", "sha256"),
        ("sha512", "sha512"),
    ):
        expected = entry.get(field)
        if expected is None:
            continue
        if not isinstance(expected, str) or checksum(payload, algorithm) != expected.lower():
            raise MergeError(f"{context} has invalid {field} for {payload}")


def source_variant_equivalent(
    first_record: ModuleRecord,
    first_variant: Mapping[str, Any],
    second_record: ModuleRecord,
    second_variant: Mapping[str, Any],
) -> bool:
    normalized: list[dict[str, Any]] = []
    payload_maps: list[dict[tuple[str, str], Path]] = []
    for record, variant in (
        (first_record, first_variant),
        (second_record, second_variant),
    ):
        value = copy.deepcopy(variant)
        raw_files = value.get("files", [])
        if not isinstance(raw_files, list):
            raise MergeError(
                f"{record.coordinate.display()}:{variant.get('name')} has invalid files array"
            )
        files: dict[tuple[str, str], Path] = {}
        normalized_files: list[dict[str, Any]] = []
        for entry in raw_files:
            if not isinstance(entry, dict):
                raise MergeError(
                    f"{record.coordinate.display()}:{variant.get('name')} has invalid file entry"
                )
            name = entry.get("name")
            url = entry.get("url")
            if (
                not isinstance(name, str)
                or not name
                or Path(name).name != name
                or any(character in name for character in ("/", "\\", ":", "\0"))
                or not isinstance(url, str)
                or not url
                or Path(url).name != url
                or any(character in url for character in ("/", "\\", ":", "\0"))
            ):
                raise MergeError(
                    f"{record.coordinate.display()}:{variant.get('name')} has unsafe input file "
                    f"name/url {name!r}/{url!r}"
                )
            key = (name, url)
            if key in files:
                raise MergeError(
                    f"{record.coordinate.display()}:{variant.get('name')} repeats file {key!r}"
                )
            payload = record.path.parent / url
            validate_declared_payload(
                payload,
                entry,
                f"{record.coordinate.display()}:{variant.get('name')}",
            )
            files[key] = payload
            normalized_entry = {
                key_name: entry_value
                for key_name, entry_value in entry.items()
                if key_name not in {"size", "md5", "sha1", "sha256", "sha512"}
            }
            normalized_files.append(normalized_entry)
        value["files"] = sorted(
            normalized_files,
            key=lambda entry: (str(entry.get("name")), str(entry.get("url"))),
        )
        normalized.append(value)
        payload_maps.append(files)
    if normalized[0] != normalized[1] or set(payload_maps[0]) != set(payload_maps[1]):
        return False
    return all(
        files_equivalent(payload_maps[0][key], payload_maps[1][key])
        for key in payload_maps[0]
    )


def dependency_coordinate(dependency: Mapping[str, Any], context: str) -> Coordinate | None:
    group = dependency.get("group")
    module = dependency.get("module")
    if not isinstance(group, str) or not isinstance(module, str):
        return None
    version_value = dependency.get("version")
    version: str | None = None
    if isinstance(version_value, str):
        version = version_value
    elif isinstance(version_value, dict):
        constraints = {
            key: candidate
            for key in ("strictly", "requires")
            if isinstance((candidate := version_value.get(key)), str) and candidate
        }
        if len(set(constraints.values())) > 1:
            raise MergeError(
                f"fork dependency {group}:{module} in {context} has conflicting exact versions: "
                f"{constraints}"
            )
        if constraints:
            version = next(iter(constraints.values()))
    if version is None:
        raise MergeError(f"fork dependency {group}:{module} in {context} has no exact version")
    coordinate = Coordinate(group, module, version)
    stable_version(coordinate.version, f"fork dependency {coordinate.display()} in {context}")
    return coordinate


class Assembler:
    def __init__(
        self,
        inputs: dict[str, Path],
        indexes: dict[str, dict[Coordinate, ModuleRecord]],
        destination: Path,
        stage: Path,
        requirements_path: Path,
        requirements: Requirements,
        requirements_sha256: str,
        source_provenance: dict[str, dict[str, Any]],
        dry_run: bool,
    ) -> None:
        self.inputs = inputs
        self.indexes = indexes
        self.destination = destination
        self.stage = stage
        self.requirements_path = requirements_path
        self.requirements = requirements
        self.assembled: set[Coordinate] = set()
        self.assembled_platforms: dict[Coordinate, set[str]] = {}
        self.assembling: set[Coordinate] = set()
        self.checksum_algorithms: dict[Path, set[str]] = {}
        self.report = Report(
            dry_run=dry_run,
            inputs={owner: str(path) for owner, path in inputs.items()},
            destination=str(destination),
            requirements=str(requirements_path),
            requirements_sha256=requirements_sha256,
            source_provenance=source_provenance,
        )

    def record(self, owner: str, coordinate: Coordinate) -> ModuleRecord:
        record = self.indexes[owner].get(coordinate)
        if record is None:
            raise MergeError(f"{owner} input is missing {coordinate.display()}")
        return record

    def assemble(self) -> None:
        for requirement in self.requirements.modules.values():
            self.assemble_required(requirement)
        for requirement in self.requirements.pom_only_modules.values():
            if requirement.coordinate not in self.assembled:
                self.stage_pom_only(requirement)
        self.regenerate_checksums()
        self.validate_stage()
        self.report.file_manifest = repository_file_manifest(self.stage)

    def stage_pom_only(
        self,
        requirement: PomOnlyRequirement,
        platform: str | None = None,
    ) -> None:
        coordinate = requirement.coordinate
        if coordinate in self.assembled:
            raise MergeError(
                f"POM-only module {coordinate.display()} was already assembled as Gradle metadata"
            )
        for owner in OWNERS:
            source_dir = coordinate.version_dir(self.inputs[owner])
            if owner != requirement.owner and (source_dir.exists() or source_dir.is_symlink()):
                raise MergeError(
                    f"POM-only module {coordinate.display()} is pinned to {requirement.owner}, "
                    f"but {owner} also contains its version directory"
                )
        source_dir = coordinate.version_dir(self.inputs[requirement.owner])
        if not source_dir.is_dir() or source_dir.is_symlink():
            raise MergeError(
                f"{requirement.owner} input is missing POM-only module {coordinate.display()}"
            )
        cursor = self.inputs[requirement.owner]
        for part in coordinate.relative_version_dir().parts:
            cursor /= part
            if cursor.is_symlink():
                raise MergeError(f"POM-only publication contains a symlink: {cursor}")
        try:
            source_dir.resolve(strict=True).relative_to(self.inputs[requirement.owner])
        except (OSError, ValueError) as error:
            raise MergeError(
                f"POM-only module escapes the {requirement.owner} repository: {source_dir}"
            ) from error

        files = self.validated_directory_files(source_dir)
        expected_pom = f"{coordinate.module}-{coordinate.version}.pom"
        if expected_pom not in files:
            raise MergeError(
                f"POM-only module {coordinate.display()} is missing {expected_pom}"
            )
        pom_files = sorted(name for name in files if name.endswith(".pom"))
        if pom_files != [expected_pom]:
            raise MergeError(
                f"POM-only module {coordinate.display()} contains unexpected POM files: "
                + ", ".join(pom_files)
            )
        module_files = sorted(name for name in files if name.endswith(".module"))
        if module_files:
            raise MergeError(
                f"POM-only module {coordinate.display()} unexpectedly contains Gradle metadata: "
                + ", ".join(module_files)
            )

        self.copy_version_directory(coordinate, source_dir, set())
        self.assembled.add(coordinate)
        self.assembled_platforms[coordinate] = {platform} if platform is not None else set()
        self.report.modules.append(
            {
                "coordinate": coordinate.display(),
                "required": True,
                "pomOnly": True,
                "owner": requirement.owner,
                **({"targetPlatform": platform} if platform is not None else {}),
            }
        )

    def assemble_required(self, requirement: ModuleRequirement) -> None:
        coordinate = requirement.coordinate
        if coordinate in self.assembled:
            return
        if coordinate in self.assembling:
            raise MergeError(f"cyclic assembly while processing {coordinate.display()}")
        self.assembling.add(coordinate)
        common_owner = self.requirements.platform_owners["common"]
        base = self.record(common_owner, coordinate)
        self.check_contributing_metadata(coordinate, base)
        self.audit_variant_ownership(coordinate)
        selected: list[dict[str, Any]] = []
        targets: dict[Coordinate, str] = {}
        platforms: dict[str, str] = {}

        for platform, names in requirement.required_variants.items():
            owner = self.requirements.platform_owners[platform]
            record = self.record(owner, coordinate)
            for name in names:
                variant = record.variants.get(name)
                if variant is None:
                    raise MergeError(
                        f"{owner} input {coordinate.display()} lacks required {platform} "
                        f"variant {name!r}"
                    )
                actual_platform = variant_platform(variant)
                if actual_platform != platform:
                    raise MergeError(
                        f"{coordinate.display()}:{name} is {actual_platform!r}, expected {platform!r}"
                    )
                variant_copy = copy.deepcopy(variant)
                selected.append(variant_copy)
                platforms[name] = platform
                if platform == "common":
                    if available_coordinate(variant, f"{coordinate.display()}:{name}") is not None:
                        raise MergeError(f"common variant {coordinate.display()}:{name} has available-at")
                    continue
                expected_module = requirement.target_modules[platform]
                target = available_coordinate(variant, f"{coordinate.display()}:{name}")
                if target is None:
                    raise MergeError(f"required variant {coordinate.display()}:{name} has no available-at")
                if (
                    target.group != coordinate.group
                    or target.version != coordinate.version
                    or target.module != expected_module
                ):
                    raise MergeError(
                        f"{coordinate.display()}:{name} points to {target.display()}, expected "
                        f"{coordinate.group}:{expected_module}:{coordinate.version}"
                    )
                previous_platform = targets.get(target)
                if previous_platform is not None and previous_platform != platform:
                    raise MergeError(
                        f"{coordinate.display()} maps {target.display()} to both "
                        f"{previous_platform} and {platform}"
                    )
                targets[target] = platform

        merged = copy.deepcopy(base.data)
        merged["variants"] = selected
        self.stage_root(coordinate, common_owner, merged, set(requirement.required_variants))
        self.report.modules.append(
            {
                "coordinate": coordinate.display(),
                "required": True,
                "variants": [variant["name"] for variant in selected],
                "variantPlatforms": platforms,
            }
        )
        self.assembled.add(coordinate)
        self.assembled_platforms[coordinate] = set(requirement.required_variants)
        self.assembling.remove(coordinate)
        for target, platform in targets.items():
            self.stage_target(target, platform, coordinate)
        self.assemble_fork_dependencies(merged, coordinate.display())

    def assemble_automatic(self, coordinate: Coordinate, required_platform: str) -> None:
        if coordinate in self.requirements.modules:
            requirement = self.requirements.modules[coordinate]
            if required_platform not in requirement.required_variants:
                raise MergeError(
                    f"dependency requires {coordinate.display()} for {required_platform}, but its "
                    "checked-in requirement does not include that platform"
                )
            self.assemble_required(requirement)
            return
        pom_only = self.requirements.pom_only_modules.get(coordinate)
        if pom_only is not None:
            expected_owner = self.requirements.platform_owners.get(required_platform)
            if expected_owner != pom_only.owner:
                raise MergeError(
                    f"dependency requires POM-only {coordinate.display()} for "
                    f"{required_platform}, owned by {expected_owner!r}, but requirements pin it "
                    f"to {pom_only.owner}"
                )
            if coordinate not in self.assembled:
                self.stage_pom_only(pom_only, required_platform)
            elif not platform_is_satisfied(
                required_platform, self.assembled_platforms[coordinate]
            ):
                raise MergeError(
                    f"POM-only {coordinate.display()} was reused for incompatible platforms"
                )
            return
        if coordinate in self.assembled:
            if not platform_is_satisfied(
                required_platform, self.assembled_platforms[coordinate]
            ):
                raise MergeError(
                    f"dependency requires {coordinate.display()} for {required_platform}, but the "
                    f"assembled publication provides {sorted(self.assembled_platforms[coordinate])}"
                )
            return
        if coordinate in self.assembling:
            return
        stable_version(coordinate.version, coordinate.display())
        if not belongs_to_group(coordinate.group, self.requirements.group_prefix):
            return
        self.assembling.add(coordinate)

        available_records = {
            owner: index[coordinate]
            for owner, index in self.indexes.items()
            if coordinate in index
        }
        if not available_records:
            raise MergeError(f"no input contains fork dependency {coordinate.display()}")
        is_root = any(
            any("available-at" in variant for variant in record.variants.values())
            for record in available_records.values()
        )
        contributing_owners = {
            self.requirements.platform_owners.get(platform)
            for record in available_records.values()
            for variant in record.variants.values()
            if (platform := variant_platform(variant)) is not None
        }
        contributing_owners.discard(None)
        # Gradle selector modules such as skiko-awt-runtime have no available-at redirects, but
        # their JVM runtime variants are still split by OS/architecture across producer hosts.
        is_root = is_root or len(contributing_owners) > 1
        if not is_root:
            platforms: set[str] = set()
            for record in available_records.values():
                for name, variant in record.variants.items():
                    platform = variant_platform(variant)
                    if platform is None:
                        raise MergeError(
                            f"cannot classify target variant {coordinate.display()}:{name}"
                        )
                    platforms.add(platform)
            owners = {self.requirements.platform_owners.get(platform) for platform in platforms}
            owners.discard(None)
            if len(owners) != 1:
                raise MergeError(
                    f"cannot infer one owner for leaf {coordinate.display()} from {sorted(platforms)}"
                )
            owner = next(iter(owners))
            if owner not in available_records:
                raise MergeError(f"owner {owner} has no leaf {coordinate.display()}")
            if not platform_is_satisfied(required_platform, platforms):
                raise MergeError(
                    f"dependency requires {coordinate.display()} for {required_platform}, but the "
                    f"available publication provides {sorted(platforms)}"
                )
            self.audit_leaf_copies(coordinate, owner)
            self.stage_leaf(available_records[owner])
            self.assembled.add(coordinate)
            self.assembled_platforms[coordinate] = platforms
            self.assembling.remove(coordinate)
            self.assemble_fork_dependencies(available_records[owner].data, coordinate.display())
            return

        common_owner = self.requirements.platform_owners["common"]
        base = available_records.get(common_owner)
        if base is None:
            raise MergeError(f"root {coordinate.display()} is missing its {common_owner} owner copy")
        self.check_contributing_metadata(coordinate, base)
        self.audit_variant_ownership(coordinate)
        selected_by_name: dict[str, dict[str, Any]] = {}
        selected_platforms: dict[str, str] = {}
        targets: dict[Coordinate, str] = {}
        for owner in OWNERS:
            record = available_records.get(owner)
            if record is None:
                continue
            for name, variant in record.variants.items():
                platform = variant_platform(variant)
                if platform is None:
                    raise MergeError(f"cannot classify {coordinate.display()}:{name}")
                expected_owner = self.requirements.platform_owners.get(platform)
                if expected_owner is None:
                    raise MergeError(
                        f"no owner configured for {platform} used by {coordinate.display()}:{name}"
                    )
                if expected_owner != owner:
                    continue
                if name in selected_by_name:
                    raise MergeError(f"owner merge produced duplicate {coordinate.display()}:{name}")
                target = available_coordinate(variant, f"{coordinate.display()}:{name}")
                if target is not None:
                    if target not in self.indexes[owner]:
                        raise MergeError(
                            f"owner {owner} advertises dangling {coordinate.display()}:{name} -> "
                            f"{target.display()}"
                        )
                    previous_platform = targets.get(target)
                    if previous_platform is not None and previous_platform != platform:
                        raise MergeError(
                            f"{coordinate.display()} maps {target.display()} to both "
                            f"{previous_platform} and {platform}"
                        )
                    targets[target] = platform
                selected_by_name[name] = copy.deepcopy(variant)
                selected_platforms[name] = platform
        if not selected_by_name:
            raise MergeError(f"no owned variants found for {coordinate.display()}")
        provided_platforms = set(selected_platforms.values())
        if not platform_is_satisfied(required_platform, provided_platforms):
            raise MergeError(
                f"dependency requires {coordinate.display()} for {required_platform}, but the "
                f"available root publication provides {sorted(provided_platforms)}"
            )
        merged = copy.deepcopy(base.data)
        merged["variants"] = list(selected_by_name.values())
        self.stage_root(
            coordinate,
            common_owner,
            merged,
            set(selected_platforms.values()),
        )
        self.report.modules.append(
            {
                "coordinate": coordinate.display(),
                "required": False,
                "variants": list(selected_by_name),
                "variantPlatforms": selected_platforms,
            }
        )
        self.assembled.add(coordinate)
        self.assembled_platforms[coordinate] = provided_platforms
        self.assembling.remove(coordinate)
        for target, platform in targets.items():
            self.stage_target(target, platform, coordinate)
        self.assemble_fork_dependencies(merged, coordinate.display())

    def stage_root(
        self,
        coordinate: Coordinate,
        owner: str,
        merged: dict[str, Any],
        allowed_platforms: set[str],
    ) -> None:
        record = self.record(owner, coordinate)
        module_name = record.path.name
        tooling_name = f"{coordinate.module}-{coordinate.version}-kotlin-tooling-metadata.json"
        synthesized = {module_name}
        tooling = self.merge_tooling_metadata(coordinate, tooling_name, allowed_platforms)
        if tooling is not None:
            synthesized.add(tooling_name)
        self.copy_owned_version(record, synthesized)
        self.audit_root_collisions(coordinate, owner, synthesized)
        module_dest = coordinate.module_path(self.stage)
        write_json_atomic(module_dest, merged)
        self.report.synthesized_files.append(str(module_dest.relative_to(self.stage)))
        self.collect_synthesized_sidecars(coordinate, synthesized)
        if tooling is not None:
            tooling_dest = coordinate.version_dir(self.stage) / tooling_name
            write_json_atomic(tooling_dest, tooling)
            self.report.synthesized_files.append(str(tooling_dest.relative_to(self.stage)))

    def stage_target(
        self,
        coordinate: Coordinate,
        platform: str,
        root_coordinate: Coordinate,
    ) -> None:
        owner = self.requirements.platform_owners[platform]
        stable_version(coordinate.version, coordinate.display())
        record = self.record(owner, coordinate)
        reference = component_reference(
            record.data,
            record.path,
            coordinate,
            self.inputs[owner],
        )
        if reference is None or reference[0] != root_coordinate:
            declared = reference[0].display() if reference is not None else "no root component"
            raise MergeError(
                f"target {coordinate.display()} for {root_coordinate.display()} links to {declared}"
            )
        if coordinate in self.assembled:
            if platform not in self.assembled_platforms[coordinate]:
                raise MergeError(
                    f"target {coordinate.display()} is reused for both "
                    f"{sorted(self.assembled_platforms[coordinate])} and {platform}"
                )
            return
        classified: set[str] = set()
        for name, variant in record.variants.items():
            value = variant_platform(variant)
            if value is None:
                raise MergeError(f"cannot classify target variant {coordinate.display()}:{name}")
            classified.add(value)
        if platform == "android" and classified == {"jvm"} and coordinate.module.endswith("-android"):
            classified = {"android"}
        if classified and classified != {platform}:
            raise MergeError(
                f"target {coordinate.display()} owned by {owner}/{platform} contains "
                f"variants for {sorted(classified)}"
            )
        self.audit_leaf_copies(coordinate, owner)
        self.stage_leaf(record)
        self.assembled.add(coordinate)
        self.assembled_platforms[coordinate] = {platform}
        self.report.modules.append(
            {
                "coordinate": coordinate.display(),
                "required": False,
                "targetPlatform": platform,
                "owner": owner,
                "variants": list(record.variants),
            }
        )
        self.assemble_fork_dependencies(record.data, coordinate.display())

    def check_contributing_metadata(self, coordinate: Coordinate, base: ModuleRecord) -> None:
        expected_header = comparable_module_header(base.data)
        for owner in OWNERS:
            record = self.indexes[owner].get(coordinate)
            if record is None:
                continue
            if record.coordinate != coordinate:
                raise MergeError(
                    f"{owner} metadata coordinate mismatch for {coordinate.display()}: "
                    f"{record.coordinate.display()}"
                )
            if comparable_module_header(record.data) != expected_header:
                raise MergeError(
                    f"Gradle metadata header mismatch for {coordinate.display()} between "
                    f"{base.owner} and {owner}"
                )

    def audit_variant_ownership(self, coordinate: Coordinate) -> None:
        records = {
            owner: index[coordinate]
            for owner, index in self.indexes.items()
            if coordinate in index
        }
        for owner, record in records.items():
            for name, variant in record.variants.items():
                platform = variant_platform(variant)
                if platform is None:
                    raise MergeError(
                        f"cannot classify input variant {coordinate.display()}:{name} from {owner}"
                    )
                expected_owner = self.requirements.platform_owners.get(platform)
                if expected_owner is None:
                    raise MergeError(
                        f"no owner configured for {platform} used by {coordinate.display()}:{name}"
                    )
                if owner == expected_owner:
                    continue
                authoritative = records.get(expected_owner)
                authoritative_variant = (
                    authoritative.variants.get(name) if authoritative is not None else None
                )
                if authoritative_variant is None or not source_variant_equivalent(
                    authoritative,
                    authoritative_variant,
                    record,
                    variant,
                ):
                    raise MergeError(
                        f"{owner} input contains non-owned {platform} variant "
                        f"{coordinate.display()}:{name} without an identical {expected_owner} copy"
                    )

    def stage_leaf(self, record: ModuleRecord) -> None:
        self.copy_owned_version(record, set())

    @staticmethod
    def validated_directory_files(directory: Path) -> dict[str, Path]:
        result: dict[str, Path] = {}
        for entry in directory.iterdir():
            if entry.is_symlink():
                raise MergeError(f"input publication contains a symlink: {entry}")
            if not entry.is_file():
                raise MergeError(f"input Maven version directory is not flat: {entry}")
            if entry.stat().st_size == 0:
                raise MergeError(f"input publication contains an empty file: {entry}")
            result[entry.name] = entry
        for entry in result.values():
            suffix = entry.suffix.lower()
            if suffix in HASH_SUFFIXES:
                validate_checksum_sidecar(entry)
            elif suffix == ".asc" and not entry.with_suffix("").is_file():
                raise MergeError(f"orphan signature {entry}")
        return result

    @classmethod
    def validated_version_files(cls, record: ModuleRecord) -> dict[str, Path]:
        return cls.validated_directory_files(record.path.parent)

    def copy_owned_version(self, record: ModuleRecord, synthesized: set[str]) -> None:
        self.copy_version_directory(record.coordinate, record.path.parent, synthesized)

    def copy_version_directory(
        self,
        coordinate: Coordinate,
        source_dir: Path,
        synthesized: set[str],
    ) -> None:
        destination_dir = coordinate.version_dir(self.stage)
        if destination_dir.exists():
            return
        destination_dir.mkdir(parents=True)
        self.report.version_directories.add(str(coordinate.relative_version_dir()))

        files = list(self.validated_directory_files(source_dir).values())
        for source in files:
            suffix = source.suffix.lower()
            if suffix in HASH_SUFFIXES:
                algorithm = HASH_SUFFIXES[suffix]
                base = source.with_suffix("")
                if base.suffix.lower() == ".asc" and base.with_suffix("").name in synthesized:
                    continue
                relative_base = destination_dir / base.name
                self.checksum_algorithms.setdefault(relative_base, set()).add(algorithm)
                continue
            if suffix == ".asc":
                continue
            if source.name in synthesized:
                continue
            shutil.copy2(source, destination_dir / source.name)

        for signature in (path for path in files if path.suffix.lower() == ".asc"):
            base = signature.with_suffix("")
            if base.name in synthesized:
                self.report.dropped_signatures.append(
                    str((destination_dir / signature.name).relative_to(self.stage))
                )
                continue
            if not base.is_file():
                raise MergeError(f"orphan signature {signature}")
            staged_base = destination_dir / base.name
            if not staged_base.is_file():
                raise MergeError(f"signature {signature} has no staged payload")
            shutil.copy2(signature, destination_dir / signature.name)

    def collect_synthesized_sidecars(self, coordinate: Coordinate, names: set[str]) -> None:
        destination_dir = coordinate.version_dir(self.stage)
        for owner in OWNERS:
            record = self.indexes[owner].get(coordinate)
            if record is None:
                continue
            for name in names:
                for suffix, algorithm in HASH_SUFFIXES.items():
                    if (record.path.parent / f"{name}{suffix}").is_file():
                        validate_checksum_sidecar(record.path.parent / f"{name}{suffix}")
                        self.checksum_algorithms.setdefault(destination_dir / name, set()).add(algorithm)
                signature = record.path.parent / f"{name}.asc"
                if signature.is_file():
                    relative = str((destination_dir / signature.name).relative_to(self.stage))
                    if relative not in self.report.dropped_signatures:
                        self.report.dropped_signatures.append(relative)

    def audit_root_collisions(
        self,
        coordinate: Coordinate,
        owner: str,
        synthesized: set[str],
    ) -> None:
        owner_dir = self.record(owner, coordinate).path.parent
        ignored_suffixes = set(HASH_SUFFIXES) | {".asc"}
        for other_owner in OWNERS:
            if other_owner == owner:
                continue
            other = self.indexes[other_owner].get(coordinate)
            if other is None:
                continue
            for candidate in self.validated_version_files(other).values():
                if candidate.suffix.lower() in HASH_SUFFIXES:
                    continue
                if candidate.suffix.lower() == ".asc":
                    continue
                if candidate.name in synthesized or candidate.suffix.lower() in ignored_suffixes:
                    continue
                authoritative = owner_dir / candidate.name
                if not authoritative.is_file():
                    raise MergeError(
                        f"unowned root file {candidate} has no {owner} authoritative counterpart"
                    )
                if not files_equivalent(authoritative, candidate):
                    raise MergeError(
                        f"unequal root collision for {coordinate.display()}:{candidate.name} "
                        f"between {owner} and {other_owner}"
                    )
                self.report.equivalent_root_copies.append(
                    {
                        "path": str(coordinate.relative_version_dir() / candidate.name),
                        "owner": owner,
                        "equivalent": other_owner,
                    }
                )

    def audit_leaf_copies(self, coordinate: Coordinate, owner: str) -> None:
        authoritative = self.record(owner, coordinate)
        authoritative_files = self.validated_version_files(authoritative)
        module_name = authoritative.path.name

        def comparable_payloads(files: Mapping[str, Path]) -> dict[str, Path]:
            return {
                name: path
                for name, path in files.items()
                if name != module_name
                and path.suffix.lower() not in HASH_SUFFIXES
                and path.suffix.lower() != ".asc"
            }

        authoritative_payloads = comparable_payloads(authoritative_files)
        authoritative_header = comparable_module_header(authoritative.data)
        for other_owner in OWNERS:
            if other_owner == owner:
                continue
            other = self.indexes[other_owner].get(coordinate)
            if other is None:
                continue
            if comparable_module_header(other.data) != authoritative_header:
                raise MergeError(
                    f"Gradle metadata header mismatch for leaf {coordinate.display()} between "
                    f"{owner} and {other_owner}"
                )
            if set(other.variants) != set(authoritative.variants):
                raise MergeError(
                    f"unequal leaf variant set for {coordinate.display()} between "
                    f"{owner} and {other_owner}"
                )
            for name, variant in authoritative.variants.items():
                authoritative_platform = variant_platform(variant)
                other_platform = variant_platform(other.variants[name])
                if authoritative_platform is None or other_platform is None:
                    raise MergeError(f"cannot classify leaf variant {coordinate.display()}:{name}")
                if authoritative_platform != other_platform or not source_variant_equivalent(
                    authoritative,
                    variant,
                    other,
                    other.variants[name],
                ):
                    raise MergeError(
                        f"unequal leaf variant {coordinate.display()}:{name} between "
                        f"{owner} and {other_owner}"
                    )

            other_payloads = comparable_payloads(self.validated_version_files(other))
            if set(other_payloads) != set(authoritative_payloads):
                raise MergeError(
                    f"unequal leaf payload set for {coordinate.display()} between "
                    f"{owner} and {other_owner}"
                )
            for name, path in authoritative_payloads.items():
                if not files_equivalent(path, other_payloads[name]):
                    raise MergeError(
                        f"unequal leaf collision for {coordinate.display()}:{name} between "
                        f"{owner} and {other_owner}"
                    )
            self.report.equivalent_leaf_copies.append(
                {
                    "coordinate": coordinate.display(),
                    "owner": owner,
                    "equivalent": other_owner,
                }
            )

    def merge_tooling_metadata(
        self,
        coordinate: Coordinate,
        filename: str,
        allowed_platforms: set[str],
    ) -> dict[str, Any] | None:
        documents: list[tuple[str, Path, dict[str, Any]]] = []
        missing: list[tuple[str, Path]] = []
        for owner in OWNERS:
            record = self.indexes[owner].get(coordinate)
            if record is None:
                continue
            path = record.path.parent / filename
            if not path.is_file():
                missing.append((owner, path))
                continue
            documents.append((owner, path, load_json(path, "Kotlin tooling metadata")))
        # Non-KMP Gradle selector publications have mergeable host variants but no Kotlin tooling
        # metadata. A partial presence remains an error because it would make producer contracts
        # disagree about the kind of publication being merged.
        if not documents:
            return None
        if missing:
            owner, path = missing[0]
            raise MergeError(
                f"{owner} root {coordinate.display()} is missing Kotlin tooling metadata {path}"
            )
        base_entry = next((entry for entry in documents if entry[0] == "windows"), None)
        if base_entry is None:
            raise MergeError(f"{coordinate.display()} tooling metadata has no windows owner copy")
        base = copy.deepcopy(base_entry[2])
        base_targets = base.get("projectTargets")
        if not isinstance(base_targets, list):
            raise MergeError(f"{base_entry[1]} has no projectTargets array")
        comparable_base = {key: value for key, value in base.items() if key != "projectTargets"}
        candidates: dict[str, list[tuple[str, str, dict[str, Any]]]] = {}
        for owner, path, document in documents:
            targets = document.get("projectTargets")
            if not isinstance(targets, list):
                raise MergeError(f"{path} has no projectTargets array")
            comparable = {key: value for key, value in document.items() if key != "projectTargets"}
            if comparable != comparable_base:
                raise MergeError(
                    f"Kotlin tooling metadata scalars/settings differ for {coordinate.display()} "
                    f"between windows and {owner}: {path}"
                )
            document_keys: set[str] = set()
            for target in targets:
                if not isinstance(target, dict):
                    raise MergeError(f"{path} contains a non-object project target")
                key = self.tooling_target_key(target, path)
                if key in document_keys:
                    raise MergeError(f"{path} repeats Kotlin tooling target {key!r}")
                document_keys.add(key)
                platform = self.tooling_target_platform(target, path)
                if platform not in self.requirements.platform_owners:
                    raise MergeError(
                        f"no owner configured for tooling platform {platform!r} in {path}"
                    )
                candidates.setdefault(key, []).append((owner, platform, target))

        ordered_targets: list[dict[str, Any]] = []
        covered_platforms: set[str] = set()
        added_keys: set[str] = set()
        for owner in OWNERS:
            for document_owner, path, document in documents:
                if document_owner != owner:
                    continue
                for target in document["projectTargets"]:
                    key = self.tooling_target_key(target, path)
                    platform = self.tooling_target_platform(target, path)
                    expected_owner = self.requirements.platform_owners[platform]
                    entries = candidates[key]
                    authoritative = next(
                        (value for candidate_owner, _, value in entries if candidate_owner == expected_owner),
                        None,
                    )
                    if authoritative is None:
                        raise MergeError(
                            f"tooling target {key!r} for {coordinate.display()} has no "
                            f"{expected_owner} owner copy"
                        )
                    if any(value != authoritative for _, _, value in entries):
                        raise MergeError(
                            f"conflicting Kotlin tooling target {key!r} for {coordinate.display()}"
                        )
                    if owner != expected_owner:
                        continue
                    if platform not in allowed_platforms:
                        raise MergeError(
                            f"{owner} tooling metadata advertises unexpected {platform} target "
                            f"{key!r} for {coordinate.display()}"
                        )
                    if key in added_keys:
                        continue
                    if platform in covered_platforms:
                        raise MergeError(
                            f"{owner} tooling metadata repeats platform {platform!r} with "
                            f"different targets for {coordinate.display()}"
                        )
                    ordered_targets.append(copy.deepcopy(target))
                    added_keys.add(key)
                    covered_platforms.add(platform)
        if covered_platforms != allowed_platforms:
            raise MergeError(
                f"Kotlin tooling metadata target coverage differs for {coordinate.display()}; "
                f"missing={sorted(allowed_platforms - covered_platforms)}, "
                f"unexpected={sorted(covered_platforms - allowed_platforms)}"
            )
        base["projectTargets"] = ordered_targets
        return base

    @staticmethod
    def tooling_target_platform(target: Mapping[str, Any], path: Path) -> str:
        target_type = target.get("target")
        platform = target.get("platformType")
        if not isinstance(target_type, str) or not isinstance(platform, str):
            raise MergeError(f"{path} has an incomplete project target")
        if "android" in target_type.lower():
            return "android"
        if platform == "common":
            return "common"
        if platform == "jvm":
            return "jvm"
        if platform == "js":
            return "js"
        if platform == "wasm":
            return "wasmJs"
        if platform == "native":
            extras = target.get("extras")
            native = extras.get("native") if isinstance(extras, dict) else None
            konan_target = native.get("konanTarget") if isinstance(native, dict) else None
            if isinstance(konan_target, str) and konan_target in NATIVE_PLATFORMS:
                return NATIVE_PLATFORMS[konan_target]
        raise MergeError(f"{path} has an unrecognized project target {target!r}")

    @staticmethod
    def tooling_target_key(target: Mapping[str, Any], path: Path) -> str:
        target_type = target.get("target")
        platform = target.get("platformType")
        if not isinstance(target_type, str) or not isinstance(platform, str):
            raise MergeError(f"{path} has an incomplete project target")
        extras = target.get("extras")
        native_target = None
        if isinstance(extras, dict):
            native = extras.get("native")
            if isinstance(native, dict):
                native_target = native.get("konanTarget")
        return f"{platform}:{target_type}:{native_target or ''}"

    def assemble_fork_dependencies(self, module: Mapping[str, Any], context: str) -> None:
        variants = module.get("variants")
        if not isinstance(variants, list):
            return
        dependencies: set[tuple[Coordinate, str]] = set()
        for variant in variants:
            if not isinstance(variant, dict):
                continue
            raw_dependencies = variant.get("dependencies", [])
            if not isinstance(raw_dependencies, list):
                raise MergeError(f"{context}:{variant.get('name')} has invalid dependencies")
            platform = variant_platform(variant)
            for dependency in raw_dependencies:
                if not isinstance(dependency, dict):
                    raise MergeError(f"{context}:{variant.get('name')} has invalid dependency")
                group = dependency.get("group")
                if not isinstance(group, str) or not belongs_to_group(
                    group, self.requirements.group_prefix
                ):
                    continue
                coordinate = dependency_coordinate(dependency, context)
                assert coordinate is not None
                if platform is None:
                    raise MergeError(
                        f"cannot classify dependency-bearing variant "
                        f"{context}:{variant.get('name')}"
                    )
                dependencies.add((coordinate, platform))
            raw_constraints = variant.get("dependencyConstraints", [])
            if not isinstance(raw_constraints, list):
                raise MergeError(
                    f"{context}:{variant.get('name')} has invalid dependencyConstraints"
                )
            for constraint in raw_constraints:
                if not isinstance(constraint, dict):
                    raise MergeError(
                        f"{context}:{variant.get('name')} has invalid dependency constraint"
                    )
                group = constraint.get("group")
                if not isinstance(group, str) or not belongs_to_group(
                    group, self.requirements.group_prefix
                ):
                    continue
                # Constraints do not fetch artifacts, so they are not part of the staged
                # dependency closure. They must still pin a stable, exact fork version.
                dependency_coordinate(constraint, context)
        for dependency, platform in sorted(dependencies):
            self.assemble_automatic(dependency, platform)

    def regenerate_checksums(self) -> None:
        for path, algorithms in sorted(self.checksum_algorithms.items(), key=lambda item: str(item[0])):
            if not path.is_file():
                raise MergeError(f"checksum sidecar refers to missing staged payload {path}")
            for algorithm in sorted(algorithms):
                sidecar = path.with_name(path.name + f".{algorithm}")
                sidecar.write_text(checksum(path, algorithm) + "\n", encoding="ascii", newline="\n")
                self.report.regenerated_checksums.append(str(sidecar.relative_to(self.stage)))

    def validate_stage(self) -> None:
        module_cache: dict[Path, tuple[Coordinate, dict[str, Any]]] = {}
        for path in self.stage.rglob("*"):
            if path.is_file() and path.stat().st_size == 0:
                raise MergeError(f"staged file is empty: {path}")
        for path in self.stage.rglob("*.module"):
            data = load_json(path, "staged Gradle module metadata")
            coordinate = coordinate_from_module_path(path, self.stage)
            if not belongs_to_group(coordinate.group, self.requirements.group_prefix):
                raise MergeError(f"staged module escaped fork group: {coordinate.display()}")
            component = coordinate_from_module(data, path)
            if not belongs_to_group(component.group, self.requirements.group_prefix):
                raise MergeError(f"staged component escaped fork group: {component.display()}")
            variants_by_name(data, path)
            if path.resolve() in module_cache:
                raise MergeError(f"duplicate staged module path: {path}")
            module_cache[path.resolve()] = (coordinate, data)

        for path, (coordinate, data) in module_cache.items():
            reference = component_reference(data, path, coordinate, self.stage)
            if reference is not None:
                component_coordinate, component_path = reference
                component_record = module_cache.get(component_path.resolve())
                if component_record is None:
                    raise MergeError(
                        f"staged target {coordinate.display()} has dangling component.url to "
                        f"{component_coordinate.display()} at {component_path}"
                    )
                if component_record[0] != component_coordinate:
                    raise MergeError(
                        f"staged target {coordinate.display()} component.url coordinate mismatch: "
                        f"{component_coordinate.display()} != {component_record[0].display()}"
                    )
            for variant in data["variants"]:
                name = variant["name"]
                self.validate_variant_files(path, variant, f"{coordinate.display()}:{name}")
                available = variant.get("available-at")
                if available is None:
                    continue
                target_coordinate = available_coordinate(variant, f"{coordinate.display()}:{name}")
                assert target_coordinate is not None
                url = available.get("url")
                target_path = self.resolve_url(path.parent, url, f"{coordinate.display()}:{name}")
                target = module_cache.get(target_path.resolve())
                if target is None:
                    raise MergeError(
                        f"dangling available-at {coordinate.display()}:{name} -> {target_path}"
                    )
                if target[0] != target_coordinate:
                    raise MergeError(
                        f"available-at coordinate mismatch for {coordinate.display()}:{name}: "
                        f"advertised {target_coordinate.display()}, file declares {target[0].display()}"
                    )
                target_reference = component_reference(
                    target[1],
                    target_path,
                    target_coordinate,
                    self.stage,
                )
                if target_reference is None or target_reference[0] != coordinate:
                    declared = (
                        target_reference[0].display()
                        if target_reference is not None
                        else "no root component"
                    )
                    raise MergeError(
                        f"available-at {coordinate.display()}:{name} targets "
                        f"{target_coordinate.display()}, whose component links to {declared}"
                    )

        module_coordinates = {coordinate for coordinate, _ in module_cache.values()}
        pom_only_coordinates = set(self.requirements.pom_only_modules)
        unexpected_metadata = module_coordinates.intersection(pom_only_coordinates)
        if unexpected_metadata:
            raise MergeError(
                "POM-only staged modules unexpectedly contain Gradle metadata: "
                + ", ".join(sorted(value.display() for value in unexpected_metadata))
            )
        represented_coordinates = module_coordinates.union(pom_only_coordinates)
        if represented_coordinates != self.assembled:
            missing = self.assembled - represented_coordinates
            unexpected = represented_coordinates - self.assembled
            details: list[str] = []
            if missing:
                details.append(
                    "unrepresented " + ", ".join(sorted(value.display() for value in missing))
                )
            if unexpected:
                details.append(
                    "unexpected " + ", ".join(sorted(value.display() for value in unexpected))
                )
            raise MergeError("staged coordinate accounting differs: " + "; ".join(details))
        for coordinate in sorted(self.assembled):
            self.validate_pom(coordinate, self.assembled)

        for requirement in self.requirements.modules.values():
            path = requirement.coordinate.module_path(self.stage).resolve()
            staged = module_cache.get(path)
            if staged is None:
                raise MergeError(f"required staged module is missing: {requirement.coordinate.display()}")
            variants = variants_by_name(staged[1], path)
            required_names = {
                name for names in requirement.required_variants.values() for name in names
            }
            actual_names = set(variants)
            if actual_names != required_names:
                raise MergeError(
                    f"{requirement.coordinate.display()} final variants differ from requirements; "
                    f"missing={sorted(required_names - actual_names)}, "
                    f"unexpected={sorted(actual_names - required_names)}"
                )
            for platform, names in requirement.required_variants.items():
                for name in names:
                    variant = variants[name]
                    if variant_platform(variant) != platform:
                        raise MergeError(
                            f"final {requirement.coordinate.display()}:{name} is not {platform}"
                        )
                    if platform != "common":
                        target = available_coordinate(
                            variant, f"{requirement.coordinate.display()}:{name}"
                        )
                        if target is None or target.module != requirement.target_modules[platform]:
                            raise MergeError(
                                f"final {requirement.coordinate.display()}:{name} has wrong target"
                            )
        self.validate_sidecars()

    def validate_pom(
        self,
        coordinate: Coordinate,
        staged_coordinates: set[Coordinate],
    ) -> None:
        path = coordinate.version_dir(self.stage) / f"{coordinate.module}-{coordinate.version}.pom"
        if not path.is_file():
            raise MergeError(f"staged module has no Maven POM: {coordinate.display()}")
        root = load_pom(path)
        declared = Coordinate(
            xml_child_text(root, "groupId") or "",
            xml_child_text(root, "artifactId") or "",
            xml_child_text(root, "version") or "",
        )
        if declared != coordinate:
            raise MergeError(
                f"Maven POM coordinate mismatch at {path}: "
                f"{declared.display()} != {coordinate.display()}"
            )

        def validate_dependency(element: ElementTree.Element, require_artifact: bool) -> None:
            group = xml_child_text(element, "groupId")
            module = xml_child_text(element, "artifactId")
            if not isinstance(group, str) or not belongs_to_group(
                group, self.requirements.group_prefix
            ):
                return
            if not isinstance(module, str):
                raise MergeError(f"fork dependency in {path} has no artifactId")
            version = xml_child_text(element, "version")
            if not isinstance(version, str):
                raise MergeError(f"fork dependency {group}:{module} in {path} has no exact version")
            normalized_version = exact_pom_dependency_version(
                version,
                f"fork dependency {group}:{module}:{version} in {path}",
            )
            dependency = Coordinate(group, module, normalized_version)
            if require_artifact and dependency not in staged_coordinates:
                raise MergeError(
                    f"Maven POM {path} has dangling fork dependency {dependency.display()}"
                )

        def dependencies_in(container: ElementTree.Element) -> Iterable[ElementTree.Element]:
            for child in container:
                if child.tag.rsplit("}", 1)[-1] == "dependency":
                    yield child

        for child in root:
            local_name = child.tag.rsplit("}", 1)[-1]
            if local_name == "dependencies":
                for dependency in dependencies_in(child):
                    validate_dependency(dependency, True)
            elif local_name == "dependencyManagement":
                for managed in child:
                    if managed.tag.rsplit("}", 1)[-1] != "dependencies":
                        continue
                    for dependency in dependencies_in(managed):
                        # Maven dependency-management entries are constraints. They do not
                        # fetch an artifact, but fork versions still have to be exact/stable.
                        validate_dependency(dependency, False)

    def validate_variant_files(
        self,
        module_path: Path,
        variant: Mapping[str, Any],
        context: str,
    ) -> None:
        files = variant.get("files", [])
        if not isinstance(files, list):
            raise MergeError(f"{context} has invalid files array")
        for entry in files:
            if not isinstance(entry, dict):
                raise MergeError(f"{context} has a non-object file entry")
            name = entry.get("name")
            url = entry.get("url")
            if (
                not isinstance(name, str)
                or not name
                or Path(name).name != name
                or any(character in name for character in ("/", "\\", "\0"))
            ):
                raise MergeError(f"{context} has an invalid payload name {name!r}")
            payload = self.resolve_url(module_path.parent, url, context)
            if payload.parent != module_path.parent.resolve():
                raise MergeError(f"{context} payload URL leaves its Maven version directory: {url!r}")
            validate_declared_payload(payload, entry, context)

    def resolve_url(self, base: Path, value: Any, context: str) -> Path:
        if not isinstance(value, str) or not value:
            raise MergeError(f"{context} has an empty/non-string URL")
        if any(token in value for token in (":", "\\", "\0", "?", "#")):
            raise MergeError(f"{context} uses a non-local URL {value!r}")
        candidate = Path(value)
        if candidate.is_absolute():
            raise MergeError(f"{context} uses absolute URL {value!r}")
        resolved = (base / candidate).resolve()
        try:
            resolved.relative_to(self.stage.resolve())
        except ValueError as error:
            raise MergeError(f"{context} URL escapes staged repository: {value!r}") from error
        return resolved

    def validate_sidecars(self) -> None:
        for path in self.stage.rglob("*"):
            if not path.is_file():
                continue
            suffix = path.suffix.lower()
            if suffix in HASH_SUFFIXES:
                base = path.with_suffix("")
                if not base.is_file():
                    raise MergeError(f"orphan staged checksum {path}")
                value = path.read_text(encoding="ascii").strip().lower()
                if value != checksum(base, HASH_SUFFIXES[suffix]):
                    raise MergeError(f"invalid staged checksum {path}")
            elif suffix == ".asc" and not path.with_suffix("").is_file():
                raise MergeError(f"orphan staged signature {path}")


def install_transactionally(
    stage: Path,
    destination: Path,
    relative_dirs: Iterable[str],
    post_install: Callable[[], None] | None = None,
) -> None:
    transaction = stage.parent
    backup_root = transaction / "backup"
    backup_root.mkdir()
    moved: list[tuple[Path, Path | None]] = []
    relatives = [Path(value) for value in sorted(set(relative_dirs))]
    for relative in relatives:
        if relative.is_absolute() or not relative.parts or any(
            part in {"", ".", ".."} for part in relative.parts
        ):
            raise MergeError(f"unsafe staged version directory {relative!s}")
        source = stage / relative
        try:
            source.resolve(strict=True).relative_to(stage.resolve())
        except (OSError, ValueError) as error:
            raise MergeError(f"staged version directory escapes staging: {source}") from error
        if not source.is_dir() or source.is_symlink():
            raise MergeError(f"validated version directory disappeared or is unsafe: {source}")

        cursor = destination
        for part in relative.parts:
            cursor /= part
            if cursor.is_symlink():
                raise MergeError(f"destination Maven path contains a symlink: {cursor}")
            if cursor.exists() and not cursor.is_dir():
                raise MergeError(f"destination Maven path is not a directory: {cursor}")
    try:
        for index, relative in enumerate(relatives):
            source = stage / relative
            target = destination / relative
            if not source.is_dir():
                raise MergeError(f"validated version directory disappeared: {source}")
            target.parent.mkdir(parents=True, exist_ok=True)
            backup: Path | None = None
            if target.exists():
                if not target.is_dir():
                    raise MergeError(f"destination version path is not a directory: {target}")
                backup = backup_root / str(index)
                os.replace(target, backup)
            try:
                os.replace(source, target)
            except Exception:
                if backup is not None:
                    os.replace(backup, target)
                raise
            moved.append((target, backup))
        if post_install is not None:
            post_install()
    except Exception:
        for rollback_index, (target, backup) in enumerate(reversed(moved)):
            failed = transaction / "rollback" / str(rollback_index)
            failed.parent.mkdir(parents=True, exist_ok=True)
            if target.exists():
                os.replace(target, failed)
            if backup is not None and backup.exists():
                os.replace(backup, target)
        raise
    for _, backup in moved:
        if backup is not None and backup.exists():
            shutil.rmtree(backup)


def run(argv: Sequence[str] | None = None) -> Report:
    args = parse_args(argv)
    raw_inputs = {owner: getattr(args, owner) for owner in OWNERS}
    for owner, input_path in raw_inputs.items():
        if input_path.is_symlink():
            raise MergeError(f"{owner} repository root must not be a symlink: {input_path}")
    if args.requirements.is_symlink():
        raise MergeError(f"requirements file must not be a symlink: {args.requirements}")
    inputs = {owner: path.resolve() for owner, path in raw_inputs.items()}
    destination = args.destination.resolve()
    requirements_path = args.requirements.resolve()
    report_path = args.report.resolve() if args.report is not None else None
    if paths_overlap(requirements_path, destination):
        raise MergeError(
            f"requirements file overlaps destination repository: "
            f"{requirements_path} and {destination}"
        )
    if report_path is not None:
        if args.report.is_symlink():
            raise MergeError(f"report path must not be a symlink: {args.report}")
        if report_path.exists() and not report_path.is_file():
            raise MergeError(f"report path is not a regular file: {report_path}")
        if report_path == requirements_path:
            raise MergeError(f"report path would overwrite requirements: {report_path}")
        if paths_overlap(report_path, destination):
            raise MergeError(
                f"report path overlaps destination repository: {report_path} and {destination}"
            )
        for owner, input_path in inputs.items():
            if paths_overlap(report_path, input_path):
                raise MergeError(
                    f"report path overlaps {owner} input repository: "
                    f"{report_path} and {input_path}"
                )
    requirements_sha256 = sha256_file(requirements_path, "requirements")
    requirements = load_requirements(requirements_path)
    if sha256_file(requirements_path, "requirements") != requirements_sha256:
        raise MergeError("requirements file changed while it was validated")
    for index, first_owner in enumerate(OWNERS):
        for second_owner in OWNERS[index + 1 :]:
            if paths_overlap(inputs[first_owner], inputs[second_owner]):
                raise MergeError(
                    f"input repositories overlap: {first_owner}={inputs[first_owner]} and "
                    f"{second_owner}={inputs[second_owner]}"
                )
    for owner, input_path in inputs.items():
        if paths_overlap(input_path, destination):
            raise MergeError(
                f"destination repository overlaps {owner} input: {destination} and {input_path}"
            )
    source_provenance = validate_source_provenance(inputs, requirements)
    indexes = {
        owner: module_index(owner, path, requirements.group_prefix)
        for owner, path in inputs.items()
    }

    destination_parent = destination.parent
    if not destination_parent.is_dir():
        raise MergeError(f"destination parent does not exist: {destination_parent}")
    transaction = Path(
        tempfile.mkdtemp(prefix=".merge-maven-artifact-", dir=destination_parent)
    ).resolve()
    stage = transaction / "stage"
    stage.mkdir()
    report_temporary: Path | None = None
    try:
        assembler = Assembler(
            inputs=inputs,
            indexes=indexes,
            destination=destination,
            stage=stage,
            requirements_path=requirements_path,
            requirements=requirements,
            requirements_sha256=requirements_sha256,
            source_provenance=source_provenance,
            dry_run=args.dry_run,
        )
        assembler.assemble()
        if report_path is not None:
            report_temporary = prepare_json_atomic(report_path, assembler.report.as_json())

        def install_report() -> None:
            nonlocal report_temporary
            assert report_temporary is not None and report_path is not None
            os.replace(report_temporary, report_path)
            report_temporary = None

        if not args.dry_run:
            destination.mkdir(parents=True, exist_ok=True)
            install_transactionally(
                stage,
                destination,
                assembler.report.version_directories,
                install_report if report_temporary is not None else None,
            )
        elif report_temporary is not None:
            install_report()
        return assembler.report
    finally:
        if report_temporary is not None and report_temporary.exists():
            report_temporary.unlink()
        if transaction.exists():
            shutil.rmtree(transaction)


def main(argv: Sequence[str] | None = None) -> int:
    try:
        report = run(argv)
    except MergeError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    result = report.as_json()
    print(
        f"validated {len(result['modules'])} modules in "
        f"{len(result['versionDirectories'])} version directories"
    )
    print(f"synthesized files: {len(result['synthesizedFiles'])}")
    print(f"dropped signatures: {len(result['droppedSignatures'])}")
    print(f"regenerated checksums: {len(result['regeneratedChecksums'])}")
    print("dry run: destination unchanged" if report.dry_run else "install complete")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
