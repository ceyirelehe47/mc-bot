#!/usr/bin/env python3
"""Run Gradle JUnit directly and archive both raw output and XML aggregation."""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def aggregate(xml_root: Path) -> dict[str, int]:
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "files": 0}
    for path in sorted(xml_root.glob("TEST-*.xml")):
        root = ET.parse(path).getroot()
        totals["files"] += 1
        totals["tests"] += int(root.attrib.get("tests", "0"))
        totals["failures"] += int(root.attrib.get("failures", "0"))
        totals["errors"] += int(root.attrib.get("errors", "0"))
        totals["skipped"] += int(root.attrib.get("skipped", "0"))
    return totals


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tree", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME", ""))
    parser.add_argument("--minimum-tests", type=int, default=385)
    args = parser.parse_args()

    tree = args.tree.resolve()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    raw_log = out / "gradle-test-direct.log"

    gradlew_bat = tree / "gradlew.bat"
    gradlew_sh = tree / "gradlew"
    if os.name == "nt" and gradlew_bat.exists():
        command = [str(gradlew_bat), "test", "--rerun-tasks", "--console=plain"]
    elif gradlew_sh.exists():
        command = [str(gradlew_sh), "test", "--rerun-tasks", "--console=plain"]
    else:
        raise SystemExit(f"Gradle wrapper not found in {tree}")

    env = dict(os.environ)
    if args.java_home:
        env["JAVA_HOME"] = args.java_home
        env["PATH"] = str(Path(args.java_home) / "bin") + os.pathsep + env.get("PATH", "")

    with open(raw_log, "w", encoding="utf-8", newline="\n") as log:
        log.write("command=" + json.dumps(command, ensure_ascii=False) + "\n")
        log.write(f"cwd={tree}\n")
        log.write(f"JAVA_HOME={env.get('JAVA_HOME', '')}\n")
        log.flush()
        process = subprocess.Popen(
            command,
            cwd=tree,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            errors="replace",
        )
        assert process.stdout is not None
        for line in process.stdout:
            sys.stdout.write(line)
            log.write(line)
        return_code = process.wait()
        log.write(f"\nreturn_code={return_code}\n")

    xml_root = tree / "build" / "test-results" / "test"
    totals = aggregate(xml_root)
    summary = {
        "tree": str(tree),
        "command": command,
        "return_code": return_code,
        **totals,
    }
    (out / "junit-xml-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (out / "junit-xml-summary.txt").write_text(
        " ".join(f"{key}={value}" for key, value in summary.items()) + "\n",
        encoding="utf-8",
    )

    if return_code != 0:
        raise SystemExit(f"Gradle test failed rc={return_code}")
    if totals["tests"] < args.minimum_tests:
        raise SystemExit(f"too few tests: {totals}")
    if totals["failures"] or totals["errors"]:
        raise SystemExit(f"JUnit failures/errors: {totals}")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
