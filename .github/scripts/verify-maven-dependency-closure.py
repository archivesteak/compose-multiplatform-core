#!/usr/bin/env python3
"""Verify dependencies in every Gradle-module/POM redirect of one KMP publication."""

from __future__ import annotations

import argparse
import json
import xml.etree.ElementTree as ET
from collections import deque
from pathlib import Path
from typing import Any, Iterable


Coordinate = tuple[str, str, str]


def parse_coordinate(value: str, description: str) -> Coordinate:
    parts = value.split(":")
    if len(parts) != 3 or not all(parts):
        raise ValueError(f"invalid {description}: {value!r}")
    return parts[0], parts[1], parts[2]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("repository", type=Path)
    parser.add_argument("--coordinate", required=True)
    parser.add_argument("--require-dependency", action="append", default=[])
    parser.add_argument("--forbid-dependency-group-prefix", action="append", default=[])
    return parser.parse_args()


def publication_path(repository: Path, coordinate: Coordinate) -> Path:
    group, artifact, version = coordinate
    return repository.joinpath(*group.split("."), artifact, version)


def load_module(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as stream:
        module = json.load(stream)
    if not isinstance(module, dict) or not isinstance(module.get("variants"), list):
        raise ValueError(f"{path} is not Gradle module metadata")
    return module


def module_dependencies(module: dict[str, Any]) -> Iterable[Coordinate]:
    for variant in module["variants"]:
        if not isinstance(variant, dict):
            continue
        yield from variant_dependencies(variant)


def variant_dependencies(variant: dict[str, Any]) -> Iterable[Coordinate]:
    dependencies = variant.get("dependencies", [])
    if not isinstance(dependencies, list):
        return
    for dependency in dependencies:
        if not isinstance(dependency, dict):
            continue
        group = dependency.get("group")
        artifact = dependency.get("module")
        version = dependency.get("version", {})
        required = version.get("requires") if isinstance(version, dict) else None
        if all(isinstance(value, str) and value for value in (group, artifact, required)):
            yield group, artifact, required


def module_redirects(module: dict[str, Any]) -> Iterable[Coordinate]:
    for variant in module["variants"]:
        if not isinstance(variant, dict):
            continue
        redirect = variant.get("available-at")
        if not isinstance(redirect, dict):
            continue
        coordinate = tuple(redirect.get(key) for key in ("group", "module", "version"))
        if all(isinstance(value, str) and value for value in coordinate):
            yield coordinate  # type: ignore[misc]


def pom_dependencies(path: Path) -> Iterable[Coordinate]:
    root = ET.parse(path).getroot()
    # Only direct project dependencies form the published consumer contract. In particular, an
    # entry under dependencyManagement must not be allowed to hide an absent dependency.
    dependencies = root.find("{*}dependencies")
    if dependencies is None:
        return
    for dependency in dependencies.findall("{*}dependency"):
        values = []
        for name in ("groupId", "artifactId", "version"):
            element = dependency.find(f"{{*}}{name}")
            values.append(element.text.strip() if element is not None and element.text else "")
        if all(values):
            yield values[0], values[1], values[2]


def variant_name(variant: dict[str, Any], module_path: Path) -> str:
    name = variant.get("name")
    if not isinstance(name, str) or not name:
        raise ValueError(f"{module_path} contains a variant without a name")
    return name


def is_api_or_runtime_variant(variant: dict[str, Any]) -> bool:
    attributes = variant.get("attributes")
    if not isinstance(attributes, dict):
        return False
    if attributes.get("org.gradle.category") != "library":
        return False
    return attributes.get("org.gradle.usage") in {
        "java-api",
        "java-runtime",
        "kotlin-api",
        "kotlin-runtime",
    }


def render_coordinates(coordinates: Iterable[Coordinate]) -> str:
    return ", ".join(":".join(value) for value in sorted(coordinates))


def verify_exact_dependencies(
    label: str,
    dependencies: set[Coordinate],
    required: set[Coordinate],
    forbidden_group_prefixes: list[str],
) -> None:
    missing = required - dependencies
    if missing:
        raise ValueError(f"{label} lacks required dependencies: {render_coordinates(missing)}")
    verify_forbidden_dependencies(label, dependencies, forbidden_group_prefixes)


def verify_forbidden_dependencies(
    label: str,
    dependencies: set[Coordinate],
    forbidden_group_prefixes: list[str],
) -> None:
    forbidden = {
        dependency
        for dependency in dependencies
        if any(dependency[0].startswith(prefix) for prefix in forbidden_group_prefixes)
    }
    if forbidden:
        raise ValueError(
            f"{label} contains forbidden dependencies: {render_coordinates(forbidden)}"
        )


def target_suffix(root_artifact: str, target_artifact: str) -> str | None:
    if not target_artifact.startswith(root_artifact):
        return None
    suffix = target_artifact[len(root_artifact) :]
    return suffix if suffix.startswith("-") else None


def verify_target_pom_dependencies(
    label: str,
    dependencies: set[Coordinate],
    required: set[Coordinate],
    forbidden_group_prefixes: list[str],
    suffix: str | None,
) -> None:
    missing: list[Coordinate] = []
    for coordinate in sorted(required):
        group, artifact, version = coordinate
        # Gradle metadata keeps a dependency on the KMP root coordinate, while a target POM
        # normally projects it to the sibling target artifact (for example runtime-mingwx64).
        # A base coordinate is also valid for dependencies which are not themselves KMP.
        candidates = {coordinate}
        if suffix is not None:
            candidates.add((group, artifact + suffix, version))
        if dependencies.isdisjoint(candidates):
            missing.append(coordinate)
    if missing:
        raise ValueError(
            f"{label} lacks required dependencies: {render_coordinates(missing)}"
        )
    verify_forbidden_dependencies(label, dependencies, forbidden_group_prefixes)


def verify(
    repository: Path,
    root_coordinate: Coordinate,
    required: set[Coordinate],
    forbidden_group_prefixes: list[str],
) -> tuple[int, int, int]:
    repository = repository.resolve()
    pending = deque([root_coordinate])
    publications: dict[Coordinate, tuple[Path, dict[str, Any], Path, set[Coordinate]]] = {}

    while pending:
        coordinate = pending.popleft()
        if coordinate in publications:
            continue
        group, artifact, version = coordinate
        directory = publication_path(repository, coordinate)
        module_path = directory / f"{artifact}-{version}.module"
        pom_path = directory / f"{artifact}-{version}.pom"
        if not module_path.is_file():
            raise ValueError(f"missing Gradle module metadata: {module_path}")
        if not pom_path.is_file():
            raise ValueError(f"missing Maven POM: {pom_path}")

        module = load_module(module_path)
        pom_dependency_set = set(pom_dependencies(pom_path))
        publications[coordinate] = (module_path, module, pom_path, pom_dependency_set)
        pending.extend(module_redirects(module))

    root_module_path, root_module, root_pom_path, root_pom_dependencies = publications[
        root_coordinate
    ]
    root_gradle_dependencies = set(module_dependencies(root_module))
    verify_exact_dependencies(
        f"root Gradle module metadata {root_module_path}",
        root_gradle_dependencies,
        required,
        forbidden_group_prefixes,
    )
    verify_exact_dependencies(
        f"root Maven POM metadata {root_pom_path}",
        root_pom_dependencies,
        required,
        forbidden_group_prefixes,
    )

    root_artifact = root_coordinate[1]
    for coordinate, (module_path, module, pom_path, pom_dependency_set) in publications.items():
        if coordinate == root_coordinate:
            continue

        verify_forbidden_dependencies(
            f"redirected child Gradle module metadata {module_path}",
            set(module_dependencies(module)),
            forbidden_group_prefixes,
        )
        relevant_variants: list[dict[str, Any]] = []
        for variant in module["variants"]:
            if not isinstance(variant, dict):
                raise ValueError(f"{module_path} contains a non-object variant")
            # Validate names even on documentation variants so malformed metadata is never hidden
            # merely because it is outside the dependency contract.
            variant_name(variant, module_path)
            if is_api_or_runtime_variant(variant):
                relevant_variants.append(variant)
        if not relevant_variants:
            raise ValueError(f"redirected child {module_path} has no API/runtime variants")

        for variant in relevant_variants:
            name = variant_name(variant, module_path)
            verify_exact_dependencies(
                f"redirected child Gradle variant {module_path} {name}",
                set(variant_dependencies(variant)),
                required,
                forbidden_group_prefixes,
            )

        suffix = target_suffix(root_artifact, coordinate[1])
        verify_target_pom_dependencies(
            f"target Maven POM metadata {pom_path}",
            pom_dependency_set,
            required,
            forbidden_group_prefixes,
            suffix,
        )

    all_gradle_dependencies = {
        dependency
        for _, module, _, _ in publications.values()
        for dependency in module_dependencies(module)
    }
    all_pom_dependencies = {
        dependency
        for _, _, _, dependencies in publications.values()
        for dependency in dependencies
    }
    return len(publications), len(all_gradle_dependencies), len(all_pom_dependencies)


def main() -> None:
    args = parse_args()
    root_coordinate = parse_coordinate(args.coordinate, "coordinate")
    required = {
        parse_coordinate(value, "required dependency")
        for value in args.require_dependency
    }
    counts = verify(
        args.repository,
        root_coordinate,
        required,
        args.forbid_dependency_group_prefix,
    )
    publications, gradle_dependencies, pom_dependencies_count = counts
    print(
        f"verified dependency closure for {args.coordinate}: "
        f"{publications} publications, {gradle_dependencies} Gradle dependencies, "
        f"{pom_dependencies_count} POM dependencies"
    )


if __name__ == "__main__":
    main()
