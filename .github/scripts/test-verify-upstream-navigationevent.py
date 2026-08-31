#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import unittest
import zipfile
from pathlib import Path


SCRIPT = Path(__file__).with_name("verify-upstream-navigationevent.py")
SPEC = importlib.util.spec_from_file_location("verify_upstream_navigationevent", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


def encoded(value: object) -> bytes:
    return json.dumps(value, separators=(",", ":"), sort_keys=True).encode("utf-8")


def klib(marker: str) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("default/manifest", "unique_name=synthetic-navigationevent")
        archive.writestr("default/ir/body", marker)
    return output.getvalue()


def synthetic_artifacts(marker: str = "trusted") -> dict[str, bytes]:
    klib_payload = klib(marker)
    root_payload = encoded(
        {
            "variants": [
                {
                    "name": VERIFIER.TARGET_VARIANT,
                    "available-at": {
                        "group": "androidx.navigationevent",
                        "module": VERIFIER.TARGET_MODULE,
                        "version": VERIFIER.VERSION,
                    },
                }
            ]
        }
    )
    target_payload = encoded(
        {
            "component": {
                "group": "androidx.navigationevent",
                "module": VERIFIER.ROOT_MODULE,
                "version": VERIFIER.VERSION,
            },
            "variants": [
                {
                    "name": VERIFIER.TARGET_VARIANT,
                    "files": [
                        {
                            "name": VERIFIER.TARGET_KLIB_DECLARED_NAME,
                            "url": VERIFIER.TARGET_KLIB_FILENAME,
                            "size": len(klib_payload),
                            "sha256": hashlib.sha256(klib_payload).hexdigest(),
                        }
                    ],
                }
            ],
        }
    )
    return {
        VERIFIER.ROOT_MODULE_PATH: root_payload,
        VERIFIER.TARGET_MODULE_PATH: target_payload,
        VERIFIER.TARGET_KLIB_PATH: klib_payload,
    }


def anchors(artifacts: dict[str, bytes]) -> dict[str, object]:
    return {
        path: VERIFIER.TrustAnchor(
            size=len(payload),
            sha256=hashlib.sha256(payload).hexdigest(),
        )
        for path, payload in artifacts.items()
    }


class VerifyUpstreamNavigationEventTest(unittest.TestCase):
    def test_repository_pins_are_the_reviewed_upstream_artifacts(self) -> None:
        self.assertEqual(
            {
                VERIFIER.ROOT_MODULE_PATH: VERIFIER.TrustAnchor(
                    36116,
                    "ed89d604fd391ddd183337f64b86ff02909293d751eab2dbcaf58d20a33f930f",
                ),
                VERIFIER.TARGET_MODULE_PATH: VERIFIER.TrustAnchor(
                    4476,
                    "2ce45a1f404c02178011e5dec40daebda150a433ac9566a1109c3c4c3d5cd6d4",
                ),
                VERIFIER.TARGET_KLIB_PATH: VERIFIER.TrustAnchor(
                    77955,
                    "76c310e2ed6976e7ac12790502ebc753cf0aa09fd7923930b67d06a095ee61d7",
                ),
            },
            dict(VERIFIER.TRUST_ANCHORS),
        )

    def test_matching_independent_pins_pass_offline(self) -> None:
        artifacts = synthetic_artifacts()
        result = VERIFIER.verify(artifacts.__getitem__, anchors(artifacts))
        self.assertIn("verified upstream", result)

    def test_each_pinned_payload_rejects_same_size_substitution(self) -> None:
        artifacts = synthetic_artifacts()
        trust_anchors = anchors(artifacts)

        for path in artifacts:
            with self.subTest(path=path):
                tampered_artifacts = dict(artifacts)
                original = artifacts[path]
                tampered_artifacts[path] = original[:-1] + bytes([original[-1] ^ 1])
                with self.assertRaisesRegex(
                    ValueError,
                    rf"{path.rsplit('/', 1)[-1]}.*repository-pinned SHA-256",
                ):
                    VERIFIER.verify(tampered_artifacts.__getitem__, trust_anchors)

    def test_coherent_tampered_metadata_and_klib_are_rejected(self) -> None:
        trusted_artifacts = synthetic_artifacts()
        trust_anchors = anchors(trusted_artifacts)

        tampered_artifacts = synthetic_artifacts("tamper!")
        # The target metadata coherently declares the tampered KLIB's new size and hash.
        # It still cannot replace the independently pinned target metadata.
        self.assertEqual(
            len(trusted_artifacts[VERIFIER.TARGET_MODULE_PATH]),
            len(tampered_artifacts[VERIFIER.TARGET_MODULE_PATH]),
        )
        self.assertNotEqual(
            trusted_artifacts[VERIFIER.TARGET_MODULE_PATH],
            tampered_artifacts[VERIFIER.TARGET_MODULE_PATH],
        )
        with self.assertRaisesRegex(
            ValueError,
            r"navigationevent-mingwx64-1\.1\.1\.module.*repository-pinned SHA-256",
        ):
            VERIFIER.verify(tampered_artifacts.__getitem__, trust_anchors)


if __name__ == "__main__":
    unittest.main()
