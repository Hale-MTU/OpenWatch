#!/usr/bin/env python3
"""Fails when a version this build shares with a submodule's build differs between them.

openlink/ and health-calculations/ are composite builds with catalogs of their own, resolved
inside this build. A Kotlin, AGP or coroutines version that drifted between the three surfaces
as a plugin conflict or a silent upgrade; this names it instead. Only the versions a catalog
shares with this one by key are compared.

    python3 scripts/ci-catalog-alignment.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SHARED = ("agp", "kotlin", "coroutines", "junit4", "ksp")
SUBMODULES = ("openlink", "health-calculations")


def versions(catalog: Path) -> dict[str, str]:
    text = catalog.read_text(encoding="utf-8")
    block = re.search(r"^\[versions\]\n(.*?)(?=^\[|\Z)", text, re.S | re.M)
    pairs = re.findall(r'^([\w-]+)\s*=\s*"([^"]+)"', block.group(1) if block else "", re.M)
    return dict(pairs)


def main() -> int:
    own = versions(ROOT / "gradle/libs.versions.toml")
    failed = False
    for module in SUBMODULES:
        catalog = ROOT / module / "gradle/libs.versions.toml"
        if not catalog.is_file():
            print(f"catalogs: {module}/ is not checked out (git submodule update --init)")
            failed = True
            continue
        theirs = versions(catalog)
        for key in SHARED:
            if key in own and key in theirs and own[key] != theirs[key]:
                print(f"catalogs: {key} is {own[key]} here and {theirs[key]} in {module}/")
                failed = True
    if not failed:
        print("catalogs: the shared versions agree")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
