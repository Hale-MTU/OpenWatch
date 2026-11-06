#!/usr/bin/env python3
"""Copies the string resources OpenWatch's code references out of an OpenVitals checkout.

OpenWatch's screens are OpenVitals' device screens, so their strings and the
fourteen translations are taken from there rather than retyped. This script
collects every R.string / R.plurals key the Kotlin sources, the manifest and
the non-string resources reference, then rewrites each values*/strings.xml of
this repo so it holds exactly those keys, in the order the source file has
them, plus the keys only OpenWatch defines (kept from the existing file).

    python3 -I scripts/extract-strings.py --source /path/to/openvitals/android-app

Rerun after copying more code from OpenVitals. Keys the source lacks are reported.
"""
import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP_RES = os.path.join(ROOT, "app", "src", "main", "res")
KEY_RE = re.compile(r"R\.(?:string|plurals)\.(\w+)")
XML_KEY_RE = re.compile(r"@(?:string|plurals)/(\w+)")
TOOLS_NS = "http://schemas.android.com/tools"
XLIFF_NS = "urn:oasis:names:tc:xliff:document:1.2"
ET.register_namespace("tools", TOOLS_NS)
ET.register_namespace("xliff", XLIFF_NS)


def referenced_keys():
    keys = set()
    for base in (os.path.join(ROOT, "app", "src", "main"),):
        for dirpath, _, files in os.walk(base):
            for name in files:
                path = os.path.join(dirpath, name)
                if name.endswith(".kt") or (name.endswith(".xml") and "values" not in dirpath):
                    with open(path, encoding="utf-8") as fh:
                        text = fh.read()
                    keys.update(KEY_RE.findall(text))
                    keys.update(XML_KEY_RE.findall(text))
    return keys


def parse(path):
    """Keeps the source's element order and attributes; comments are dropped."""
    tree = ET.parse(path)
    return tree.getroot()


def rename_product(element):
    """A copied string names the app it is in. The keys OpenWatch adds itself are left alone."""
    for node in element.iter():
        if node.text and "OpenVitals" in node.text:
            node.text = node.text.replace("OpenVitals", "OpenWatch")
        if node.tail and "OpenVitals" in node.tail:
            node.tail = node.tail.replace("OpenVitals", "OpenWatch")
    return element


def serialize(root):
    body = []
    for element in root:
        element.tail = None
        text = ET.tostring(element, encoding="unicode")
        body.append("    " + text.strip())
    return '<?xml version="1.0" encoding="utf-8"?>\n<resources xmlns:tools="%s">\n%s\n</resources>\n' % (
        TOOLS_NS,
        "\n".join(body),
    )


def own_keys(path, taken):
    """Keys this repo's file already has that the source does not: OpenWatch's own strings."""
    if not os.path.exists(path):
        return []
    return [element for element in parse(path) if element.get("name") not in taken]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, help="an OpenVitals android-app checkout")
    args = parser.parse_args()
    source_res = os.path.join(args.source, "app", "src", "main", "res")
    keys = referenced_keys()
    base = parse(os.path.join(source_res, "values", "strings.xml"))
    source_keys = {element.get("name") for element in base}
    missing = sorted(k for k in keys if k not in source_keys)
    folders = sorted(
        name for name in os.listdir(source_res) if name == "values" or name.startswith("values-")
    )
    for folder in folders:
        source_file = os.path.join(source_res, folder, "strings.xml")
        if not os.path.exists(source_file):
            continue
        target_dir = os.path.join(APP_RES, folder)
        target_file = os.path.join(target_dir, "strings.xml")
        root = parse(source_file)
        kept = [rename_product(element) for element in root if element.get("name") in keys]
        own = own_keys(target_file, source_keys)
        os.makedirs(target_dir, exist_ok=True)
        new_root = ET.Element("resources")
        for element in own + kept:
            new_root.append(element)
        with open(target_file, "w", encoding="utf-8") as fh:
            fh.write(serialize(new_root))
        print("%s: %d copied, %d own" % (folder, len(kept), len(own)))
    if missing:
        print("Referenced keys the source does not define (OpenWatch's own, or typos):", file=sys.stderr)
        for key in missing:
            print("  " + key, file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
