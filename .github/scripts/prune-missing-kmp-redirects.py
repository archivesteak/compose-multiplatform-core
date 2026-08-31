#!/usr/bin/env python3
"""Make a partial Maven/KMP repository internally closed before it is uploaded."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


KOTLIN_PLATFORM_TYPE = "org.jetbrains.kotlin.platform.type"
JVM_ENVIRONMENT_ATTRIBUTE = "org.gradle.jvm.environment"
ANDROID_JVM_ENVIRONMENT = "android"
STANDARD_JVM_ENVIRONMENT = "standard-jvm"
SUPPORTED_JVM_ENVIRONMENTS = frozenset(
    {ANDROID_JVM_ENVIRONMENT, STANDARD_JVM_ENVIRONMENT}
)
ANDROID_TOOLING_TARGET_CLASSES = frozenset(
    {
        "org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget",
        (
            "com.android.build.api.variant.impl."
            "KotlinMultiplatformAndroidLibraryTargetImpl"
        ),
    }
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("repository", type=Path, help="Root of the partial Maven repository")
    parser.add_argument(
        "--close-group-prefix",
        help=(
            "also remove variants whose direct dependency inside this Maven group prefix "
            "is absent from the partial repository"
        ),
    )
    return parser.parse_args()


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path} does not contain a JSON object")
    return value


def resolve_within(path: Path, repository: Path, description: str) -> Path:
    resolved = path.resolve()
    try:
        resolved.relative_to(repository)
    except ValueError as error:
        raise ValueError(f"{description} escapes {repository}: {resolved}") from error
    return resolved


def regenerate_sidecars(path: Path) -> None:
    for sidecar in path.parent.glob(path.name + ".*"):
        sidecar.unlink()
    payload = path.read_bytes()
    for algorithm in ("md5", "sha1", "sha256", "sha512"):
        digest = hashlib.new(algorithm, payload).hexdigest()
        path.with_name(path.name + f".{algorithm}").write_text(
            digest + "\n", encoding="ascii"
        )


def dependency_coordinate(
    dependency: Any,
    module_path: Path,
    variant_name: str,
    group_prefix: str,
) -> tuple[str, str, str] | None:
    if not isinstance(dependency, dict):
        raise ValueError(f"{module_path} {variant_name} contains a non-object dependency")
    group = dependency.get("group")
    if not isinstance(group, str) or not (
        group == group_prefix or group.startswith(group_prefix + ".")
    ):
        return None
    artifact = dependency.get("module")
    version_constraint = dependency.get("version")
    version = (
        version_constraint.get("requires")
        if isinstance(version_constraint, dict)
        else None
    )
    if not all(isinstance(value, str) and value for value in (group, artifact, version)):
        raise ValueError(
            f"{module_path} {variant_name} has a non-exact dependency inside {group_prefix}"
        )
    return group, artifact, version


def publication_exists(repository: Path, coordinate: tuple[str, str, str]) -> bool:
    group, artifact, version = coordinate
    directory = repository.joinpath(*group.split("."), artifact, version)
    return any(
        (directory / f"{artifact}-{version}{suffix}").is_file()
        for suffix in (".module", ".pom")
    )


def tooling_jvm_environment(target: dict[str, Any], tooling_path: Path) -> str:
    target_class = target.get("target")
    if not isinstance(target_class, str) or not target_class:
        raise ValueError(f"{tooling_path} contains a JVM target without a target class")

    extras = target.get("extras")
    extras = extras if isinstance(extras, dict) else {}
    has_jvm_extras = isinstance(extras.get("jvm"), dict)
    has_android_extras = isinstance(extras.get("android"), dict)
    # The Kotlin tooling-metadata schema emits jvm extras only for KotlinJvmTarget and
    # android extras for the classic KotlinAndroidTarget. The newer Android KMP library
    # target is owned by AGP rather than KotlinAndroidTarget, so it currently has no
    # android extras and is identified by the canonical target class the schema records.
    is_android = (
        has_android_extras or target_class in ANDROID_TOOLING_TARGET_CLASSES
    )

    if has_jvm_extras == is_android:
        raise ValueError(
            f"{tooling_path} cannot distinguish JVM target {target_class!r} as "
            "Android or standard JVM"
        )
    return STANDARD_JVM_ENVIRONMENT if has_jvm_extras else ANDROID_JVM_ENVIRONMENT


def prune_tooling_metadata(
    module_path: Path,
    kept_variants: list[dict[str, Any]],
) -> int:
    tooling_files = list(module_path.parent.glob("*-kotlin-tooling-metadata.json"))
    if len(tooling_files) > 1:
        raise ValueError(f"{module_path.parent} contains multiple tooling metadata files")
    if not tooling_files:
        return 0

    platform_types: set[str] = set()
    native_targets: set[str] = set()
    jvm_environments: set[str] = set()
    for variant in kept_variants:
        attributes = variant.get("attributes")
        if not isinstance(attributes, dict):
            continue
        platform_type = attributes.get(KOTLIN_PLATFORM_TYPE)
        if isinstance(platform_type, str):
            if platform_type == "jvm":
                environment = attributes.get(JVM_ENVIRONMENT_ATTRIBUTE)
                if (
                    not isinstance(environment, str)
                    or environment not in SUPPORTED_JVM_ENVIRONMENTS
                ):
                    raise ValueError(
                        f"{module_path} {variant.get('name')!r} has ambiguous JVM "
                        f"environment {environment!r}"
                    )
                jvm_environments.add(environment)
            else:
                platform_types.add(platform_type)
        native_target = attributes.get("org.jetbrains.kotlin.native.target")
        if isinstance(native_target, str):
            native_targets.add(native_target)

    tooling_path = tooling_files[0]
    tooling = load_json(tooling_path)
    targets = tooling.get("projectTargets")
    if not isinstance(targets, list):
        raise ValueError(f"{tooling_path} has no projectTargets array")

    def is_kept(target: Any) -> bool:
        if not isinstance(target, dict):
            raise ValueError(f"{tooling_path} contains a non-object project target")
        platform_type = target.get("platformType")
        if platform_type == "common":
            return True
        if platform_type == "jvm":
            if not jvm_environments:
                return False
            return tooling_jvm_environment(target, tooling_path) in jvm_environments
        if platform_type != "native":
            return isinstance(platform_type, str) and platform_type in platform_types
        extras = target.get("extras")
        native = extras.get("native") if isinstance(extras, dict) else None
        konan_target = native.get("konanTarget") if isinstance(native, dict) else None
        return isinstance(konan_target, str) and konan_target in native_targets

    kept_targets = [target for target in targets if is_kept(target)]
    removed = len(targets) - len(kept_targets)
    if removed:
        tooling["projectTargets"] = kept_targets
        tooling_path.write_text(json.dumps(tooling, indent=2) + "\n", encoding="utf-8")
        regenerate_sidecars(tooling_path)
    return removed


def normalize(
    repository: Path,
    close_group_prefix: str | None = None,
) -> tuple[int, int, int]:
    repository = repository.resolve()
    if not repository.is_dir():
        raise ValueError(f"not a Maven repository directory: {repository}")

    changed_modules = 0
    removed_variants = 0
    removed_tooling_targets = 0
    module_paths = sorted(repository.rglob("*.module"))
    # Validate every present module before mutating anything. A corrupt child is a hard producer
    # failure, never an excuse to prune its parent's otherwise-present redirect.
    modules = {module_path: load_json(module_path) for module_path in module_paths}
    for module_path, module in modules.items():
        variants = module.get("variants")
        if not isinstance(variants, list):
            raise ValueError(f"{module_path} has no variants array")

        kept: list[dict[str, Any]] = []
        removed_names: list[str] = []
        for variant in variants:
            if not isinstance(variant, dict) or not isinstance(variant.get("name"), str):
                raise ValueError(f"{module_path} contains a variant without a name")
            redirect = variant.get("available-at")
            if isinstance(redirect, dict):
                url = redirect.get("url")
                if not isinstance(url, str) or not url:
                    raise ValueError(
                        f"{module_path} {variant['name']} has an invalid redirect URL"
                    )
                child = resolve_within(
                    module_path.parent / url,
                    repository,
                    f"{module_path} {variant['name']} redirect",
                )
                coordinates = tuple(
                    redirect.get(key) for key in ("group", "module", "version")
                )
                if not all(isinstance(value, str) and value for value in coordinates):
                    raise ValueError(
                        f"{module_path} {variant['name']} has incomplete redirect coordinates"
                    )
                group, artifact, version = coordinates
                expected_child = repository.joinpath(
                    *group.split("."), artifact, version, f"{artifact}-{version}.module"
                ).resolve()
                if child != expected_child:
                    raise ValueError(
                        f"{module_path} {variant['name']} URL does not match its coordinates: "
                        f"{child} != {expected_child}"
                    )
                if not child.is_file():
                    removed_names.append(variant["name"])
                    continue

            if close_group_prefix is not None:
                dependencies = variant.get("dependencies", [])
                if not isinstance(dependencies, list):
                    raise ValueError(
                        f"{module_path} {variant['name']} has a non-array dependencies field"
                    )
                missing_dependencies = [
                    coordinate
                    for dependency in dependencies
                    if (
                        coordinate := dependency_coordinate(
                            dependency,
                            module_path,
                            variant["name"],
                            close_group_prefix,
                        )
                    )
                    is not None
                    and not publication_exists(repository, coordinate)
                ]
                if missing_dependencies:
                    print(
                        f"pruning {module_path} {variant['name']}: missing "
                        + ", ".join(":".join(value) for value in missing_dependencies)
                    )
                    removed_names.append(variant["name"])
                    continue

            kept.append(variant)

        if not removed_names:
            continue
        module["variants"] = kept
        module_path.write_text(json.dumps(module, indent=2) + "\n", encoding="utf-8")
        regenerate_sidecars(module_path)
        removed_tooling_targets += prune_tooling_metadata(module_path, kept)
        changed_modules += 1
        removed_variants += len(removed_names)
        print(f"pruned {module_path}: {', '.join(removed_names)}")

    # The output contract is stronger than the transformation: every redirect and every direct
    # fork dependency left in the repository must resolve inside that same artifact.
    for module_path in sorted(repository.rglob("*.module")):
        module = load_json(module_path)
        for variant in module.get("variants", []):
            redirect = variant.get("available-at")
            if isinstance(redirect, dict):
                child = resolve_within(
                    module_path.parent / redirect["url"],
                    repository,
                    f"{module_path} {variant['name']} redirect",
                )
                if not child.is_file():
                    raise ValueError(
                        f"kept redirect has no child module: {module_path} -> {child}"
                    )
            if close_group_prefix is not None:
                for dependency in variant.get("dependencies", []):
                    coordinate = dependency_coordinate(
                        dependency,
                        module_path,
                        variant["name"],
                        close_group_prefix,
                    )
                    if coordinate is not None and not publication_exists(
                        repository, coordinate
                    ):
                        raise ValueError(
                            f"kept variant has a missing fork dependency: "
                            f"{module_path} {variant['name']} -> {':'.join(coordinate)}"
                        )

    print(
        f"normalized partial repository: {changed_modules} module(s), "
        f"{removed_variants} unclosed variant(s), "
        f"{removed_tooling_targets} tooling target(s) pruned"
    )
    return changed_modules, removed_variants, removed_tooling_targets


def main() -> None:
    args = parse_args()
    normalize(args.repository, args.close_group_prefix)


if __name__ == "__main__":
    main()
