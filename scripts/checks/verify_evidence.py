#!/usr/bin/env python3
"""Verify the curated submission snapshot. Hashes detect edits, not independent authenticity."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / "docs" / "evaluation" / "samples"


def main():
    manifest = json.loads((ROOT / "manifest.json").read_text())
    entries = manifest["files"]
    assert entries, "Evidence manifest is empty"
    for name, expected in entries.items():
        path = (ROOT / name).resolve()
        assert path.is_relative_to(ROOT.resolve()), "Manifest path escapes evidence folder"
        assert path.is_file() and not (ROOT / name).is_symlink(), f"Missing regular evidence file: {name}"
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        assert actual == expected["exportSha256"], f"Evidence changed: {name}"
    actual_files = {str(path.relative_to(ROOT)) for path in ROOT.rglob("*") if path.is_file() and path.name != "manifest.json"}
    assert actual_files == set(entries), "Manifest does not cover exactly the evidence files"
    live = json.loads((ROOT / "live-bugfix" / "run.json").read_text())
    for task, digest in live["artifactHashes"].items():
        name = f"live-bugfix/{task}-v{live['artifactVersions'][task]}.txt"
        assert entries[name]["originalSha256"] == digest, f"Original artifact digest differs from persisted run: {task}"
    print(f"PASS {len(entries)} curated evidence files; exported SHA-256 checksums match")


if __name__ == "__main__":
    main()
