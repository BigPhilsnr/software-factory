#!/usr/bin/env python3
"""Launch the Java factory CLI, loading a local .env as data without echoing secrets."""

import os
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ALLOWED = {"ANTHROPIC_API_KEY", "CLAUDE_MODEL", "FACTORY_MAX_MODEL_CALLS"}


def environment():
    values = dict(os.environ)
    path = ROOT / ".env"
    if path.is_file():
        for number, raw in enumerate(path.read_text().splitlines(), start=1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            name, separator, value = line.partition("=")
            if not separator or name.strip() not in ALLOWED:
                raise ValueError(f"Invalid .env entry on line {number}")
            if not values.get(name.strip()):
                values[name.strip()] = value.strip().strip('"').strip("'")
    return values


def main():
    if len(sys.argv) < 2:
        raise SystemExit("Usage: python3 scripts/factory_cli.py <factory command and arguments>")
    values = environment()
    key = values.get("ANTHROPIC_API_KEY", "")
    if "live" in sys.argv[1:] and (not key or key.startswith("replace-")):
        raise SystemExit("Set ANTHROPIC_API_KEY in .env before starting a live run")
    command = ["mvn", "-q", "-f", "factory/pom.xml", "exec:java", "-Dexec.args=" + " ".join(sys.argv[1:])]
    raise SystemExit(subprocess.call(command, cwd=ROOT, env=values))


if __name__ == "__main__":
    main()
