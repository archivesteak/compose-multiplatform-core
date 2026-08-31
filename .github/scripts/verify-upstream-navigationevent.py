#!/usr/bin/env python3
"""Verify the exact upstream NavigationEvent base artifact used by the fork bridge."""

from __future__ import annotations

import hashlib
import io
import json
import urllib.request
import zipfile
from collections.abc import Callable, Mapping
from types import MappingProxyType
from typing import Any, NamedTuple


BASE_URL = "https://dl.google.com/dl/android/maven2/androidx/navigationevent"
VERSION = "1.1.1"
ROOT_MODULE = "navigationevent"
TARGET_MODULE = "navigationevent-mingwx64"
TARGET_VARIANT = "mingwX64ApiElements-published"
ROOT_MODULE_PATH = f"{ROOT_MODULE}/{VERSION}/{ROOT_MODULE}-{VERSION}.module"
TARGET_MODULE_PATH = f"{TARGET_MODULE}/{VERSION}/{TARGET_MODULE}-{VERSION}.module"
TARGET_KLIB_FILENAME = f"{TARGET_MODULE}-{VERSION}.klib"
TARGET_KLIB_PATH = f"{TARGET_MODULE}/{VERSION}/{TARGET_KLIB_FILENAME}"
TARGET_KLIB_DECLARED_NAME = f"navigationevent-mingwX64Main-{VERSION}.klib"


class TrustAnchor(NamedTuple):
    size: int
    sha256: str


# These values are deliberately independent of the checksums declared by the downloaded
# Gradle module metadata. Updating an upstream version requires reviewing and changing all
# three repository-owned trust anchors together.
TRUST_ANCHORS: Mapping[str, TrustAnchor] = MappingProxyType(
    {
        ROOT_MODULE_PATH: TrustAnchor(
            size=36116,
            sha256="ed89d604fd391ddd183337f64b86ff02909293d751eab2dbcaf58d20a33f930f",
        ),
        TARGET_MODULE_PATH: TrustAnchor(
            size=4476,
            sha256="2ce45a1f404c02178011e5dec40daebda150a433ac9566a1109c3c4c3d5cd6d4",
        ),
        TARGET_KLIB_PATH: TrustAnchor(
            size=77955,
            sha256="76c310e2ed6976e7ac12790502ebc753cf0aa09fd7923930b67d06a095ee61d7",
        ),
    }
)

Downloader = Callable[[str], bytes]


def download(path: str) -> bytes:
    url = f"{BASE_URL}/{path}"
    request = urllib.request.Request(url, headers={"User-Agent": "compose-publication-verifier"})
    with urllib.request.urlopen(request, timeout=30) as response:
        if response.status != 200:
            raise ValueError(f"{url} returned HTTP {response.status}")
        payload = response.read()
    if not payload:
        raise ValueError(f"{url} is empty")
    return payload


def trusted_payload(
    path: str,
    download_artifact: Downloader,
    trust_anchors: Mapping[str, TrustAnchor],
) -> bytes:
    try:
        anchor = trust_anchors[path]
    except KeyError as error:
        raise ValueError(f"{path} has no repository-pinned trust anchor") from error

    payload = download_artifact(path)
    if len(payload) != anchor.size:
        raise ValueError(
            f"{path} does not match repository-pinned size: "
            f"expected {anchor.size}, got {len(payload)}"
        )
    actual_sha256 = hashlib.sha256(payload).hexdigest()
    if actual_sha256 != anchor.sha256:
        raise ValueError(
            f"{path} does not match repository-pinned SHA-256: "
            f"expected {anchor.sha256}, got {actual_sha256}"
        )
    return payload


def load_module(
    path: str,
    download_artifact: Downloader,
    trust_anchors: Mapping[str, TrustAnchor],
) -> dict[str, Any]:
    value = json.loads(trusted_payload(path, download_artifact, trust_anchors))
    if not isinstance(value, dict) or not isinstance(value.get("variants"), list):
        raise ValueError(f"{path} is not Gradle module metadata")
    return value


def one_variant(module: dict[str, Any], name: str) -> dict[str, Any]:
    matching = [variant for variant in module["variants"] if variant.get("name") == name]
    if len(matching) != 1:
        raise ValueError(f"expected exactly one {name!r} variant, found {len(matching)}")
    return matching[0]


def verify(
    download_artifact: Downloader = download,
    trust_anchors: Mapping[str, TrustAnchor] = TRUST_ANCHORS,
) -> str:
    root = load_module(ROOT_MODULE_PATH, download_artifact, trust_anchors)
    redirect = one_variant(root, TARGET_VARIANT).get("available-at")
    expected_redirect = {
        "group": "androidx.navigationevent",
        "module": TARGET_MODULE,
        "version": VERSION,
    }
    if not isinstance(redirect, dict) or any(
        redirect.get(key) != value for key, value in expected_redirect.items()
    ):
        raise ValueError(
            f"{ROOT_MODULE_PATH} has an invalid mingwX64 redirect: {redirect!r}"
        )

    target = load_module(TARGET_MODULE_PATH, download_artifact, trust_anchors)
    component = target.get("component")
    if not isinstance(component, dict) or any(
        component.get(key) != value
        for key, value in {
            "group": "androidx.navigationevent",
            "module": ROOT_MODULE,
            "version": VERSION,
        }.items()
    ):
        raise ValueError(
            f"{TARGET_MODULE_PATH} does not belong to the expected root component"
        )

    files = one_variant(target, TARGET_VARIANT).get("files")
    klibs = [
        entry
        for entry in files or []
        if isinstance(entry, dict) and str(entry.get("url", "")).endswith(".klib")
    ]
    if len(klibs) != 1:
        raise ValueError(
            f"{TARGET_MODULE_PATH} must declare exactly one mingwX64 KLIB"
        )
    declared = klibs[0]
    klib_anchor = trust_anchors[TARGET_KLIB_PATH]
    expected_declaration = {
        "name": TARGET_KLIB_DECLARED_NAME,
        "url": TARGET_KLIB_FILENAME,
        "size": klib_anchor.size,
        "sha256": klib_anchor.sha256,
    }
    if any(declared.get(key) != value for key, value in expected_declaration.items()):
        raise ValueError(
            f"{TARGET_MODULE_PATH} has an untrusted mingwX64 KLIB declaration: "
            f"{declared!r}"
        )

    payload = trusted_payload(TARGET_KLIB_PATH, download_artifact, trust_anchors)
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        names = set(archive.namelist())
    if "default/manifest" not in names or not any(
        name.startswith(("default/ir/", "default/linkdata/")) for name in names
    ):
        raise ValueError(f"{TARGET_KLIB_PATH} is not a populated Kotlin/Native KLIB")

    return (
        "verified upstream androidx.navigationevent:navigationevent:1.1.1 "
        f"mingwX64: {TARGET_KLIB_FILENAME} ({len(payload)} bytes, "
        f"SHA-256 {klib_anchor.sha256})"
    )


def main() -> None:
    print(verify())


if __name__ == "__main__":
    main()
