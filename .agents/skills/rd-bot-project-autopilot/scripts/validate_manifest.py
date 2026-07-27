from __future__ import annotations

import argparse
from pathlib import Path

from .iteration_state import ManifestStore


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate one RD-Bot Autopilot Manifest")
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args(argv)
    ManifestStore(args.manifest.parent).load()
    print(f"OK: {args.manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
