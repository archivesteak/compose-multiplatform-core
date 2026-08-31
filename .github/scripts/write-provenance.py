#!/usr/bin/env python3
"""Write the exact, machine-readable source provenance for one producer shard."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


COMMIT = re.compile(r"^[0-9a-f]{40}$")
EXPECTED_SOURCES = {
    "apple": {"compose", "skiko"},
    "web": {"compose", "skiko"},
    "windows": {"compose", "skia", "skiko"},
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--owner", required=True, choices=tuple(EXPECTED_SOURCES))
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--source", action="append", required=True, metavar="NAME=COMMIT")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    sources: dict[str, str] = {}
    for declaration in args.source:
        name, separator, commit = declaration.partition("=")
        if not separator or not name or not COMMIT.fullmatch(commit):
            raise ValueError(f"invalid source declaration: {declaration!r}")
        if name in sources:
            raise ValueError(f"duplicate source name: {name}")
        sources[name] = commit
    expected_sources = EXPECTED_SOURCES[args.owner]
    if set(sources) != expected_sources:
        raise ValueError(
            f"{args.owner} provenance must contain exactly "
            + ", ".join(sorted(expected_sources))
        )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(
            {"schemaVersion": 1, "owner": args.owner, "sources": sources},
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
