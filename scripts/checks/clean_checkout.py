#!/usr/bin/env python3
"""Test committed HEAD in a fresh clone, without ignored sources, .env or existing build output."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def main():
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip())
    output = ROOT / ".runs" / "clean-checkout" / revision
    output.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ)
    for key in ("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GOOGLE_API_KEY"):
        env.pop(key, None)
    with tempfile.TemporaryDirectory(prefix="factory-clean-checkout-") as temporary:
        clone = Path(temporary) / "repo"
        subprocess.run(["git", "clone", "--no-local", "--quiet", str(ROOT), str(clone)], check=True)
        subprocess.run(["git", "checkout", "--quiet", "--detach", revision], cwd=clone, check=True)
        print(f"Testing committed {revision}; uncommitted files excluded (source dirty={dirty})", flush=True)
        with (output / "maven.log").open("w") as log:
            result = subprocess.run(["mvn", "--batch-mode", "--no-transfer-progress", "clean", "test"],
                cwd=clone, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=900)
        totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
        for path in clone.glob("*/target/surefire-reports/TEST-*.xml"):
            suite = ET.parse(path).getroot()
            for key in totals:
                totals[key] += int(suite.attrib[key])
        passed = result.returncode == 0 and totals["tests"] > 0 and not any(totals[key] for key in ("failures", "errors", "skipped"))
        (output / "result.json").write_text(json.dumps({"revision": revision, "sourceWorkingTreeDirty": dirty,
            "checkout": "fresh git clone --no-local; ignored/uncommitted files excluded", "exitCode": result.returncode,
            "testTotals": totals, "passed": passed}, indent=2) + "\n")
        print("PASS" if passed else "FAIL", totals, "Evidence:", output, flush=True)
        raise SystemExit(0 if passed else 1)


if __name__ == "__main__":
    main()
