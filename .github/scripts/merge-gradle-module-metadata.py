#!/usr/bin/env python3
"""Union one generated Gradle module descriptor into a Maven-local publication."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from typing import Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Merge variants from a generated module.json into the matching artifact in a "
            "Maven repository. Existing variants win when names overlap unless an explicit "
            "include allowlist requires an exact match."
        )
    )
    parser.add_argument("source", type=Path, help="Generated Gradle module metadata")
    parser.add_argument("repository", type=Path, help="Root of the Maven repository")
    parser.add_argument(
        "--require-variant",
        action="append",
        default=[],
        help="Variant that must exist in the source and merged descriptor (repeatable)",
    )
    parser.add_argument(
        "--include-variant",
        action="append",
        default=[],
        help=(
            "Merge only this source variant (repeatable). Every named variant must exist. "
            "Without this option all source variants are considered."
        ),
    )
    parser.add_argument(
        "--source-tooling-metadata",
        type=Path,
        help=(
            "Generated kotlin-tooling-metadata.json paired with the source descriptor. "
            "Required when --include-konan-target is used."
        ),
    )
    parser.add_argument(
        "--include-konan-target",
        action="append",
        default=[],
        help=(
            "Union this Kotlin/Native konanTarget into the published tooling metadata "
            "(repeatable). Every named target must exist in the source."
        ),
    )
    return parser.parse_args()


def load_module(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as stream:
        value = json.load(stream)
    if not isinstance(value, dict) or not isinstance(value.get("variants"), list):
        raise ValueError(f"{path} is not Gradle module metadata")
    return value


def coordinates(module: dict[str, Any], path: Path) -> tuple[str, str, str]:
    component = module.get("component")
    if not isinstance(component, dict):
        raise ValueError(f"{path} has no component coordinates")
    values = tuple(component.get(key) for key in ("group", "module", "version"))
    if not all(isinstance(value, str) and value for value in values):
        raise ValueError(f"{path} has incomplete component coordinates")
    return values  # type: ignore[return-value]


def variants_by_name(module: dict[str, Any], path: Path) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for variant in module["variants"]:
        if not isinstance(variant, dict) or not isinstance(variant.get("name"), str):
            raise ValueError(f"{path} contains a variant without a name")
        name = variant["name"]
        if name in result:
            raise ValueError(f"{path} contains duplicate variant {name!r}")
        result[name] = variant
    return result


def load_tooling_metadata(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as stream:
        value = json.load(stream)
    if not isinstance(value, dict) or not isinstance(value.get("projectTargets"), list):
        raise ValueError(f"{path} is not Kotlin tooling metadata")
    return value


def konan_targets_by_name(
    metadata: dict[str, Any], path: Path
) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for target in metadata["projectTargets"]:
        if not isinstance(target, dict):
            raise ValueError(f"{path} contains a non-object project target")
        extras = target.get("extras")
        native = extras.get("native") if isinstance(extras, dict) else None
        konan_target = native.get("konanTarget") if isinstance(native, dict) else None
        if konan_target is None:
            continue
        if not isinstance(konan_target, str) or not konan_target:
            raise ValueError(f"{path} contains an invalid konanTarget")
        if konan_target in result:
            raise ValueError(f"{path} contains duplicate konanTarget {konan_target!r}")
        result[konan_target] = target
    return result


def write_json(path: Path, value: dict[str, Any]) -> None:
    temporary_path = path.with_suffix(path.suffix + ".tmp")
    with temporary_path.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, indent=2, ensure_ascii=False)
        stream.write("\n")
    os.replace(temporary_path, path)


def remove_stale_sidecars(path: Path) -> list[Path]:
    removed: list[Path] = []
    for sidecar in path.parent.glob(path.name + ".*"):
        if sidecar.is_file() or sidecar.is_symlink():
            sidecar.unlink()
            removed.append(sidecar)
    return removed


def main() -> None:
    args = parse_args()
    included_konan_targets = set(args.include_konan_target)
    if included_konan_targets and args.source_tooling_metadata is None:
        raise ValueError(
            "--source-tooling-metadata is required with --include-konan-target"
        )
    if args.source_tooling_metadata is not None and not included_konan_targets:
        raise ValueError(
            "--include-konan-target is required with --source-tooling-metadata"
        )

    source_path = args.source.resolve()
    repository = args.repository.resolve()
    source = load_module(source_path)
    group, artifact, version = coordinates(source, source_path)
    destination_path = (
        repository
        / Path(*group.split("."))
        / artifact
        / version
        / f"{artifact}-{version}.module"
    )
    if not destination_path.is_file():
        raise FileNotFoundError(
            f"destination publication does not exist: {destination_path}"
        )

    destination = load_module(destination_path)
    if coordinates(destination, destination_path) != (group, artifact, version):
        raise ValueError("source and destination component coordinates differ")

    source_variants = variants_by_name(source, source_path)
    destination_variants = variants_by_name(destination, destination_path)
    included_names = set(args.include_variant)
    missing_included = sorted(included_names - source_variants.keys())
    if missing_included:
        raise ValueError(
            "source metadata is missing included variants: " + ", ".join(missing_included)
        )
    missing_required = sorted(set(args.require_variant) - source_variants.keys())
    if missing_required:
        raise ValueError(
            "source metadata is missing required variants: " + ", ".join(missing_required)
        )

    added: list[str] = []
    for name, variant in source_variants.items():
        if included_names and name not in included_names:
            continue
        existing = destination_variants.get(name)
        if existing is not None:
            if included_names and existing != variant:
                raise ValueError(
                    f"destination already has a different included variant {name!r}"
                )
            continue
        destination["variants"].append(variant)
        destination_variants[name] = variant
        added.append(name)

    missing_after_merge = sorted(set(args.require_variant) - destination_variants.keys())
    if missing_after_merge:
        raise AssertionError(
            "merged metadata is missing required variants: "
            + ", ".join(missing_after_merge)
        )

    tooling_added: list[str] = []
    source_tooling_path: Path | None = None
    destination_tooling_path: Path | None = None
    destination_tooling: dict[str, Any] | None = None
    if args.source_tooling_metadata is not None:
        source_tooling_path = args.source_tooling_metadata.resolve()
        destination_tooling_path = destination_path.with_name(
            f"{artifact}-{version}-kotlin-tooling-metadata.json"
        )
        if not destination_tooling_path.is_file():
            raise FileNotFoundError(
                "destination tooling metadata does not exist: "
                f"{destination_tooling_path}"
            )
        source_tooling = load_tooling_metadata(source_tooling_path)
        destination_tooling = load_tooling_metadata(destination_tooling_path)
        comparable_keys = (
            "schemaVersion",
            "buildSystem",
            "buildSystemVersion",
            "buildPlugin",
            "buildPluginVersion",
            "projectSettings",
        )
        differing_keys = [
            key
            for key in comparable_keys
            if source_tooling.get(key) != destination_tooling.get(key)
        ]
        if differing_keys:
            raise ValueError(
                "source and destination tooling metadata differ in: "
                + ", ".join(differing_keys)
            )

        source_konan_targets = konan_targets_by_name(source_tooling, source_tooling_path)
        destination_konan_targets = konan_targets_by_name(
            destination_tooling, destination_tooling_path
        )
        missing_konan_targets = sorted(
            included_konan_targets - source_konan_targets.keys()
        )
        if missing_konan_targets:
            raise ValueError(
                "source tooling metadata is missing included konan targets: "
                + ", ".join(missing_konan_targets)
            )
        for konan_target in args.include_konan_target:
            source_target = source_konan_targets[konan_target]
            existing_target = destination_konan_targets.get(konan_target)
            if existing_target is not None:
                if existing_target != source_target:
                    raise ValueError(
                        "destination tooling metadata has a different target for "
                        f"{konan_target!r}"
                    )
                continue
            destination_tooling["projectTargets"].append(source_target)
            destination_konan_targets[konan_target] = source_target
            tooling_added.append(konan_target)

        missing_after_tooling_merge = sorted(
            included_konan_targets - destination_konan_targets.keys()
        )
        if missing_after_tooling_merge:
            raise AssertionError(
                "merged tooling metadata is missing included konan targets: "
                + ", ".join(missing_after_tooling_merge)
            )

    if not added and not tooling_added:
        print(f"No new variants to merge into {destination_path}")
        if destination_tooling_path is not None:
            print(f"No new project targets to merge into {destination_tooling_path}")
        return

    if added:
        write_json(destination_path, destination)
    if tooling_added:
        assert destination_tooling_path is not None
        assert destination_tooling is not None
        write_json(destination_tooling_path, destination_tooling)

    # Mutated metadata no longer matches signatures or checksums produced by either partial build.
    # A durable publication must sign/checksum fully assembled descriptors, not preserve a sidecar
    # from one input. Maven Local/Actions artifacts deliberately remain unsigned here.
    removed_sidecars: list[Path] = []
    if added:
        removed_sidecars.extend(remove_stale_sidecars(destination_path))
    if tooling_added:
        assert destination_tooling_path is not None
        removed_sidecars.extend(remove_stale_sidecars(destination_tooling_path))

    if added:
        print(f"Merged {len(added)} variants into {destination_path}")
        for name in added:
            print(f"  + variant {name}")
    if tooling_added:
        assert destination_tooling_path is not None
        print(
            f"Merged {len(tooling_added)} project targets into "
            f"{destination_tooling_path}"
        )
        for name in tooling_added:
            print(f"  + konan target {name}")
    for sidecar in removed_sidecars:
        print(f"  - stale sidecar {sidecar.name}")


if __name__ == "__main__":
    main()
