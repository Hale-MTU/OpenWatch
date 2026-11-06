#!/usr/bin/env python3
"""Checks that R8 kept what is reached by name at run time.

Nothing runs the minified build before it ships, so a class or a constructor
that only reflection reaches can vanish unseen. In OpenVitals R8 once stripped
the no-arg constructor of a widget callback, and every widget tap was dead
for a release.

The manifest components are checked against mapping.txt: Android makes them
by name, with no arguments. (OpenVitals' script also checks its widget
callbacks and the record names its phone sync puts on the wire; OpenWatch has
neither.)

Exit 0 when all hold, 1 with one line per problem.
"""

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "{http://schemas.android.com/apk/res/android}"
COMPONENT_TAGS = ("application", "activity", "service", "receiver", "provider")
APP_PACKAGE = "tech.mmarca.openwatch"

CLASS_LINE = re.compile(r"^(\S+) -> (\S+):$")
NO_ARG_CONSTRUCTOR = re.compile(r"\bvoid <init>\(\)(?::\d+(?::\d+)?)? -> <init>$")


def manifest_components(manifest: Path) -> set[str]:
    root = ET.parse(manifest).getroot()
    package = root.get("package", APP_PACKAGE)
    names = set()
    for element in root.iter():
        if element.tag in COMPONENT_TAGS:
            name = element.get(ANDROID + "name")
        elif element.tag == "activity-alias":
            name = element.get(ANDROID + "targetActivity")
        else:
            continue
        if not name:
            continue
        if name.startswith("."):
            name = package + name
        # Library components ship their own keep rules. This guards ours.
        if name.startswith(APP_PACKAGE):
            names.add(name)
    return names


def read_mapping(mapping: Path, wanted: set[str]) -> dict[str, dict]:
    """One pass: the file runs past 100 MB. Returns, per wanted class, its new name and whether <init>() survived."""
    found: dict[str, dict] = {}
    current = None
    with mapping.open(encoding="utf-8", errors="replace") as lines:
        for line in lines:
            if not line.startswith((" ", "#")):
                match = CLASS_LINE.match(line.rstrip("\n"))
                current = None
                if match and match.group(1) in wanted:
                    current = found.setdefault(match.group(1), {"renamed_to": match.group(2), "init": False})
            elif current is not None and NO_ARG_CONSTRUCTOR.search(line.rstrip("\n")):
                current["init"] = True
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--mapping", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--sources", type=Path, required=True)
    args = parser.parse_args()

    for path in (args.mapping, args.manifest, args.sources):
        if not path.exists():
            print(f"verify-r8-keeps: {path} does not exist", file=sys.stderr)
            return 1

    components = manifest_components(args.manifest)
    # A check that finds nothing to check has stopped checking.
    if not components:
        print("verify-r8-keeps: found no manifest components; the scan is broken", file=sys.stderr)
        return 1

    found = read_mapping(args.mapping, components)
    problems = []
    for name in sorted(components):
        entry = found.get(name)
        if entry is None:
            problems.append(f"{name}: removed by R8")
        elif entry["renamed_to"] != name:
            problems.append(f"{name}: renamed to {entry['renamed_to']}")
        elif not entry["init"]:
            problems.append(f"{name}: its no-arg constructor was removed, so it cannot be made by name")

    if problems:
        print("verify-r8-keeps: R8 removed or renamed what is reached by name:", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        print("Add a keep rule to app/proguard-rules.pro, with its reason.", file=sys.stderr)
        return 1

    print(f"verify-r8-keeps: {len(components)} manifest components kept.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
