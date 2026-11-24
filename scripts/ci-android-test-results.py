#!/usr/bin/env python3
"""Reads the instrumented test results a connected test task wrote, and fails unless tests ran.

A connected task that finds no test passes, and so does verifyAndroidTest with no
ANDROID_SERIAL, which is then disabled: green, with nothing run. This counts the JUnit XML
under every module's build/outputs/androidTest-results/connected/ and fails when none was
written, when no test ran, or when one failed. It names each skipped test (an assumption
that did not hold) and each failed one, in the log and, on GitHub, in the job summary.

scripts/ci-android-test.sh runs it after the Gradle task, with --newer naming a file it
touched first, so a result left by an earlier run is not read as this one's.

Exit 0 when tests ran and none failed, 1 otherwise.
"""

import argparse
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RESULTS = Path("build") / "outputs" / "androidTest-results" / "connected"
SKIP_DIRS = {".git", ".gradle", ".gradle-ci", ".gradle-release", "node_modules"}


def result_files(root, newer_ns):
    for directory, subdirs, files in os.walk(root):
        subdirs[:] = [d for d in subdirs if d not in SKIP_DIRS]
        here = Path(directory)
        if RESULTS.as_posix() not in here.as_posix():
            continue
        for name in files:
            path = here / name
            if name.startswith("TEST-") and name.endswith(".xml") and path.stat().st_mtime_ns > newer_ns:
                yield path


def test_cases(path):
    root = ET.parse(path).getroot()
    suites = [root] if root.tag == "testsuite" else root.iter("testsuite")
    for suite in suites:
        for case in suite.iter("testcase"):
            name = f"{case.get('classname', '?')}.{case.get('name', '?')}"
            if case.find("failure") is not None or case.find("error") is not None:
                yield name, "failed"
            elif case.find("skipped") is not None:
                yield name, "skipped"
            else:
                yield name, "passed"


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=".", help="the repository root (default: the current directory)")
    parser.add_argument("--newer", help="count only result files written after this file")
    args = parser.parse_args()

    newer_ns = Path(args.newer).stat().st_mtime_ns if args.newer else -1
    files = sorted(result_files(Path(args.root), newer_ns))
    lines = []
    totals = {"passed": 0, "skipped": 0, "failed": 0}
    named = {"skipped": [], "failed": []}
    for path in files:
        counts = {"passed": 0, "skipped": 0, "failed": 0}
        for name, outcome in test_cases(path):
            counts[outcome] += 1
            if outcome in named:
                named[outcome].append(name)
        for outcome, count in counts.items():
            totals[outcome] += count
        lines.append(f"{path}: {counts['passed']} passed, {counts['skipped']} skipped, {counts['failed']} failed")

    problems = []
    if not files:
        problems.append(f"no test results under */{RESULTS}: the task ran no instrumented test")
    elif totals["passed"] + totals["failed"] == 0:
        problems.append("every test was skipped: nothing ran")
    if totals["failed"]:
        problems.append(f"{totals['failed']} test(s) failed")

    for line in lines:
        print(line)
    print(f"instrumented tests: {totals['passed']} passed, {totals['skipped']} skipped, {totals['failed']} failed")
    for outcome in ("skipped", "failed"):
        for name in named[outcome]:
            print(f"  {outcome}: {name}")
    for problem in problems:
        print(f"::error::{problem}" if os.environ.get("GITHUB_ACTIONS") else problem, file=sys.stderr)

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write("### Instrumented tests\n\n")
            out.write(f"{totals['passed']} passed, {totals['skipped']} skipped, {totals['failed']} failed.\n\n")
            for outcome in ("skipped", "failed"):
                for name in named[outcome]:
                    out.write(f"- {outcome}: `{name}`\n")
            for problem in problems:
                out.write(f"- **{problem}**\n")
            out.write("\n")

    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
