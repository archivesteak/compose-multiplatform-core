#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("verify-maven-dependency-closure.py")
SPEC = importlib.util.spec_from_file_location("verify_maven_dependency_closure", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)

ROOT = ("example", "library", "1")
RUNTIME = ("example.dependencies", "runtime", "2")
NAVIGATION = ("example.dependencies", "navigation", "3")
REQUIRED = {RUNTIME, NAVIGATION}
TARGETS = ("mingwx64", "iosarm64")


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


def dependency(coordinate: tuple[str, str, str]) -> dict[str, object]:
    group, artifact, version = coordinate
    return {"group": group, "module": artifact, "version": {"requires": version}}


def library_variant(
    name: str,
    usage: str,
    dependencies: set[tuple[str, str, str]],
) -> dict[str, object]:
    return {
        "name": name,
        "attributes": {
            "org.gradle.category": "library",
            "org.gradle.usage": usage,
        },
        "dependencies": [dependency(value) for value in sorted(dependencies)],
    }


def write_pom(
    path: Path,
    coordinates: set[tuple[str, str, str]],
    managed_coordinates: set[tuple[str, str, str]] | None = None,
) -> None:
    def dependencies_xml(values: set[tuple[str, str, str]]) -> str:
        return "".join(
            "<dependency>"
            f"<groupId>{group}</groupId>"
            f"<artifactId>{artifact}</artifactId>"
            f"<version>{version}</version>"
            "</dependency>"
            for group, artifact, version in sorted(values)
        )

    managed = ""
    if managed_coordinates is not None:
        managed = (
            "<dependencyManagement><dependencies>"
            + dependencies_xml(managed_coordinates)
            + "</dependencies></dependencyManagement>"
        )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "<project>"
        + managed
        + "<dependencies>"
        + dependencies_xml(coordinates)
        + "</dependencies></project>",
        encoding="utf-8",
    )


def publication_directory(
    repository: Path,
    coordinate: tuple[str, str, str],
) -> Path:
    return VERIFIER.publication_path(repository, coordinate)


def child_coordinate(target: str) -> tuple[str, str, str]:
    return ROOT[0], f"{ROOT[1]}-{target}", ROOT[2]


def child_module_path(repository: Path, target: str) -> Path:
    coordinate = child_coordinate(target)
    directory = publication_directory(repository, coordinate)
    return directory / f"{coordinate[1]}-{coordinate[2]}.module"


def child_pom_path(repository: Path, target: str) -> Path:
    coordinate = child_coordinate(target)
    directory = publication_directory(repository, coordinate)
    return directory / f"{coordinate[1]}-{coordinate[2]}.pom"


def projected_dependencies(target: str) -> set[tuple[str, str, str]]:
    return {
        (group, f"{artifact}-{target}", version)
        for group, artifact, version in REQUIRED
    }


def make_repository(root: Path) -> Path:
    repository = root / "repository"
    root_directory = publication_directory(repository, ROOT)
    root_module_path = root_directory / "library-1.module"
    root_variants: list[dict[str, object]] = [
        library_variant("metadataApiElements", "kotlin-metadata", REQUIRED)
    ]
    for target in TARGETS:
        coordinate = child_coordinate(target)
        redirect = {
            "url": f"../../library-{target}/1/library-{target}-1.module",
            "group": coordinate[0],
            "module": coordinate[1],
            "version": coordinate[2],
        }
        root_variants.extend(
            [
                {
                    "name": f"{target}ApiElements-published",
                    "attributes": {
                        "org.gradle.category": "library",
                        "org.gradle.usage": "kotlin-api",
                    },
                    "available-at": redirect,
                },
                {
                    "name": f"{target}RuntimeElements-published",
                    "attributes": {
                        "org.gradle.category": "library",
                        "org.gradle.usage": "kotlin-runtime",
                    },
                    "available-at": redirect,
                },
                {
                    "name": f"{target}SourcesElements-published",
                    "attributes": {
                        "org.gradle.category": "documentation",
                        "org.gradle.usage": "kotlin-runtime",
                    },
                    "available-at": redirect,
                },
            ]
        )
    write_json(root_module_path, {"variants": root_variants})
    write_pom(root_directory / "library-1.pom", REQUIRED)

    for target in TARGETS:
        module_path = child_module_path(repository, target)
        write_json(
            module_path,
            {
                "variants": [
                    library_variant(
                        f"{target}ApiElements-published", "kotlin-api", REQUIRED
                    ),
                    library_variant(
                        f"{target}RuntimeElements-published", "kotlin-runtime", REQUIRED
                    ),
                    {
                        "name": f"{target}SourcesElements-published",
                        "attributes": {
                            "org.gradle.category": "documentation",
                            "org.gradle.usage": "kotlin-runtime",
                        },
                    },
                ]
            },
        )
        write_pom(child_pom_path(repository, target), projected_dependencies(target))
    return repository


def replace_variant_dependencies(
    repository: Path,
    target: str,
    variant_name: str,
    coordinates: set[tuple[str, str, str]],
) -> None:
    path = child_module_path(repository, target)
    module = json.loads(path.read_text(encoding="utf-8"))
    variant = next(value for value in module["variants"] if value["name"] == variant_name)
    variant["dependencies"] = [dependency(value) for value in sorted(coordinates)]
    write_json(path, module)


class VerifyMavenDependencyClosureTest(unittest.TestCase):
    def verify(self, repository: Path) -> tuple[int, int, int]:
        return VERIFIER.verify(repository, ROOT, REQUIRED, ["forbidden."])

    def test_accepts_each_complete_child_variant_and_target_pom(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = make_repository(Path(directory))
            publications, _, _ = self.verify(repository)
            self.assertEqual(3, publications)

    def test_rejects_previously_hidden_broken_child(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = make_repository(Path(directory))
            replace_variant_dependencies(
                repository,
                "iosarm64",
                "iosarm64ApiElements-published",
                {RUNTIME},
            )

            # The old verifier passed this fixture: the root and mingw child made its global union
            # complete even though the ios API variant omitted NavigationEvent.
            with self.assertRaisesRegex(
                ValueError,
                r"library-iosarm64-1\.module iosarm64ApiElements-published.*navigation:3",
            ):
                self.verify(repository)

    def test_rejects_broken_runtime_variant_even_when_api_variant_is_complete(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = make_repository(Path(directory))
            replace_variant_dependencies(
                repository,
                "mingwx64",
                "mingwx64RuntimeElements-published",
                {NAVIGATION},
            )

            with self.assertRaisesRegex(
                ValueError,
                r"mingwx64RuntimeElements-published.*runtime:2",
            ):
                self.verify(repository)

    def test_rejects_broken_target_pom_even_when_other_poms_are_complete(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = make_repository(Path(directory))
            path = child_pom_path(repository, "iosarm64")
            # dependencyManagement is not a direct dependency and must not satisfy the contract.
            write_pom(path, {next(iter(projected_dependencies("iosarm64")))}, REQUIRED)

            with self.assertRaisesRegex(
                ValueError,
                r"target Maven POM metadata .*library-iosarm64-1\.pom.*lacks required",
            ):
                self.verify(repository)

    def test_target_pom_may_keep_a_non_kmp_dependency_at_its_base_coordinate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = make_repository(Path(directory))
            write_pom(
                child_pom_path(repository, "mingwx64"),
                {
                    RUNTIME,
                    (
                        NAVIGATION[0],
                        NAVIGATION[1] + "-mingwx64",
                        NAVIGATION[2],
                    ),
                },
            )
            self.verify(repository)

    def test_documentation_variant_does_not_need_runtime_dependencies(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = make_repository(Path(directory))
            # The synthetic source variants intentionally have no dependencies. Their
            # kotlin-runtime usage must not make them consumer runtime variants.
            self.verify(repository)


if __name__ == "__main__":
    unittest.main()
