#!/usr/bin/env python3
"""Verify KMP root redirects and their concrete Maven target artifacts."""

from __future__ import annotations

import argparse
import hashlib
import json
import shlex
import zipfile
from pathlib import Path
from typing import Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("repository", type=Path, help="Root of the Maven repository")
    parser.add_argument(
        "--coordinate",
        action="append",
        required=True,
        help="Root publication as group:artifact:version (repeatable)",
    )
    parser.add_argument(
        "--target",
        action="append",
        required=True,
        help="Target and expected file suffix as target=.suffix (repeatable)",
    )
    parser.add_argument(
        "--forbid-root-variant-prefix",
        action="append",
        default=[],
        help="Fail when any root variant starts with this prefix (repeatable)",
    )
    parser.add_argument(
        "--require-klib-linker-option",
        action="append",
        default=[],
        help="Require target KLIB metadata to carry target=linker-option (repeatable)",
    )
    return parser.parse_args()


def split_exact(value: str, separator: str, count: int, description: str) -> list[str]:
    parts = value.split(separator)
    if len(parts) != count or not all(parts):
        raise ValueError(f"invalid {description}: {value!r}")
    return parts


def load_module(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as stream:
        value = json.load(stream)
    if not isinstance(value, dict) or not isinstance(value.get("variants"), list):
        raise ValueError(f"{path} is not Gradle module metadata")
    return value


def variants_by_name(module: dict[str, Any], path: Path) -> dict[str, dict[str, Any]]:
    variants: dict[str, dict[str, Any]] = {}
    for variant in module["variants"]:
        if not isinstance(variant, dict) or not isinstance(variant.get("name"), str):
            raise ValueError(f"{path} contains a variant without a name")
        name = variant["name"]
        if name in variants:
            raise ValueError(f"{path} contains duplicate variant {name!r}")
        variants[name] = variant
    return variants


def resolve_within(path: Path, root: Path, description: str) -> Path:
    resolved = path.resolve()
    try:
        resolved.relative_to(root)
    except ValueError as error:
        raise ValueError(f"{description} resolves outside {root}: {resolved}") from error
    return resolved


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_archive(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
    if path.suffix == ".klib":
        if "default/manifest" not in names or not any(
            name.startswith(("default/ir/", "default/linkdata/")) for name in names
        ):
            raise ValueError(f"{path} is not a populated Kotlin KLIB")
    elif path.suffix == ".aar" and "AndroidManifest.xml" not in names:
        raise ValueError(f"{path} is not a populated Android AAR")


def klib_linker_options(paths: list[Path]) -> set[str]:
    options: set[str] = set()
    for path in paths:
        if path.suffix != ".klib":
            continue
        with zipfile.ZipFile(path) as archive:
            manifest = archive.read("default/manifest").decode("utf-8")
        fields = dict(
            line.split("=", 1)
            for line in manifest.splitlines()
            if "=" in line
        )
        options.update(shlex.split(fields.get("linkerOpts", "")))
    return options


def main() -> None:
    args = parse_args()
    repository = args.repository.resolve()
    targets = dict(
        split_exact(value, "=", 2, "target") for value in args.target
    )
    if len(targets) != len(args.target):
        raise ValueError("duplicate --target values are not allowed")
    if any(not suffix.startswith(".") for suffix in targets.values()):
        raise ValueError("every target suffix must start with '.'")
    required_linker_options: dict[str, set[str]] = {}
    for value in args.require_klib_linker_option:
        target, option = split_exact(value, "=", 2, "KLIB linker option")
        if target not in targets:
            raise ValueError(
                f"KLIB linker option target {target!r} has no matching --target"
            )
        required_linker_options.setdefault(target, set()).add(option)
    if len(set(args.coordinate)) != len(args.coordinate):
        raise ValueError("duplicate --coordinate values are not allowed")

    for value in args.coordinate:
        group, artifact, version = split_exact(value, ":", 3, "coordinate")
        publication = repository.joinpath(*group.split("."), artifact, version)
        root_path = publication / f"{artifact}-{version}.module"
        root = load_module(root_path)
        variants = variants_by_name(root, root_path)

        forbidden = sorted(
            name
            for name in variants
            if any(name.startswith(prefix) for prefix in args.forbid_root_variant_prefix)
        )
        if forbidden:
            raise ValueError(
                f"{root_path} contains forbidden variants: {', '.join(forbidden)}"
            )

        for target, suffix in targets.items():
            variant_name = f"{target}ApiElements-published"
            variant = variants.get(variant_name)
            if variant is None:
                raise ValueError(f"{root_path} lacks {variant_name}")
            redirect = variant.get("available-at")
            if not isinstance(redirect, dict) or not isinstance(redirect.get("url"), str):
                raise ValueError(f"{root_path} {variant_name} has no available-at URL")
            redirect_coordinates = tuple(
                redirect.get(key) for key in ("group", "module", "version")
            )
            if not all(
                isinstance(part, str) and part for part in redirect_coordinates
            ):
                raise ValueError(
                    f"{root_path} {variant_name} has incomplete redirect coordinates"
                )
            target_group, target_artifact, target_version = redirect_coordinates
            expected_target_path = repository.joinpath(
                *target_group.split("."),
                target_artifact,
                target_version,
                f"{target_artifact}-{target_version}.module",
            )
            target_module_path = resolve_within(
                root_path.parent / redirect["url"],
                repository,
                f"{root_path} {variant_name} redirect",
            )
            if target_module_path != expected_target_path.resolve():
                raise ValueError(
                    f"{root_path} {variant_name} redirect URL does not match its coordinates"
                )
            target_module = load_module(target_module_path)
            variants_by_name(target_module, target_module_path)
            files: dict[Path, dict[str, Any]] = {}
            for target_variant in target_module["variants"]:
                target_files = target_variant.get("files", [])
                if not isinstance(target_files, list):
                    raise ValueError(
                        f"{target_module_path} {target_variant['name']} has invalid files"
                    )
                for declared in target_files:
                    if not isinstance(declared, dict):
                        raise ValueError(
                            f"{target_module_path} contains a non-object file entry"
                        )
                    url = declared.get("url")
                    if not isinstance(url, str) or not url.endswith(suffix):
                        continue
                    path = resolve_within(
                        target_module_path.parent / url,
                        target_module_path.parent.resolve(),
                        f"{target_module_path} artifact URL",
                    )
                    existing = files.get(path)
                    if existing is not None and existing != declared:
                        raise ValueError(
                            f"{target_module_path} declares conflicting metadata for {path.name}"
                        )
                    files[path] = declared
            if not files:
                raise ValueError(
                    f"{target_module_path} exposes no artifact ending in {suffix}"
                )
            for path, declared in files.items():
                if not path.is_file() or path.stat().st_size == 0:
                    raise ValueError(f"missing or empty target artifact: {path}")
                if declared.get("size") != path.stat().st_size:
                    raise ValueError(f"declared size does not match {path}")
                declared_sha256 = declared.get("sha256")
                if not isinstance(declared_sha256, str) or sha256(path) != declared_sha256:
                    raise ValueError(f"declared SHA-256 does not match {path}")
                verify_archive(path)
            required_options = required_linker_options.get(target, set())
            if required_options:
                actual_options = klib_linker_options(list(files))
                missing_options = sorted(required_options - actual_options)
                if missing_options:
                    raise ValueError(
                        f"{target_module_path} KLIBs lack required linker options: "
                        + ", ".join(missing_options)
                    )
            print(
                f"verified {group}:{artifact}:{version} {variant_name}: "
                + ", ".join(
                    f"{path.name} ({path.stat().st_size} bytes)" for path in files
                )
            )


if __name__ == "__main__":
    main()
