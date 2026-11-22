#!/usr/bin/env python3
"""Checks that R8 kept what is reached by name at run time.

Nothing runs the minified build before it ships, so a class or a constructor
that only reflection reaches can vanish unseen. In OpenVitals R8 once stripped
the no-arg constructor of a widget callback, and every widget tap was dead
for a release.

The manifest components are checked against mapping.txt: Android makes them
by name, with no arguments. So are the @Serializable types, the navigation
routes: kotlinx-serialization finds a class's serializer by reflection, through
its Companion field and the companion's serializer(), or an object's INSTANCE
and its serializer(). R8 may rename the classes, since a route is named by the
serial name the compiler wrote, but not those members. (OpenVitals' script also
checks its widget callbacks and the record names its phone sync puts on the
wire; OpenWatch has neither.)

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
SERIALIZER_METHOD = re.compile(r"\bkotlinx\.serialization\.KSerializer serializer\(\)(?::\d+(?::\d+)?)? -> serializer$")
# A top-level declaration marked @Serializable, with the annotation on the line before.
SERIALIZABLE_DECLARATION = re.compile(
    r"^@Serializable\s*\n(?:(?:data|sealed|internal|public)\s+)*(object|class|interface)\s+(\w+)",
    re.MULTILINE,
)
PACKAGE_LINE = re.compile(r"^package\s+([\w.]+)", re.MULTILINE)


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


def serializable_types(sources: Path) -> dict[str, str]:
    """Each top-level @Serializable type under [sources], by binary name: "object" or "class"."""
    types = {}
    for path in sorted(sources.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        if "@Serializable" not in text:
            continue
        package = PACKAGE_LINE.search(text)
        prefix = package.group(1) + "." if package else ""
        for kind, name in SERIALIZABLE_DECLARATION.findall(text):
            if kind != "interface":
                types[prefix + name] = kind
    return types


def read_mapping(mapping: Path, wanted: set[str]) -> dict[str, dict]:
    """
    One pass: the file runs past 100 MB. Returns, per wanted class, its new name, whether <init>()
    survived, and the serialization members it kept under their own names.
    """
    found: dict[str, dict] = {}
    current = None
    with mapping.open(encoding="utf-8", errors="replace") as lines:
        for line in lines:
            line = line.rstrip("\n")
            if not line.startswith((" ", "#")):
                match = CLASS_LINE.match(line)
                current = None
                if match and match.group(1) in wanted:
                    current = found.setdefault(
                        match.group(1),
                        {"renamed_to": match.group(2), "init": False, "members": set()},
                    )
            elif current is not None:
                if NO_ARG_CONSTRUCTOR.search(line):
                    current["init"] = True
                elif SERIALIZER_METHOD.search(line):
                    current["members"].add("serializer()")
                elif line.endswith(" Companion -> Companion"):
                    current["members"].add("Companion")
                elif line.endswith(" INSTANCE -> INSTANCE"):
                    current["members"].add("INSTANCE")
    return found


def serializable_problems(types: dict[str, str], found: dict[str, dict]) -> list[str]:
    """What R8 took from each @Serializable type that its serializer lookup needs."""
    problems = []
    for name, kind in sorted(types.items()):
        if kind == "object":
            needs = {name: {"INSTANCE", "serializer()"}}
        else:
            needs = {name: {"Companion"}, name + "$Companion": {"serializer()"}}
        for owner, members in needs.items():
            entry = found.get(owner)
            if entry is None:
                problems.append(f"{owner}: removed by R8, so its serializer cannot be found")
                continue
            for member in sorted(members - entry["members"]):
                problems.append(f"{owner}: {member} removed or renamed, so its serializer cannot be found")
    return problems


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

    serializable = serializable_types(args.sources)
    # The routes are @Serializable; finding none means the scan broke, not that none exist.
    if not serializable:
        print("verify-r8-keeps: found no @Serializable types; the scan is broken", file=sys.stderr)
        return 1
    wanted = components | set(serializable) | {
        name + "$Companion" for name, kind in serializable.items() if kind == "class"
    }

    found = read_mapping(args.mapping, wanted)
    problems = []
    for name in sorted(components):
        entry = found.get(name)
        if entry is None:
            problems.append(f"{name}: removed by R8")
        elif entry["renamed_to"] != name:
            problems.append(f"{name}: renamed to {entry['renamed_to']}")
        elif not entry["init"]:
            problems.append(f"{name}: its no-arg constructor was removed, so it cannot be made by name")
    problems.extend(serializable_problems(serializable, found))

    if problems:
        print("verify-r8-keeps: R8 removed or renamed what is reached by name:", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        print("Add a keep rule to app/proguard-rules.pro, with its reason.", file=sys.stderr)
        return 1

    print(
        f"verify-r8-keeps: {len(components)} manifest components and "
        f"{len(serializable)} @Serializable types' serializers kept."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
