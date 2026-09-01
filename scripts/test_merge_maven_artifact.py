#!/usr/bin/env python3
"""Synthetic, filesystem-isolated tests for merge-maven-artifact.py."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from typing import Any
from unittest import mock


SCRIPT = Path(__file__).with_name("merge-maven-artifact.py")
SPEC = importlib.util.spec_from_file_location("merge_maven_artifact", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MERGER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MERGER
SPEC.loader.exec_module(MERGER)

GROUP = "io.github.archivesteak.test"
MODULE = "demo"
VERSION = "1.0.0-mingw"
SOURCE_PROVENANCE = {
    "windows": {"compose": "1" * 40, "skia": "3" * 40, "skiko": "2" * 40},
    "apple": {"compose": "1" * 40, "skiko": "2" * 40},
    "web": {"compose": "1" * 40, "skiko": "2" * 40},
}


def digest(data: bytes, algorithm: str) -> str:
    return hashlib.new(algorithm, data).hexdigest()


def payload_entry(filename: str, data: bytes) -> dict[str, Any]:
    return {
        "name": filename,
        "url": filename,
        "size": len(data),
        "md5": digest(data, "md5"),
        "sha1": digest(data, "sha1"),
        "sha256": digest(data, "sha256"),
        "sha512": digest(data, "sha512"),
    }


def attributes(platform: str) -> dict[str, str]:
    result = {
        "org.gradle.category": "library",
        "org.gradle.usage": "kotlin-api",
    }
    if platform == "common":
        result["org.jetbrains.kotlin.platform.type"] = "common"
        result["org.gradle.usage"] = "kotlin-metadata"
    elif platform == "jvm":
        result["org.jetbrains.kotlin.platform.type"] = "jvm"
    elif platform == "js":
        result["org.jetbrains.kotlin.platform.type"] = "js"
    else:
        result["org.jetbrains.kotlin.platform.type"] = "native"
        result["org.jetbrains.kotlin.native.target"] = {
            "mingwX64": "mingw_x64",
            "macosArm64": "macos_arm64",
        }[platform]
    return result


def available_variant(name: str, platform: str, target: str) -> dict[str, Any]:
    return {
        "name": name,
        "attributes": attributes(platform),
        "available-at": {
            "url": f"../../{target}/{VERSION}/{target}-{VERSION}.module",
            "group": GROUP,
            "module": target,
            "version": VERSION,
        },
    }


def version_dir(repository: Path, module: str) -> Path:
    return repository.joinpath(*GROUP.split("."), module, VERSION)


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def rewrite_json(path: Path, action: Any) -> None:
    value = read_json(path)
    action(value)
    write_json(path, value)


def write_checksum_sidecar(path: Path, algorithm: str) -> Path:
    sidecar = path.with_name(f"{path.name}.{algorithm}")
    sidecar.write_text(digest(path.read_bytes(), algorithm) + "\n", encoding="ascii")
    return sidecar


def write_pom(
    repository: Path,
    module: str,
    *,
    group: str = GROUP,
    version: str = VERSION,
    dependencies: list[tuple[str, str, str]] | None = None,
    dependency_management: list[tuple[str, str, str]] | None = None,
) -> Path:
    def dependency_xml(values: list[tuple[str, str, str]] | None) -> str:
        return "".join(
            "<dependency>"
            f"<groupId>{dependency_group}</groupId>"
            f"<artifactId>{dependency_module}</artifactId>"
            f"<version>{dependency_version}</version>"
            "</dependency>"
            for dependency_group, dependency_module, dependency_version in (values or [])
        )

    path = version_dir(repository, module) / f"{module}-{VERSION}.pom"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
        "<modelVersion>4.0.0</modelVersion>"
        f"<groupId>{group}</groupId>"
        f"<artifactId>{module}</artifactId>"
        f"<version>{version}</version>"
        "<dependencyManagement><dependencies>"
        f"{dependency_xml(dependency_management)}"
        "</dependencies></dependencyManagement>"
        f"<dependencies>{dependency_xml(dependencies)}</dependencies>"
        "</project>\n",
        encoding="utf-8",
    )
    return path


def write_module(
    repository: Path,
    module: str,
    variants: list[dict[str, Any]],
    payloads: dict[str, bytes] | None = None,
    *,
    root_module: str | None = None,
) -> Path:
    directory = version_dir(repository, module)
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / f"{module}-{VERSION}.module"
    component_module = root_module or module
    component = {"group": GROUP, "module": component_module, "version": VERSION}
    if root_module is not None:
        component["url"] = (
            f"../../{component_module}/{VERSION}/{component_module}-{VERSION}.module"
        )
    write_json(
        path,
        {
            "formatVersion": "1.1",
            "component": component,
            "variants": variants,
        },
    )
    for filename, data in (payloads or {}).items():
        (directory / filename).write_bytes(data)
    return path


def write_target(
    repository: Path,
    module: str,
    name: str,
    platform: str,
    *,
    root_module: str | None = None,
) -> None:
    suffix = ".klib" if platform in {"mingwX64", "macosArm64"} else ".jar"
    filename = f"{module}-{VERSION}{suffix}"
    data = f"payload:{module}:{platform}".encode()
    path = write_module(
        repository,
        module,
        [
            {
                "name": name,
                "attributes": attributes(platform),
                "files": [payload_entry(filename, data)],
            }
        ],
        {filename: data},
        root_module=root_module,
    )
    payload = path.parent / filename
    write_pom(repository, module)
    (payload.parent / f"{payload.name}.sha256").write_text(
        digest(data, "sha256") + "\n", encoding="ascii"
    )
    (payload.parent / f"{payload.name}.asc").write_text("test-signature\n", encoding="ascii")


def tooling_target(platform: str) -> dict[str, Any]:
    if platform in {"mingwX64", "macosArm64"}:
        return {
            "target": "org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget",
            "platformType": "native",
            "extras": {
                "native": {
                    "konanTarget": {"mingwX64": "mingw_x64", "macosArm64": "macos_arm64"}[
                        platform
                    ],
                    "konanVersion": "2.4.10",
                    "konanAbiVersion": "2.4.0",
                }
            },
        }
    return {
        "target": {
            "common": "org.jetbrains.kotlin.gradle.plugin.mpp.KotlinMetadataTarget",
            "jvm": "org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget",
            "js": "org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget",
        }[platform],
        "platformType": platform,
    }


class MergerFixture:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.inputs = {owner: root / owner for owner in ("windows", "apple", "web")}
        self.destination = root / "destination" / "repository"
        self.destination.parent.mkdir(parents=True)
        self.requirements = root / "requirements.json"
        self.report = root / "report.json"

    def module_path(self, owner: str, module: str = MODULE) -> Path:
        return version_dir(self.inputs[owner], module) / f"{module}-{VERSION}.module"

    def tooling_path(self, owner: str, module: str = MODULE) -> Path:
        return version_dir(self.inputs[owner], module) / (
            f"{module}-{VERSION}-kotlin-tooling-metadata.json"
        )

    def payload_path(self, owner: str, module: str, suffix: str = ".jar") -> Path:
        return version_dir(self.inputs[owner], module) / f"{module}-{VERSION}{suffix}"

    def provenance_path(self, owner: str) -> Path:
        return self.inputs[owner] / "provenance" / f"{owner}.json"

    def create(self) -> None:
        root_payload_name = f"{MODULE}-{VERSION}.jar"
        root_payload = b"common-metadata"
        variants = {
            "windows": [
                {
                    "name": "metadataApiElements",
                    "attributes": attributes("common"),
                    "files": [payload_entry(root_payload_name, root_payload)],
                },
                available_variant("desktopApiElements-published", "jvm", "demo-desktop"),
                available_variant("mingwX64ApiElements-published", "mingwX64", "demo-mingwx64"),
            ],
            "apple": [
                available_variant("macosArm64ApiElements-published", "macosArm64", "demo-macosarm64")
            ],
            "web": [available_variant("jsApiElements-published", "js", "demo-js")],
        }
        for owner, owner_variants in variants.items():
            payloads = {root_payload_name: root_payload} if owner == "windows" else None
            module_path = write_module(self.inputs[owner], MODULE, owner_variants, payloads)
            write_pom(self.inputs[owner], MODULE)
            tooling_name = f"{MODULE}-{VERSION}-kotlin-tooling-metadata.json"
            owned_platforms = {
                "windows": ["common", "jvm", "mingwX64"],
                "apple": ["macosArm64"],
                "web": ["js"],
            }[owner]
            write_json(
                module_path.parent / tooling_name,
                {
                    "schemaVersion": "1.1.0",
                    "buildSystem": "Gradle",
                    "buildSystemVersion": "9.6.1",
                    "buildPlugin": "org.jetbrains.kotlin.multiplatform",
                    "buildPluginVersion": "2.4.10",
                    "projectSettings": {"isHmppEnabled": True},
                    "projectTargets": [tooling_target(platform) for platform in owned_platforms],
                },
            )
        windows_root = version_dir(self.inputs["windows"], MODULE)
        root_module = windows_root / f"{MODULE}-{VERSION}.module"
        root_tooling = windows_root / f"{MODULE}-{VERSION}-kotlin-tooling-metadata.json"
        for path in (root_module, root_tooling):
            (path.parent / f"{path.name}.sha256").write_text(
                digest(path.read_bytes(), "sha256") + "\n", encoding="ascii"
            )
            signature = path.parent / f"{path.name}.asc"
            signature.write_text("obsolete-signature\n", encoding="ascii")
            write_checksum_sidecar(signature, "sha256")

        write_target(
            self.inputs["windows"],
            "demo-desktop",
            "desktopApiElements-published",
            "jvm",
            root_module=MODULE,
        )
        write_target(
            self.inputs["windows"],
            "demo-mingwx64",
            "mingwX64ApiElements-published",
            "mingwX64",
            root_module=MODULE,
        )
        write_target(
            self.inputs["apple"],
            "demo-macosarm64",
            "macosArm64ApiElements-published",
            "macosArm64",
            root_module=MODULE,
        )
        write_target(
            self.inputs["web"],
            "demo-js",
            "jsApiElements-published",
            "js",
            root_module=MODULE,
        )

        write_json(
            self.requirements,
            {
                "schemaVersion": 2,
                "groupPrefix": "io.github.archivesteak",
                "platformOwners": {
                    "common": "windows",
                    "jvm": "windows",
                    "mingwX64": "windows",
                    "macosArm64": "apple",
                    "js": "web",
                },
                "sourceProvenance": SOURCE_PROVENANCE,
                "pomOnlyModules": [],
                "modules": [
                    {
                        "coordinate": f"{GROUP}:{MODULE}:{VERSION}",
                        "requiredVariants": {
                            "common": ["metadataApiElements"],
                            "jvm": ["desktopApiElements-published"],
                            "mingwX64": ["mingwX64ApiElements-published"],
                            "macosArm64": ["macosArm64ApiElements-published"],
                            "js": ["jsApiElements-published"],
                        },
                        "targetModules": {
                            "jvm": "demo-desktop",
                            "mingwX64": "demo-mingwx64",
                            "macosArm64": "demo-macosarm64",
                            "js": "demo-js",
                        },
                    }
                ],
            },
        )
        for owner, sources in SOURCE_PROVENANCE.items():
            write_json(
                self.inputs[owner] / "provenance" / f"{owner}.json",
                {
                    "schemaVersion": 1,
                    "owner": owner,
                    "sources": sources,
                },
            )

    def args(self, *, dry_run: bool) -> list[str]:
        result = [
            "--windows",
            str(self.inputs["windows"]),
            "--apple",
            str(self.inputs["apple"]),
            "--web",
            str(self.inputs["web"]),
            "--destination",
            str(self.destination),
            "--requirements",
            str(self.requirements),
            "--report",
            str(self.report),
        ]
        if dry_run:
            result.append("--dry-run")
        return result


class MergeMavenArtifactTest(unittest.TestCase):
    def assert_fixture_rejected(self, mutation: Any, pattern: str) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = MergerFixture(Path(temporary))
            fixture.create()
            fixture.destination.mkdir(parents=True)
            sentinel = fixture.destination / "unrelated.txt"
            sentinel.write_text("keep", encoding="utf-8")
            mutation(fixture)
            with self.assertRaisesRegex(MERGER.MergeError, pattern):
                MERGER.run(fixture.args(dry_run=False))
            self.assertEqual(sentinel.read_text(encoding="utf-8"), "keep")
            self.assertEqual(
                sorted(path.name for path in fixture.destination.iterdir()),
                ["unrelated.txt"],
            )

    def test_dry_run_validates_without_installing(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = MergerFixture(Path(temporary))
            fixture.create()
            report = MERGER.run(fixture.args(dry_run=True))
            self.assertTrue(report.dry_run)
            self.assertFalse(fixture.destination.exists())
            self.assertTrue(fixture.report.is_file())
            self.assertEqual(len(report.version_directories), 5)
            self.assertEqual(
                report.requirements_sha256,
                digest(fixture.requirements.read_bytes(), "sha256"),
            )
            self.assertEqual(
                {owner: value["sources"] for owner, value in report.source_provenance.items()},
                SOURCE_PROVENANCE,
            )
            persisted = read_json(fixture.report)
            self.assertEqual(
                set(persisted),
                {
                    "destination",
                    "droppedSignatures",
                    "dryRun",
                    "equivalentLeafCopies",
                    "equivalentRootCopies",
                    "fileManifest",
                    "inputs",
                    "modules",
                    "regeneratedChecksums",
                    "requirements",
                    "requirementsSha256",
                    "sourceProvenance",
                    "synthesizedFiles",
                    "versionDirectories",
                },
            )
            self.assertEqual(persisted["requirementsSha256"], report.requirements_sha256)
            self.assertEqual(persisted["sourceProvenance"], report.source_provenance)
            self.assertEqual(persisted["fileManifest"], report.file_manifest)
            self.assertEqual(list(report.file_manifest), sorted(report.file_manifest))
            self.assertTrue(report.file_manifest)
            for relative, entry in report.file_manifest.items():
                self.assertEqual(set(entry), {"size", "sha256"})
                self.assertGreater(entry["size"], 0, relative)
                self.assertRegex(entry["sha256"], r"^[0-9a-f]{64}$")

    def test_file_manifest_detects_tampering_and_extra_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            second = repository / "z" / "second.jar"
            first = repository / "a" / "first.pom"
            second.parent.mkdir()
            first.parent.mkdir()
            second.write_bytes(b"second")
            first.write_bytes(b"first")

            original = MERGER.repository_file_manifest(repository)
            self.assertEqual(list(original), ["a/first.pom", "z/second.jar"])
            self.assertEqual(
                original["a/first.pom"],
                {"size": 5, "sha256": digest(b"first", "sha256")},
            )

            first.write_bytes(b"FIRST")
            tampered = MERGER.repository_file_manifest(repository)
            self.assertEqual(tampered["a/first.pom"]["size"], 5)
            self.assertNotEqual(
                tampered["a/first.pom"]["sha256"],
                original["a/first.pom"]["sha256"],
            )

            extra = repository / "a" / "unexpected.txt"
            extra.write_bytes(b"extra")
            with_extra = MERGER.repository_file_manifest(repository)
            self.assertEqual(
                set(with_extra) - set(tampered),
                {"a/unexpected.txt"},
            )

            extra.write_bytes(b"")
            with self.assertRaisesRegex(MERGER.MergeError, "staged file is empty"):
                MERGER.repository_file_manifest(repository)

    def test_rejects_missing_or_extra_provenance_without_installing(self) -> None:
        for condition in ("missing-directory", "missing-marker", "extra-marker", "extra-root"):
            with self.subTest(condition=condition):
                def mutate(fixture: MergerFixture, failure: str = condition) -> None:
                    if failure == "missing-directory":
                        shutil.rmtree(fixture.inputs["apple"] / "provenance")
                    elif failure == "missing-marker":
                        fixture.provenance_path("apple").unlink()
                    elif failure == "extra-marker":
                        (fixture.inputs["apple"] / "provenance" / "duplicate.json").write_text(
                            "{}\n", encoding="utf-8"
                        )
                    else:
                        (fixture.inputs["apple"] / "unexpected.txt").write_text(
                            "unexpected\n", encoding="utf-8"
                        )

                self.assert_fixture_rejected(
                    mutate,
                    "root must contain only|provenance directory must contain exactly",
                )

    def test_rejects_mismatched_provenance_owner_and_commit(self) -> None:
        for condition in ("owner", "commit", "uppercase", "extra-source"):
            with self.subTest(condition=condition):
                def mutate(fixture: MergerFixture, failure: str = condition) -> None:
                    path = fixture.provenance_path("web")

                    def change(value: dict[str, Any]) -> None:
                        if failure == "owner":
                            value["owner"] = "apple"
                        elif failure == "commit":
                            value["sources"]["compose"] = "4" * 40
                        elif failure == "uppercase":
                            value["sources"]["compose"] = "A" * 40
                        else:
                            value["sources"]["unexpected"] = "4" * 40

                    rewrite_json(path, change)

                self.assert_fixture_rejected(
                    mutate,
                    "declares owner|does not match exact source requirements|full lowercase",
                )

    def test_rejects_non_exact_provenance_marker_schema(self) -> None:
        for condition in ("schema", "missing-field", "extra-field", "duplicate-key"):
            with self.subTest(condition=condition):
                def mutate(fixture: MergerFixture, failure: str = condition) -> None:
                    path = fixture.provenance_path("windows")
                    if failure == "duplicate-key":
                        path.write_text(
                            '{"schemaVersion":1,"owner":"windows","owner":"web",'
                            '"sources":{"compose":"' + "1" * 40 + '","skia":"'
                            + "3" * 40 + '","skiko":"' + "2" * 40 + '"}}\n',
                            encoding="utf-8",
                        )
                        return

                    def change(value: dict[str, Any]) -> None:
                        if failure == "schema":
                            value["schemaVersion"] = True
                        elif failure == "missing-field":
                            del value["owner"]
                        else:
                            value["unexpected"] = True

                    rewrite_json(path, change)

                self.assert_fixture_rejected(
                    mutate,
                    "unsupported schemaVersion|marker fields differ|repeats key",
                )

    def test_rejects_invalid_provenance_requirements(self) -> None:
        for condition in ("schema", "missing-owner", "extra-owner", "empty", "short-sha"):
            with self.subTest(condition=condition):
                def mutate(fixture: MergerFixture, failure: str = condition) -> None:
                    requirements = read_json(fixture.requirements)
                    if failure == "schema":
                        requirements["schemaVersion"] = 1
                    elif failure == "missing-owner":
                        del requirements["sourceProvenance"]["apple"]
                    elif failure == "extra-owner":
                        requirements["sourceProvenance"]["other"] = {"compose": "4" * 40}
                    elif failure == "empty":
                        requirements["sourceProvenance"]["apple"] = {}
                    else:
                        requirements["sourceProvenance"]["apple"]["compose"] = "abc"
                    write_json(fixture.requirements, requirements)

                self.assert_fixture_rejected(
                    mutate,
                    "unsupported schemaVersion|sourceProvenance owners differ|non-empty object|"
                    "full lowercase",
                )

    def test_rejects_symlinked_provenance_marker(self) -> None:
        def mutate(fixture: MergerFixture) -> None:
            marker = fixture.provenance_path("apple")
            target = fixture.root / "outside-apple-provenance.json"
            marker.replace(target)
            try:
                marker.symlink_to(target)
            except OSError as error:
                self.skipTest(f"file symlinks are unavailable on this Windows host: {error}")

        self.assert_fixture_rejected(mutate, "provenance marker is not a regular file")

    def test_install_unions_metadata_and_preserves_unrelated_content(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = MergerFixture(Path(temporary))
            fixture.create()
            fixture.destination.mkdir(parents=True)
            unrelated = fixture.destination / "unrelated.txt"
            unrelated.write_text("keep", encoding="utf-8")
            report = MERGER.run(fixture.args(dry_run=False))

            installed_manifest = {}
            for relative_directory in report.version_directories:
                directory = fixture.destination / relative_directory
                for path in directory.rglob("*"):
                    if path.is_file():
                        relative = path.relative_to(fixture.destination).as_posix()
                        payload = path.read_bytes()
                        installed_manifest[relative] = {
                            "size": len(payload),
                            "sha256": digest(payload, "sha256"),
                        }
            self.assertEqual(report.file_manifest, dict(sorted(installed_manifest.items())))

            root = version_dir(fixture.destination, MODULE)
            root_module = root / f"{MODULE}-{VERSION}.module"
            metadata = json.loads(root_module.read_text(encoding="utf-8"))
            self.assertEqual(
                [variant["name"] for variant in metadata["variants"]],
                [
                    "metadataApiElements",
                    "desktopApiElements-published",
                    "mingwX64ApiElements-published",
                    "macosArm64ApiElements-published",
                    "jsApiElements-published",
                ],
            )
            tooling = json.loads(
                (root / f"{MODULE}-{VERSION}-kotlin-tooling-metadata.json").read_text(
                    encoding="utf-8"
                )
            )
            self.assertEqual(len(tooling["projectTargets"]), 5)
            self.assertFalse((root / f"{root_module.name}.asc").exists())
            self.assertFalse((root / f"{root_module.name}.asc.sha256").exists())
            sidecar = root / f"{root_module.name}.sha256"
            self.assertEqual(
                sidecar.read_text(encoding="ascii").strip(),
                digest(root_module.read_bytes(), "sha256"),
            )
            self.assertEqual(unrelated.read_text(encoding="utf-8"), "keep")
            tooling_path = root / f"{MODULE}-{VERSION}-kotlin-tooling-metadata.json"
            self.assertFalse(tooling_path.with_name(f"{tooling_path.name}.asc").exists())
            self.assertFalse(tooling_path.with_name(f"{tooling_path.name}.asc.sha256").exists())
            self.assertEqual(
                tooling_path.with_name(f"{tooling_path.name}.sha256")
                .read_text(encoding="ascii")
                .strip(),
                digest(tooling_path.read_bytes(), "sha256"),
            )
            self.assertTrue(report.dropped_signatures)
            self.assertTrue(report.regenerated_checksums)
            target_payload = self._single_payload(root=version_dir(fixture.destination, "demo-js"))
            self.assertTrue(target_payload.with_name(f"{target_payload.name}.asc").is_file())
            self.assertEqual(
                target_payload.with_name(f"{target_payload.name}.sha256")
                .read_text(encoding="ascii")
                .strip(),
                digest(target_payload.read_bytes(), "sha256"),
            )

    @staticmethod
    def _single_payload(root: Path) -> Path:
        payloads = list(root.glob("*.jar")) + list(root.glob("*.klib"))
        if len(payloads) != 1:
            raise AssertionError(f"expected one payload in {root}, found {payloads}")
        return payloads[0]

    def test_rejects_non_windows_mingw_without_touching_destination(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = MergerFixture(Path(temporary))
            fixture.create()
            forbidden = fixture.inputs["apple"].joinpath(
                *GROUP.split("."), "demo-mingwx64", VERSION, "forbidden.txt"
            )
            forbidden.parent.mkdir(parents=True)
            forbidden.write_text("bad", encoding="utf-8")
            with self.assertRaisesRegex(MERGER.MergeError, "forbidden mingw"):
                MERGER.run(fixture.args(dry_run=False))
            self.assertFalse(fixture.destination.exists())

    def test_rejects_corrupt_payload_without_touching_destination(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = MergerFixture(Path(temporary))
            fixture.create()
            payload = next(version_dir(fixture.inputs["web"], "demo-js").glob("*.jar"))
            payload.write_bytes(b"corrupt")
            with self.assertRaisesRegex(
                MERGER.MergeError, "size mismatch|invalid sha|invalid input checksum"
            ):
                MERGER.run(fixture.args(dry_run=False))
            self.assertFalse(fixture.destination.exists())

    def test_rejects_invalid_input_checksum_instead_of_repairing_it(self) -> None:
        def mutate(fixture: MergerFixture) -> None:
            payload = fixture.payload_path("web", "demo-js")
            payload.with_name(f"{payload.name}.sha256").write_text("0" * 64, encoding="ascii")

        self.assert_fixture_rejected(mutate, "invalid input checksum")

    def test_rejects_component_coordinate_url_mismatch(self) -> None:
        def mutate(fixture: MergerFixture) -> None:
            rewrite_json(
                fixture.module_path("web", "demo-js"),
                lambda value: value["component"].__setitem__("module", "wrong-module"),
            )

        self.assert_fixture_rejected(mutate, "component.url resolves .* expected .*wrong-module")

    def test_rejects_module_storage_filename_mismatch(self) -> None:
        def mutate(fixture: MergerFixture) -> None:
            path = fixture.module_path("web", "demo-js")
            path.rename(path.with_name("wrong-name.module"))

        self.assert_fixture_rejected(mutate, "module metadata filename mismatch")

    def test_rejects_dangling_kmp_component_url(self) -> None:
        def mutate(fixture: MergerFixture) -> None:
            def redirect(value: dict[str, Any]) -> None:
                value["component"]["module"] = "missing-root"
                value["component"]["url"] = (
                    f"../../missing-root/{VERSION}/missing-root-{VERSION}.module"
                )

            rewrite_json(fixture.module_path("web", "demo-js"), redirect)

        self.assert_fixture_rejected(mutate, "dangling component.url")

    def test_rejects_target_linked_to_a_different_kmp_root(self) -> None:
        def mutate(fixture: MergerFixture) -> None:
            other = "other-root"
            payload_name = f"{other}-{VERSION}.jar"
            payload = b"other-root"
            write_module(
                fixture.inputs["web"],
                other,
                [
                    {
                        "name": "jsApiElements",
                        "attributes": attributes("js"),
                        "files": [payload_entry(payload_name, payload)],
                    }
                ],
                {payload_name: payload},
            )
            write_pom(fixture.inputs["web"], other)

            def redirect(value: dict[str, Any]) -> None:
                value["component"]["module"] = other
                value["component"]["url"] = (
                    f"../../{other}/{VERSION}/{other}-{VERSION}.module"
                )

            rewrite_json(fixture.module_path("web", "demo-js"), redirect)

        self.assert_fixture_rejected(mutate, r"target .*demo-js.* for .*demo.* links to .*other-root")

    def test_rejects_whitespace_in_maven_coordinates(self) -> None:
        def mutate(fixture: MergerFixture) -> None:
            requirements = read_json(fixture.requirements)
            requirements["modules"][0]["coordinate"] = f"{GROUP}:bad module:{VERSION}"
            write_json(fixture.requirements, requirements)

        self.assert_fixture_rejected(mutate, "unsafe Maven module")

    def test_rejects_snapshot_and_placeholder_requirement_versions(self) -> None:
        for version in ("1.0.0-SNAPSHOT", "9999.0.0"):
            with self.subTest(version=version):
                def mutate(fixture: MergerFixture, rejected_version: str = version) -> None:
                    requirements = read_json(fixture.requirements)
                    requirements["modules"][0]["coordinate"] = f"{GROUP}:{MODULE}:{rejected_version}"
                    write_json(fixture.requirements, requirements)

                self.assert_fixture_rejected(mutate, "forbidden placeholder/snapshot version")

    def test_rejects_dynamic_fork_dependency_version(self) -> None:
        def mutate(fixture: MergerFixture) -> None:
            def add_dependency(value: dict[str, Any]) -> None:
                value["variants"][0]["dependencies"] = [
                    {"group": GROUP, "module": "helper-js", "version": {"requires": "1.+"}}
                ]

            rewrite_json(fixture.module_path("web", "demo-js"), add_dependency)

        self.assert_fixture_rejected(mutate, "non-exact version")

    def test_rejects_conflicting_fork_dependency_constraints(self) -> None:
        def mutate(fixture: MergerFixture) -> None:
            def add_constraint(value: dict[str, Any]) -> None:
                value["variants"][0]["dependencyConstraints"] = [
                    {
                        "group": GROUP,
                        "module": "helper-js",
                        "version": {"strictly": VERSION, "requires": "2.0.0"},
                    }
                ]

            rewrite_json(fixture.module_path("web", "demo-js"), add_constraint)

        self.assert_fixture_rejected(mutate, "conflicting exact versions")

    def test_rejects_duplicate_gradle_variant(self) -> None:
        def mutate(fixture: MergerFixture) -> None:
            def duplicate(value: dict[str, Any]) -> None:
                value["variants"].append(dict(value["variants"][0]))

            rewrite_json(fixture.module_path("web"), duplicate)

        self.assert_fixture_rejected(mutate, "duplicate variant")

    def test_rejects_invalid_common_requirement_schema(self) -> None:
        for condition in ("missing", "target", "duplicate"):
            with self.subTest(condition=condition):
                def mutate(fixture: MergerFixture, failure: str = condition) -> None:
                    requirements = read_json(fixture.requirements)
                    module = requirements["modules"][0]
                    if failure == "missing":
                        del module["requiredVariants"]["common"]
                    elif failure == "target":
                        module["targetModules"]["common"] = "demo-common"
                    else:
                        module["requiredVariants"]["common"].append("metadataApiElements")
                    write_json(fixture.requirements, requirements)

                self.assert_fixture_rejected(
                    mutate,
                    "no required common variants|must not declare a common target|repeats variants",
                )

    def test_rejects_required_variant_with_wrong_platform(self) -> None:
        def mutate(fixture: MergerFixture) -> None:
            def change_platform(value: dict[str, Any]) -> None:
                value["variants"][0]["attributes"]["org.jetbrains.kotlin.platform.type"] = "jvm"

            rewrite_json(fixture.module_path("web"), change_platform)

        self.assert_fixture_rejected(mutate, "is 'jvm', expected 'js'|non-owned jvm variant")

    def test_rejects_non_owned_variant_without_identical_authoritative_copy(self) -> None:
        def mutate(fixture: MergerFixture) -> None:
            root = fixture.module_path("apple")
            value = read_json(root)
            value["variants"].append(available_variant("jsApiElements-published", "js", "demo-js"))
            value["variants"][-1]["available-at"]["url"] = "../../different-js/1.0.0-mingw/x.module"
            write_json(root, value)

        self.assert_fixture_rejected(mutate, "non-owned js variant")

    def test_rejects_dangling_available_at(self) -> None:
        def mutate(fixture: MergerFixture) -> None:
            def change_url(value: dict[str, Any]) -> None:
                value["variants"][0]["available-at"]["url"] = (
                    f"../../missing-js/{VERSION}/missing-js-{VERSION}.module"
                )

            rewrite_json(fixture.module_path("web"), change_url)

        self.assert_fixture_rejected(mutate, "dangling available-at")

    def test_rejects_escaping_available_at(self) -> None:
        def mutate(fixture: MergerFixture) -> None:
            def change_url(value: dict[str, Any]) -> None:
                value["variants"][0]["available-at"]["url"] = (
                    "../../../../../../../../../../outside.module"
                )

            rewrite_json(fixture.module_path("web"), change_url)

        self.assert_fixture_rejected(mutate, "URL escapes staged repository")

    def test_rejects_payload_url_outside_its_version_directory(self) -> None:
        def mutate(fixture: MergerFixture) -> None:
            def change_url(value: dict[str, Any]) -> None:
                value["variants"][0]["files"][0]["url"] = (
                    f"../../{MODULE}/{VERSION}/{MODULE}-{VERSION}.jar"
                )

            rewrite_json(fixture.module_path("web", "demo-js"), change_url)

        self.assert_fixture_rejected(mutate, "payload URL leaves its Maven version directory")

    def test_rejects_missing_and_empty_payloads(self) -> None:
        for condition in ("missing", "empty"):
            with self.subTest(condition=condition):
                def mutate(fixture: MergerFixture, failure: str = condition) -> None:
                    payload = fixture.payload_path("web", "demo-js")
                    if failure == "missing":
                        payload.unlink()
                    else:
                        payload.write_bytes(b"")

                self.assert_fixture_rejected(
                    mutate,
                    "orphan checksum sidecar|orphan signature|empty file|missing payload",
                )

    def test_rejects_declared_payload_size_and_hash_mismatches(self) -> None:
        for field in ("size", "sha512"):
            with self.subTest(field=field):
                def mutate(fixture: MergerFixture, changed_field: str = field) -> None:
                    def corrupt(value: dict[str, Any]) -> None:
                        entry = value["variants"][0]["files"][0]
                        entry[changed_field] = 1 if changed_field == "size" else "0" * 128

                    rewrite_json(fixture.module_path("web", "demo-js"), corrupt)

                self.assert_fixture_rejected(mutate, "size mismatch|invalid sha512")

    def test_rejects_unowned_and_unequal_root_collisions(self) -> None:
        for condition in ("unowned", "unequal"):
            with self.subTest(condition=condition):
                def mutate(fixture: MergerFixture, failure: str = condition) -> None:
                    apple_root = version_dir(fixture.inputs["apple"], MODULE)
                    if failure == "unowned":
                        (apple_root / "apple-only.txt").write_text("bad", encoding="utf-8")
                    else:
                        pom = apple_root / f"{MODULE}-{VERSION}.pom"
                        pom.write_text(pom.read_text(encoding="utf-8") + "<!-- different -->\n")

                self.assert_fixture_rejected(mutate, "unowned root file|unequal root collision")

    def test_accepts_semantically_identical_root_jars(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = MergerFixture(Path(temporary))
            fixture.create()
            for index, owner in enumerate(("windows", "apple", "web")):
                path = version_dir(fixture.inputs[owner], MODULE) / "shared-sources.jar"
                with zipfile.ZipFile(path, "w") as archive:
                    info = zipfile.ZipInfo("source.kt", date_time=(2020 + index, 1, 1, 0, 0, 0))
                    archive.writestr(info, b"same source")
                module_path = fixture.module_path(owner)
                module = read_json(module_path)
                common_variant = {
                    "name": "metadataApiElements",
                    "attributes": attributes("common"),
                    "files": [payload_entry(path.name, path.read_bytes())],
                }
                if owner == "windows":
                    module["variants"][0] = common_variant
                else:
                    module["variants"].append(common_variant)
                write_json(module_path, module)
                module_sidecar = module_path.with_name(f"{module_path.name}.sha256")
                if module_sidecar.is_file():
                    write_checksum_sidecar(module_path, "sha256")
            MERGER.run(fixture.args(dry_run=True))

    def test_accepts_semantically_identical_non_owner_leaf_copy(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = MergerFixture(Path(temporary))
            fixture.create()
            module = "demo-desktop"
            authoritative_dir = version_dir(fixture.inputs["windows"], module)
            duplicate_dir = version_dir(fixture.inputs["web"], module)
            shutil.copytree(authoritative_dir, duplicate_dir)

            for index, directory in enumerate((authoritative_dir, duplicate_dir)):
                payload = directory / f"{module}-{VERSION}.jar"
                with zipfile.ZipFile(payload, "w") as archive:
                    info = zipfile.ZipInfo(
                        "commonMain/source.kt",
                        date_time=(2020 + index, 1, 1, 0, 0, 0),
                    )
                    archive.writestr(info, b"same compiled payload")
                module_path = directory / f"{module}-{VERSION}.module"

                def update_payload(value: dict[str, Any], data: bytes = payload.read_bytes()) -> None:
                    value["variants"][0]["files"] = [payload_entry(payload.name, data)]

                rewrite_json(module_path, update_payload)
                write_checksum_sidecar(payload, "sha256")

            report = MERGER.run(fixture.args(dry_run=True))
            self.assertEqual(
                report.equivalent_leaf_copies,
                [
                    {
                        "coordinate": f"{GROUP}:{module}:{VERSION}",
                        "owner": "windows",
                        "equivalent": "web",
                    }
                ],
            )

    def test_rejects_unequal_or_incomplete_non_owner_leaf_copy(self) -> None:
        for condition in ("variant", "payload", "extra", "missing"):
            with self.subTest(condition=condition):
                def mutate(fixture: MergerFixture, failure: str = condition) -> None:
                    module = "demo-desktop"
                    authoritative_dir = version_dir(fixture.inputs["windows"], module)
                    duplicate_dir = version_dir(fixture.inputs["web"], module)
                    shutil.copytree(authoritative_dir, duplicate_dir)
                    if failure == "variant":
                        rewrite_json(
                            duplicate_dir / f"{module}-{VERSION}.module",
                            lambda value: value["variants"][0].__setitem__("unexpected", True),
                        )
                    elif failure == "payload":
                        pom = duplicate_dir / f"{module}-{VERSION}.pom"
                        pom.write_text(pom.read_text(encoding="utf-8") + "<!-- different -->\n")
                    elif failure == "extra":
                        (duplicate_dir / "unexpected.txt").write_text("bad", encoding="utf-8")
                    else:
                        (duplicate_dir / f"{module}-{VERSION}.pom").unlink()

                self.assert_fixture_rejected(
                    mutate,
                    "unequal leaf variant|unequal leaf collision|unequal leaf payload set",
                )

    def test_rejects_tooling_scalar_conflict_and_missing_owner_copy(self) -> None:
        for condition in ("scalar", "missing"):
            with self.subTest(condition=condition):
                def mutate(fixture: MergerFixture, failure: str = condition) -> None:
                    path = fixture.tooling_path("apple")
                    if failure == "scalar":
                        rewrite_json(
                            path,
                            lambda value: value.__setitem__("buildSystemVersion", "0.0"),
                        )
                    else:
                        path.unlink()

                self.assert_fixture_rejected(
                    mutate,
                    "tooling metadata scalars/settings differ|missing Kotlin tooling metadata",
                )

    def test_rejects_conflicting_and_duplicate_tooling_targets(self) -> None:
        for condition in ("conflict", "duplicate", "same-platform"):
            with self.subTest(condition=condition):
                def mutate(fixture: MergerFixture, failure: str = condition) -> None:
                    path = fixture.tooling_path("apple")
                    value = read_json(path)
                    if failure == "same-platform":
                        path = fixture.tooling_path("web")
                        value = read_json(path)
                        target = tooling_target("js")
                        target["target"] = "example.SecondKotlinJsTarget"
                    else:
                        target = tooling_target(
                            "macosArm64" if failure == "duplicate" else "common"
                        )
                    if failure == "conflict":
                        target["unexpected"] = True
                    value["projectTargets"].append(target)
                    write_json(path, value)

                self.assert_fixture_rejected(
                    mutate,
                    "conflicting Kotlin tooling target|repeats Kotlin tooling target|repeats platform",
                )

    def test_rejects_pom_coordinate_and_dependency_mismatches(self) -> None:
        for condition in ("coordinate", "dangling", "snapshot"):
            with self.subTest(condition=condition):
                def mutate(fixture: MergerFixture, failure: str = condition) -> None:
                    if failure == "coordinate":
                        write_pom(fixture.inputs["web"], "demo-js", group="wrong.group")
                    elif failure == "dangling":
                        write_pom(
                            fixture.inputs["web"],
                            "demo-js",
                            dependencies=[(GROUP, "missing-helper", VERSION)],
                        )
                    else:
                        write_pom(
                            fixture.inputs["web"],
                            "demo-js",
                            dependencies=[(GROUP, "helper", "1.0-SNAPSHOT")],
                        )

                self.assert_fixture_rejected(
                    mutate,
                    "POM coordinate mismatch|dangling fork dependency|forbidden placeholder/snapshot",
                )

    def test_pom_dependency_management_does_not_expand_the_artifact_closure(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = MergerFixture(Path(temporary))
            fixture.create()
            managed_only = "optional-helper"
            write_pom(
                fixture.inputs["web"],
                "demo-js",
                dependency_management=[(GROUP, managed_only, VERSION)],
            )

            report = MERGER.run(fixture.args(dry_run=True))

            self.assertNotIn(
                str(MERGER.Coordinate(GROUP, managed_only, VERSION).relative_version_dir()),
                report.version_directories,
            )

    def test_stages_owner_pinned_pom_only_module_with_exact_range_dependency(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = MergerFixture(Path(temporary))
            fixture.create()
            selector = "demo-jvm-windows-x64"
            pom = write_pom(
                fixture.inputs["windows"],
                selector,
                dependencies=[(GROUP, "demo-desktop", f"[{VERSION}]")],
            )
            write_checksum_sidecar(pom, "sha256")
            requirements = read_json(fixture.requirements)
            requirements["pomOnlyModules"].append(
                {
                    "coordinate": f"{GROUP}:{selector}:{VERSION}",
                    "owner": "windows",
                }
            )
            write_json(fixture.requirements, requirements)

            report = MERGER.run(fixture.args(dry_run=False))

            coordinate = MERGER.Coordinate(GROUP, selector, VERSION)
            self.assertIn(str(coordinate.relative_version_dir()), report.version_directories)
            self.assertIn(
                {
                    "coordinate": coordinate.display(),
                    "required": True,
                    "pomOnly": True,
                    "owner": "windows",
                },
                report.modules,
            )
            installed_pom = coordinate.version_dir(fixture.destination) / pom.name
            self.assertEqual(installed_pom.read_bytes(), pom.read_bytes())
            self.assertEqual(
                installed_pom.with_name(f"{installed_pom.name}.sha256").read_text(
                    encoding="ascii"
                ).strip(),
                digest(installed_pom.read_bytes(), "sha256"),
            )

    def test_merges_host_owned_jvm_runtime_selector_and_pom_only_leaves(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = MergerFixture(Path(temporary))
            fixture.create()
            selector = "runtime-selector"
            leaves = {
                "windows": [("windows", "x86-64", "jvmWindowsX64")],
                "apple": [
                    ("macos", "x86-64", "jvmMacosX64"),
                    ("macos", "aarch64", "jvmMacosArm64"),
                ],
                "web": [("linux", "x86-64", "jvmLinuxX64")],
            }
            requirements = read_json(fixture.requirements)
            requirements["platformOwners"].update(
                {
                    "jvmWindowsX64": "windows",
                    "jvmMacosX64": "apple",
                    "jvmMacosArm64": "apple",
                    "jvmLinuxX64": "web",
                }
            )

            for owner, targets in leaves.items():
                variants = []
                for operating_system, architecture, platform in targets:
                    leaf = f"runtime-{operating_system}-{architecture}"
                    variants.append(
                        {
                            "name": f"runtime-{operating_system}-{architecture}",
                            "attributes": {
                                "org.gradle.category": "library",
                                "org.gradle.usage": "java-runtime",
                                "org.jetbrains.kotlin.platform.type": "jvm",
                                "org.gradle.native.operatingSystem": operating_system,
                                "org.gradle.native.architecture": architecture,
                            },
                            "dependencies": [
                                {
                                    "group": GROUP,
                                    "module": leaf,
                                    "version": {"requires": VERSION},
                                }
                            ],
                        }
                    )
                    write_pom(fixture.inputs[owner], leaf)
                    requirements["pomOnlyModules"].append(
                        {
                            "coordinate": f"{GROUP}:{leaf}:{VERSION}",
                            "owner": owner,
                        }
                    )
                    self.assertEqual(
                        platform,
                        MERGER.variant_platform(variants[-1]),
                    )
                write_module(fixture.inputs[owner], selector, variants)
                write_pom(fixture.inputs[owner], selector)

            desktop = fixture.module_path("windows", "demo-desktop")
            rewrite_json(
                desktop,
                lambda module: module["variants"][0].setdefault("dependencies", []).append(
                    {
                        "group": GROUP,
                        "module": selector,
                        "version": {"requires": VERSION},
                    }
                ),
            )
            write_pom(
                fixture.inputs["windows"],
                "demo-desktop",
                dependencies=[(GROUP, selector, VERSION)],
            )
            write_json(fixture.requirements, requirements)

            report = MERGER.run(fixture.args(dry_run=False))

            selector_report = next(
                module
                for module in report.modules
                if module["coordinate"] == f"{GROUP}:{selector}:{VERSION}"
            )
            self.assertEqual(
                {
                    "jvmWindowsX64",
                    "jvmMacosX64",
                    "jvmMacosArm64",
                    "jvmLinuxX64",
                },
                set(selector_report["variantPlatforms"].values()),
            )
            merged_selector = read_json(
                version_dir(fixture.destination, selector) / f"{selector}-{VERSION}.module"
            )
            self.assertEqual(
                set(selector_report["variants"]),
                {variant["name"] for variant in merged_selector["variants"]},
            )
            self.assertTrue(
                all(
                    str(MERGER.Coordinate(GROUP, leaf, VERSION).relative_version_dir())
                    in report.version_directories
                    for targets in leaves.values()
                    for operating_system, architecture, _ in targets
                    for leaf in [f"runtime-{operating_system}-{architecture}"]
                )
            )

    def test_rejects_invalid_pom_only_requirement_schema(self) -> None:
        for condition in ("missing-array", "wrong-owner", "extra-field", "overlap", "duplicate"):
            with self.subTest(condition=condition):
                def mutate(fixture: MergerFixture, failure: str = condition) -> None:
                    requirements = read_json(fixture.requirements)
                    entry = {
                        "coordinate": f"{GROUP}:selector:{VERSION}",
                        "owner": "windows",
                    }
                    if failure == "missing-array":
                        del requirements["pomOnlyModules"]
                    elif failure == "wrong-owner":
                        entry["owner"] = "other"
                        requirements["pomOnlyModules"].append(entry)
                    elif failure == "extra-field":
                        entry["platform"] = "jvm"
                        requirements["pomOnlyModules"].append(entry)
                    elif failure == "overlap":
                        requirements["pomOnlyModules"].append(
                            {
                                "coordinate": f"{GROUP}:{MODULE}:{VERSION}",
                                "owner": "windows",
                            }
                        )
                    else:
                        requirements["pomOnlyModules"].extend([entry, dict(entry)])
                    write_json(fixture.requirements, requirements)

                self.assert_fixture_rejected(
                    mutate,
                    "no pomOnlyModules array|invalid owner|expected exactly coordinate and owner|"
                    "both a Gradle-metadata module and POM-only|duplicate POM-only requirement",
                )

    def test_rejects_missing_misowned_or_metadata_backed_pom_only_module(self) -> None:
        for condition in (
            "missing",
            "missing-pom",
            "wrong-owner",
            "duplicate-owner",
            "metadata",
            "extra-pom",
        ):
            with self.subTest(condition=condition):
                def mutate(fixture: MergerFixture, failure: str = condition) -> None:
                    selector = "selector"
                    owner = "apple" if failure == "wrong-owner" else "windows"
                    if failure == "missing-pom":
                        directory = version_dir(fixture.inputs["windows"], selector)
                        directory.mkdir(parents=True)
                        (directory / f"{selector}-{VERSION}.jar").write_bytes(b"payload")
                    elif failure != "missing":
                        write_pom(fixture.inputs["windows"], selector)
                    if failure == "duplicate-owner":
                        write_pom(fixture.inputs["apple"], selector)
                    elif failure == "metadata":
                        write_module(
                            fixture.inputs["windows"],
                            selector,
                            [{"name": "metadataApiElements", "attributes": attributes("common")}],
                        )
                    elif failure == "extra-pom":
                        directory = version_dir(fixture.inputs["windows"], selector)
                        (directory / "unexpected.pom").write_text("<project/>", encoding="utf-8")
                    requirements = read_json(fixture.requirements)
                    requirements["pomOnlyModules"].append(
                        {
                            "coordinate": f"{GROUP}:{selector}:{VERSION}",
                            "owner": owner,
                        }
                    )
                    write_json(fixture.requirements, requirements)

                self.assert_fixture_rejected(
                    mutate,
                    "missing POM-only module|is missing .*\\.pom|"
                    "pinned to .* but .* also contains|"
                    "unexpectedly contains Gradle metadata|contains unexpected POM files",
                )

    def test_rejects_dangling_dependency_from_pom_only_module(self) -> None:
        def mutate(fixture: MergerFixture) -> None:
            selector = "selector"
            write_pom(
                fixture.inputs["windows"],
                selector,
                dependencies=[(GROUP, "missing", VERSION)],
            )
            requirements = read_json(fixture.requirements)
            requirements["pomOnlyModules"].append(
                {
                    "coordinate": f"{GROUP}:{selector}:{VERSION}",
                    "owner": "windows",
                }
            )
            write_json(fixture.requirements, requirements)

        self.assert_fixture_rejected(mutate, "POM .* has dangling fork dependency")

    def test_only_singleton_maven_ranges_are_exact_pom_versions(self) -> None:
        self.assertEqual(
            MERGER.exact_pom_dependency_version(f"[{VERSION}]", "dependency"),
            VERSION,
        )
        for version in (
            f"[{VERSION},)",
            f"(,{VERSION}]",
            f"[{VERSION},{VERSION}]",
            f"({VERSION})",
            "[1.0-SNAPSHOT]",
        ):
            with self.subTest(version=version), self.assertRaisesRegex(
                MERGER.MergeError,
                "non-exact version|forbidden placeholder/snapshot",
            ):
                MERGER.exact_pom_dependency_version(version, "dependency")

    def test_assembles_exact_fork_dependency_closure(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = MergerFixture(Path(temporary))
            fixture.create()
            helper = "helper"
            helper_target = "helper-js"
            helper_payload_name = f"{helper}-{VERSION}.jar"
            helper_payload = b"helper-common-metadata"
            helper_variants = {
                "windows": [
                    {
                        "name": "metadataApiElements",
                        "attributes": attributes("common"),
                        "files": [payload_entry(helper_payload_name, helper_payload)],
                    }
                ],
                "web": [available_variant("jsApiElements-published", "js", helper_target)],
            }
            for owner, variants in helper_variants.items():
                payloads = (
                    {helper_payload_name: helper_payload} if owner == "windows" else None
                )
                helper_root = write_module(
                    fixture.inputs[owner],
                    helper,
                    variants,
                    payloads,
                )
                write_pom(fixture.inputs[owner], helper)
                platforms = ["common"] if owner == "windows" else ["js"]
                write_json(
                    helper_root.parent
                    / f"{helper}-{VERSION}-kotlin-tooling-metadata.json",
                    {
                        "schemaVersion": "1.1.0",
                        "buildSystem": "Gradle",
                        "buildSystemVersion": "9.6.1",
                        "buildPlugin": "org.jetbrains.kotlin.multiplatform",
                        "buildPluginVersion": "2.4.10",
                        "projectSettings": {"isHmppEnabled": True},
                        "projectTargets": [tooling_target(platform) for platform in platforms],
                    },
                )
            write_target(
                fixture.inputs["web"],
                helper_target,
                "jsApiElements-published",
                "js",
                root_module=helper,
            )

            def add_dependency(value: dict[str, Any]) -> None:
                value["variants"][0]["dependencies"] = [
                    {"group": GROUP, "module": helper, "version": {"requires": VERSION}}
                ]

            rewrite_json(fixture.module_path("web", "demo-js"), add_dependency)
            write_pom(
                fixture.inputs["web"],
                "demo-js",
                dependencies=[(GROUP, helper, VERSION)],
            )
            report = MERGER.run(fixture.args(dry_run=True))
            self.assertIn(
                str(MERGER.Coordinate(GROUP, helper, VERSION).relative_version_dir()),
                report.version_directories,
            )
            self.assertIn(
                str(MERGER.Coordinate(GROUP, helper_target, VERSION).relative_version_dir()),
                report.version_directories,
            )

    def test_rejects_transitive_root_without_the_consuming_platform(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = MergerFixture(Path(temporary))
            fixture.create()

            helper = "helper"
            helper_payload_name = f"{helper}-{VERSION}.jar"
            helper_payload = b"helper-common-metadata"
            helper_root = write_module(
                fixture.inputs["windows"],
                helper,
                [
                    {
                        "name": "metadataApiElements",
                        "attributes": attributes("common"),
                        "files": [payload_entry(helper_payload_name, helper_payload)],
                    },
                    available_variant(
                        "desktopApiElements-published", "jvm", "helper-desktop"
                    ),
                ],
                {helper_payload_name: helper_payload},
            )
            write_pom(fixture.inputs["windows"], helper)
            write_json(
                helper_root.parent
                / f"{helper}-{VERSION}-kotlin-tooling-metadata.json",
                {
                    "schemaVersion": "1.1.0",
                    "buildSystem": "Gradle",
                    "buildSystemVersion": "9.6.1",
                    "buildPlugin": "org.jetbrains.kotlin.multiplatform",
                    "buildPluginVersion": "2.4.10",
                    "projectSettings": {"isHmppEnabled": True},
                    "projectTargets": [
                        tooling_target("common"),
                        tooling_target("jvm"),
                    ],
                },
            )
            write_target(
                fixture.inputs["windows"],
                "helper-desktop",
                "desktopApiElements-published",
                "jvm",
                root_module=helper,
            )

            def add_dependency(value: dict[str, Any]) -> None:
                value["variants"][0]["dependencies"] = [
                    {"group": GROUP, "module": helper, "version": {"requires": VERSION}}
                ]

            rewrite_json(fixture.module_path("web", "demo-js"), add_dependency)
            write_pom(
                fixture.inputs["web"],
                "demo-js",
                dependencies=[(GROUP, helper, VERSION)],
            )

            with self.assertRaisesRegex(
                MERGER.MergeError,
                r"dependency requires .*:helper:.* for js, .* provides \['common', 'jvm'\]",
            ):
                MERGER.run(fixture.args(dry_run=True))

    def test_rejects_overlapping_inputs_and_destination(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = MergerFixture(Path(temporary))
            fixture.create()
            args = fixture.args(dry_run=True)
            args[args.index("--apple") + 1] = str(fixture.inputs["windows"])
            with self.assertRaisesRegex(MERGER.MergeError, "input repositories overlap"):
                MERGER.run(args)

            args = fixture.args(dry_run=True)
            args[args.index("--destination") + 1] = str(fixture.inputs["windows"] / "nested")
            with self.assertRaisesRegex(MERGER.MergeError, "destination repository overlaps"):
                MERGER.run(args)

    def test_rejects_report_and_requirements_output_path_collisions(self) -> None:
        cases = ("input", "destination", "requirements")
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                fixture = MergerFixture(Path(temporary))
                fixture.create()
                args = fixture.args(dry_run=False)
                report_index = args.index("--report") + 1
                if case == "input":
                    report_path = fixture.module_path("web", "demo-js")
                    expected = "report path overlaps web input repository"
                elif case == "destination":
                    report_path = fixture.destination / "merge-report.json"
                    expected = "report path overlaps destination repository"
                else:
                    report_path = fixture.requirements
                    expected = "report path would overwrite requirements"
                original = report_path.read_bytes() if report_path.is_file() else None
                args[report_index] = str(report_path)

                with self.assertRaisesRegex(MERGER.MergeError, expected):
                    MERGER.run(args)

                self.assertFalse(fixture.destination.exists())
                if original is not None:
                    self.assertEqual(report_path.read_bytes(), original)

    def test_report_commit_failure_rolls_back_the_repository_install(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = MergerFixture(Path(temporary))
            fixture.create()
            existing = version_dir(fixture.destination, MODULE)
            existing.mkdir(parents=True)
            sentinel = existing / "preexisting.txt"
            sentinel.write_text("keep", encoding="utf-8")
            real_replace = MERGER.os.replace

            def fail_report_commit(source: Any, destination: Any) -> None:
                if Path(destination).resolve() == fixture.report.resolve():
                    raise OSError("simulated report commit failure")
                real_replace(source, destination)

            with mock.patch.object(MERGER.os, "replace", side_effect=fail_report_commit):
                with self.assertRaisesRegex(OSError, "simulated report commit failure"):
                    MERGER.run(fixture.args(dry_run=False))

            self.assertEqual(sentinel.read_text(encoding="utf-8"), "keep")
            self.assertEqual(sorted(path.name for path in existing.iterdir()), ["preexisting.txt"])
            self.assertFalse(fixture.report.exists())
            self.assertEqual(list(fixture.destination.rglob("*.module")), [])

    def test_transaction_rolls_back_every_replaced_version_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stage = root / "transaction" / "stage"
            destination = root / "repository"
            relatives = ["io/example/a/1", "io/example/b/1"]
            for relative in relatives:
                source = stage / relative
                target = destination / relative
                source.mkdir(parents=True)
                target.mkdir(parents=True)
                (source / "value.txt").write_text("new", encoding="utf-8")
                (target / "value.txt").write_text("old", encoding="utf-8")

            real_replace = MERGER.os.replace
            failing_source = stage / relatives[1]

            def replace_with_failure(source: Any, target: Any) -> None:
                if Path(source) == failing_source:
                    raise OSError("injected replacement failure")
                real_replace(source, target)

            with mock.patch.object(MERGER.os, "replace", side_effect=replace_with_failure):
                with self.assertRaisesRegex(OSError, "injected replacement failure"):
                    MERGER.install_transactionally(stage, destination, relatives)
            for relative in relatives:
                self.assertEqual(
                    (destination / relative / "value.txt").read_text(encoding="utf-8"),
                    "old",
                )

    def test_transaction_rejects_escape_before_moving_any_version_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stage = root / "transaction" / "stage"
            destination = root / "repository"
            safe = stage / "io/example/a/1"
            escaping = stage.parent / "escape"
            safe.mkdir(parents=True)
            escaping.mkdir()
            destination.mkdir()
            (safe / "value.txt").write_text("new", encoding="utf-8")
            (escaping / "value.txt").write_text("escape", encoding="utf-8")

            with self.assertRaisesRegex(MERGER.MergeError, "unsafe staged version directory"):
                MERGER.install_transactionally(
                    stage,
                    destination,
                    ["io/example/a/1", "../escape"],
                )
            self.assertTrue((safe / "value.txt").is_file())
            self.assertFalse((destination / "io/example/a/1").exists())

    def test_checked_in_requirements_use_the_intended_mixed_versions_and_platforms(self) -> None:
        path = Path(__file__).with_name("maven-variant-requirements.json")
        manifest = read_json(path)
        loaded = MERGER.load_requirements(path)
        self.assertEqual(manifest["schemaVersion"], 2)
        self.assertEqual(set(loaded.source_provenance), set(MERGER.OWNERS))
        self.assertEqual(
            set(loaded.source_provenance["windows"]), {"compose", "skia", "skiko"}
        )
        self.assertEqual(
            set(loaded.source_provenance["apple"]), {"compose", "skiko"}
        )
        self.assertEqual(set(loaded.source_provenance["web"]), {"compose", "skiko"})
        modules = {
            MERGER.Coordinate.parse(value["coordinate"]): value for value in manifest["modules"]
        }
        self.assertEqual(len(modules), 10)
        self.assertEqual(
            {
                MERGER.Coordinate.parse(value["coordinate"]): value["owner"]
                for value in manifest["pomOnlyModules"]
            },
            {
                MERGER.Coordinate(
                    "io.github.archivesteak.compose.desktop",
                    "desktop-jvm-windows-x64",
                    "1.12.0-beta02-mingw",
                ): "windows",
                MERGER.Coordinate(
                    "io.github.archivesteak.skiko",
                    "skiko-awt-runtime-windows-x64",
                    "0.151.0-alpha04-mingw",
                ): "windows",
                MERGER.Coordinate(
                    "io.github.archivesteak.skiko",
                    "skiko-awt-runtime-macos-x64",
                    "0.151.0-alpha04-mingw",
                ): "apple",
                MERGER.Coordinate(
                    "io.github.archivesteak.skiko",
                    "skiko-awt-runtime-macos-arm64",
                    "0.151.0-alpha04-mingw",
                ): "apple",
                MERGER.Coordinate(
                    "io.github.archivesteak.skiko",
                    "skiko-awt-runtime-linux-x64",
                    "0.151.0-alpha04-mingw",
                ): "web",
            },
        )
        material3 = next(
            coordinate for coordinate in modules if coordinate.module == "material3"
        )
        self.assertEqual(material3.version, "1.12.0-alpha03-mingw")
        compose_versions = {
            coordinate.version
            for coordinate in modules
            if coordinate.group.startswith("io.github.archivesteak.compose")
            and coordinate.module != "material3"
        }
        self.assertEqual(compose_versions, {"1.12.0-beta02-mingw"})
        skiko = next(coordinate for coordinate in modules if coordinate.module == "skiko")
        self.assertEqual(skiko.version, "0.151.0-alpha04-mingw")
        ui_test = next(coordinate for coordinate in modules if coordinate.module == "ui-test")
        navigation_event = next(
            coordinate
            for coordinate in modules
            if coordinate.module == "navigationevent-compose"
        )
        self.assertEqual(ui_test.version, "1.12.0-beta02-mingw")
        self.assertEqual(navigation_event.version, "1.1.1-beta01-mingw")
        desktop = next(coordinate for coordinate in modules if coordinate.module == "desktop")
        ui_uikit = next(coordinate for coordinate in modules if coordinate.module == "ui-uikit")
        self.assertEqual(set(modules[desktop]["requiredVariants"]), {"common", "jvm"})
        self.assertEqual(
            set(modules[ui_uikit]["requiredVariants"]),
            {"common", "iosArm64", "iosSimulatorArm64"},
        )
        expected_compose_platforms = {
            "common",
            "jvm",
            "mingwX64",
            "macosArm64",
            "iosArm64",
            "iosSimulatorArm64",
            "js",
            "wasmJs",
            "android",
        }
        self.assertEqual(
            set(modules[ui_test]["requiredVariants"]), expected_compose_platforms
        )
        self.assertEqual(
            set(modules[navigation_event]["requiredVariants"]),
            expected_compose_platforms,
        )
        self.assertTrue(
            {
                "common",
                "jvm",
                "mingwX64",
                "macosX64",
                "macosArm64",
                "iosArm64",
                "iosSimulatorArm64",
                "js",
                "wasmJs",
                "android",
                "linuxX64",
                "linuxArm64",
            }
            <= set(modules[skiko]["requiredVariants"])
        )
        self.assertIn(
            "skikoWasmRuntimeElementsForJs",
            modules[skiko]["requiredVariants"]["js"],
        )
        self.assertIn(
            "skikoWasmRuntimeElementsForWasmJs",
            modules[skiko]["requiredVariants"]["wasmJs"],
        )


if __name__ == "__main__":
    unittest.main()
