#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("prune-missing-kmp-redirects.py")
SPEC = importlib.util.spec_from_file_location("prune_missing_kmp_redirects", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
PRUNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PRUNER)


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


class PruneMissingKmpRedirectsTest(unittest.TestCase):
    def make_repository(self, root: Path, malformed_child: bool = False) -> Path:
        repository = root / "repository"
        publication = repository / "example" / "root" / "1"
        root_module = publication / "root-1.module"
        dependency = {
            "group": "example.dependency",
            "module": "api",
            "version": {"requires": "7"},
        }
        write_json(
            root_module,
            {
                "variants": [
                    {
                        "name": "metadataApiElements",
                        "attributes": {"org.jetbrains.kotlin.platform.type": "common"},
                        "dependencies": [dependency],
                    },
                    {
                        "name": "mingwX64ApiElements-published",
                        "attributes": {
                            "org.jetbrains.kotlin.platform.type": "native",
                            "org.jetbrains.kotlin.native.target": "mingw_x64",
                        },
                        "available-at": {
                            "url": "../../root-mingwx64/1/root-mingwx64-1.module",
                            "group": "example",
                            "module": "root-mingwx64",
                            "version": "1",
                        },
                    },
                    {
                        "name": "iosArm64ApiElements-published",
                        "attributes": {
                            "org.jetbrains.kotlin.platform.type": "native",
                            "org.jetbrains.kotlin.native.target": "ios_arm64",
                        },
                        "available-at": {
                            "url": "../../root-iosarm64/1/root-iosarm64-1.module",
                            "group": "example",
                            "module": "root-iosarm64",
                            "version": "1",
                        },
                    },
                ]
            },
        )
        child = repository / "example" / "root-mingwx64" / "1" / "root-mingwx64-1.module"
        if malformed_child:
            child.parent.mkdir(parents=True, exist_ok=True)
            child.write_text("not JSON", encoding="utf-8")
        else:
            write_json(child, {"variants": [{"name": "mingwX64ApiElements-published"}]})
        write_json(
            publication / "root-1-kotlin-tooling-metadata.json",
            {
                "projectTargets": [
                    {"platformType": "common"},
                    {
                        "platformType": "native",
                        "extras": {"native": {"konanTarget": "mingw_x64"}},
                    },
                    {
                        "platformType": "native",
                        "extras": {"native": {"konanTarget": "ios_arm64"}},
                    },
                ]
            },
        )
        return repository

    def make_jvm_repository(
        self,
        root: Path,
        *,
        android_child: bool,
        desktop_child: bool,
    ) -> Path:
        repository = root / "repository"
        publication = repository / "example" / "root" / "1"
        variants = [
            {
                "name": "metadataApiElements",
                "attributes": {"org.jetbrains.kotlin.platform.type": "common"},
            },
            {
                "name": "androidApiElements-published",
                "attributes": {
                    "org.gradle.jvm.environment": "android",
                    "org.gradle.libraryelements": "aar",
                    "org.jetbrains.kotlin.platform.type": "jvm",
                },
                "available-at": {
                    "url": "../../root-android/1/root-android-1.module",
                    "group": "example",
                    "module": "root-android",
                    "version": "1",
                },
            },
            {
                "name": "desktopApiElements-published",
                "attributes": {
                    "org.gradle.jvm.environment": "standard-jvm",
                    "org.gradle.libraryelements": "jar",
                    "org.jetbrains.kotlin.platform.type": "jvm",
                },
                "available-at": {
                    "url": "../../root-desktop/1/root-desktop-1.module",
                    "group": "example",
                    "module": "root-desktop",
                    "version": "1",
                },
            },
        ]
        write_json(publication / "root-1.module", {"variants": variants})
        if android_child:
            write_json(
                repository / "example" / "root-android" / "1" / "root-android-1.module",
                {"variants": [{"name": "androidApiElements-published"}]},
            )
        if desktop_child:
            write_json(
                repository
                / "example"
                / "root-desktop"
                / "1"
                / "root-desktop-1.module",
                {"variants": [{"name": "desktopApiElements-published"}]},
            )
        write_json(
            publication / "root-1-kotlin-tooling-metadata.json",
            {
                "projectTargets": [
                    {
                        "target": (
                            "com.android.build.api.variant.impl."
                            "KotlinMultiplatformAndroidLibraryTargetImpl"
                        ),
                        "platformType": "jvm",
                    },
                    {
                        "target": "org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget",
                        "platformType": "jvm",
                        "extras": {
                            "jvm": {"jvmTarget": "11", "withJavaEnabled": False}
                        },
                    },
                    {
                        "target": "org.jetbrains.kotlin.gradle.plugin.mpp.KotlinMetadataTarget",
                        "platformType": "common",
                    },
                ]
            },
        )
        return repository

    def test_prunes_only_absent_redirect_and_matching_tooling_target(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = self.make_repository(Path(directory))
            self.assertEqual((1, 1, 1), PRUNER.normalize(repository))

            publication = repository / "example" / "root" / "1"
            module_path = publication / "root-1.module"
            module = json.loads(module_path.read_text(encoding="utf-8"))
            self.assertEqual(
                ["metadataApiElements", "mingwX64ApiElements-published"],
                [variant["name"] for variant in module["variants"]],
            )
            # Non-redirect metadata and its dependency closure are byte-for-byte semantic input;
            # normalization never edits dependencies or constraints.
            self.assertEqual(
                [{"group": "example.dependency", "module": "api", "version": {"requires": "7"}}],
                module["variants"][0]["dependencies"],
            )
            tooling_path = publication / "root-1-kotlin-tooling-metadata.json"
            tooling = json.loads(tooling_path.read_text(encoding="utf-8"))
            self.assertEqual(
                ["common", "native"],
                [target["platformType"] for target in tooling["projectTargets"]],
            )
            self.assertEqual(
                "mingw_x64",
                tooling["projectTargets"][1]["extras"]["native"]["konanTarget"],
            )
            for path in (module_path, tooling_path):
                self.assertEqual(
                    hashlib.sha256(path.read_bytes()).hexdigest(),
                    path.with_name(path.name + ".sha256").read_text(encoding="ascii").strip(),
                )

    def test_rejects_present_but_malformed_child(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = self.make_repository(Path(directory), malformed_child=True)
            with self.assertRaises(json.JSONDecodeError):
                PRUNER.normalize(repository)

    def test_android_fragment_does_not_claim_missing_desktop_tooling_target(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = self.make_jvm_repository(
                Path(directory), android_child=True, desktop_child=False
            )
            self.assertEqual((1, 1, 1), PRUNER.normalize(repository))

            publication = repository / "example" / "root" / "1"
            module = json.loads((publication / "root-1.module").read_text())
            self.assertEqual(
                ["metadataApiElements", "androidApiElements-published"],
                [variant["name"] for variant in module["variants"]],
            )
            tooling = json.loads(
                (publication / "root-1-kotlin-tooling-metadata.json").read_text()
            )
            self.assertEqual(
                [
                    "com.android.build.api.variant.impl."
                    "KotlinMultiplatformAndroidLibraryTargetImpl",
                    "org.jetbrains.kotlin.gradle.plugin.mpp.KotlinMetadataTarget",
                ],
                [target["target"] for target in tooling["projectTargets"]],
            )

    def test_desktop_fragment_does_not_claim_missing_android_tooling_target(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = self.make_jvm_repository(
                Path(directory), android_child=False, desktop_child=True
            )
            self.assertEqual((1, 1, 1), PRUNER.normalize(repository))

            publication = repository / "example" / "root" / "1"
            module = json.loads((publication / "root-1.module").read_text())
            self.assertEqual(
                ["metadataApiElements", "desktopApiElements-published"],
                [variant["name"] for variant in module["variants"]],
            )
            tooling = json.loads(
                (publication / "root-1-kotlin-tooling-metadata.json").read_text()
            )
            self.assertEqual(
                [
                    "org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget",
                    "org.jetbrains.kotlin.gradle.plugin.mpp.KotlinMetadataTarget",
                ],
                [target["target"] for target in tooling["projectTargets"]],
            )

    def test_rejects_unclassifiable_retained_jvm_tooling_target(self) -> None:
        with self.assertRaisesRegex(ValueError, "cannot distinguish JVM target"):
            PRUNER.tooling_jvm_environment(
                {"target": "example.UnknownJvmTarget", "platformType": "jvm"},
                Path("root-1-kotlin-tooling-metadata.json"),
            )

    def test_closes_host_specific_selector_dependencies(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory) / "repository"
            module_path = repository / "example" / "selector" / "1" / "selector-1.module"
            write_json(
                module_path,
                {
                    "variants": [
                        {
                            "name": "runtime-windows-x64",
                            "dependencies": [
                                {
                                    "group": "example",
                                    "module": "runtime-windows-x64",
                                    "version": {"requires": "1"},
                                }
                            ],
                        },
                        {
                            "name": "runtime-macos-x64",
                            "dependencies": [
                                {
                                    "group": "example",
                                    "module": "runtime-macos-x64",
                                    "version": {"requires": "1"},
                                }
                            ],
                        },
                    ]
                },
            )
            runtime = (
                repository
                / "example"
                / "runtime-windows-x64"
                / "1"
                / "runtime-windows-x64-1.pom"
            )
            runtime.parent.mkdir(parents=True)
            runtime.write_text("<project/>", encoding="utf-8")

            self.assertEqual((1, 1, 0), PRUNER.normalize(repository, "example"))
            module = json.loads(module_path.read_text(encoding="utf-8"))
            self.assertEqual(
                ["runtime-windows-x64"],
                [variant["name"] for variant in module["variants"]],
            )
            self.assertEqual(
                hashlib.sha256(module_path.read_bytes()).hexdigest(),
                module_path.with_name(module_path.name + ".sha256")
                .read_text(encoding="ascii")
                .strip(),
            )


if __name__ == "__main__":
    unittest.main()
