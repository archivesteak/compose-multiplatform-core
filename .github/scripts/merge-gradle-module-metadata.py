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
            "Maven repository. Existing variants win when names overlap."
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


def main() -> None:
    args = parse_args()
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
    missing_required = sorted(set(args.require_variant) - source_variants.keys())
    if missing_required:
        raise ValueError(
            "source metadata is missing required variants: " + ", ".join(missing_required)
        )

    added: list[str] = []
    for name, variant in source_variants.items():
        if name not in destination_variants:
            destination["variants"].append(variant)
            destination_variants[name] = variant
            added.append(name)

    missing_after_merge = sorted(set(args.require_variant) - destination_variants.keys())
    if missing_after_merge:
        raise AssertionError(
            "merged metadata is missing required variants: "
            + ", ".join(missing_after_merge)
        )

    if not added:
        print(f"No new variants to merge into {destination_path}")
        return

    temporary_path = destination_path.with_suffix(destination_path.suffix + ".tmp")
    with temporary_path.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(destination, stream, indent=2, ensure_ascii=False)
        stream.write("\n")
    os.replace(temporary_path, destination_path)

    # The descriptor no longer matches signatures or checksums produced by either partial build.
    # A durable publication must sign/checksum the fully assembled descriptor, not preserve a
    # sidecar from one input. Maven Local/Actions artifacts deliberately remain unsigned here.
    removed_sidecars: list[Path] = []
    for sidecar in destination_path.parent.glob(destination_path.name + ".*"):
        if sidecar.is_file() or sidecar.is_symlink():
            sidecar.unlink()
            removed_sidecars.append(sidecar)

    print(f"Merged {len(added)} variants into {destination_path}")
    for name in added:
        print(f"  + {name}")
    for sidecar in removed_sidecars:
        print(f"  - stale sidecar {sidecar.name}")


if __name__ == "__main__":
    main()
