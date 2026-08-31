#!/usr/bin/env python3

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("write-provenance.py")
COMPOSE = "1" * 40
SKIA = "2" * 40
SKIKO = "3" * 40


class WriteProvenanceTest(unittest.TestCase):
    def run_writer(self, owner: str, *sources: str) -> tuple[subprocess.CompletedProcess[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        output = Path(temporary.name) / "provenance" / f"{owner}.json"
        completed = subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "--owner",
                owner,
                "--output",
                str(output),
                *(argument for source in sources for argument in ("--source", source)),
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        return completed, output

    def test_apple_marker_has_exact_schema(self) -> None:
        completed, output = self.run_writer(
            "apple", f"compose={COMPOSE}", f"skiko={SKIKO}"
        )
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual(
            {
                "schemaVersion": 1,
                "owner": "apple",
                "sources": {"compose": COMPOSE, "skiko": SKIKO},
            },
            json.loads(output.read_text(encoding="utf-8")),
        )

    def test_windows_marker_requires_skia(self) -> None:
        completed, output = self.run_writer(
            "windows", f"compose={COMPOSE}", f"skiko={SKIKO}"
        )
        self.assertNotEqual(0, completed.returncode)
        self.assertIn("compose, skia, skiko", completed.stderr)
        self.assertFalse(output.exists())

    def test_windows_marker_has_exact_sources(self) -> None:
        completed, output = self.run_writer(
            "windows",
            f"compose={COMPOSE}",
            f"skia={SKIA}",
            f"skiko={SKIKO}",
        )
        self.assertEqual(0, completed.returncode, completed.stderr)
        marker = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual("windows", marker["owner"])
        self.assertEqual(
            {"compose": COMPOSE, "skia": SKIA, "skiko": SKIKO},
            marker["sources"],
        )

    def test_rejects_duplicate_or_non_sha_source(self) -> None:
        for sources in (
            (f"compose={COMPOSE}", f"compose={COMPOSE}", f"skiko={SKIKO}"),
            ("compose=main", f"skiko={SKIKO}"),
        ):
            with self.subTest(sources=sources):
                completed, output = self.run_writer("web", *sources)
                self.assertNotEqual(0, completed.returncode)
                self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
