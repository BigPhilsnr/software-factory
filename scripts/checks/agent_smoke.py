#!/usr/bin/env python3
"""Replay the control plane with synthetic operator decisions and real sandbox tests."""

import json
import os
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ENV = dict(os.environ, FACTORY_OPERATOR="synthetic-fixture-test")


def cli(*arguments):
    command = [
        "mvn", "-q", "-f", "factory/pom.xml", "exec:java",
        "-Dexec.args=" + " ".join(arguments),
    ]
    result = subprocess.run(command, cwd=ROOT, env=ENV, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f"{' '.join(command)} failed:\n{result.stderr}\n{result.stdout}")
    output = result.stdout.strip()
    return output if arguments[0] == "verify-audit" else json.loads(output)


def successful_replay(scenario):
    state = cli("start", f"scenarios/{scenario}/scenario.json", "fixture")
    run_id = state["id"]
    for _ in range(20):
        state = cli("advance", run_id)
        if state["status"] == "COMPLETED":
            assert cli("verify-audit", run_id) == "AUDIT_VALID"
            print(f"PASS {scenario}: COMPLETED, run={run_id}", flush=True)
            return
        if state["status"] != "PAUSED":
            raise AssertionError(f"{scenario} ended unexpectedly: {state['status']}")
        if state["pendingClarificationTask"]:
            assert scenario == "ambiguous"
            cli("clarify", run_id, "Fixture-only assumption: one instance, immutable links, 60-second cache, 100 redirects per second target, 200 ms p95 target.")
        elif state["pendingApprovalTask"]:
            review = cli("review", run_id)
            assert review["reviewedHash"] == state["pendingApprovalHash"]
            if review["proposal"] != "none":
                assert Path(review["proposal"]).is_file()
            cli("approve", run_id, review["reviewedHash"])
        else:
            raise AssertionError(f"{scenario} paused without an operator action")
    raise AssertionError(f"{scenario} exceeded 20 transitions")


def negative_replay(scenario, expected):
    state = cli("start", f"scenarios/{scenario}/scenario.json", "fixture")
    run_id = state["id"]
    for _ in range(3):
        state = cli("advance", run_id)
        if state["status"] == expected:
            assert cli("verify-audit", run_id) == "AUDIT_VALID"
            print(f"PASS {scenario}: {expected}, run={run_id}", flush=True)
            return
    raise AssertionError(f"{scenario} did not reach {expected}: {state['status']}")


def main():
    for scenario in ("greenfield", "brownfield", "ambiguous", "bugfix"):
        successful_replay(scenario)
    negative_replay("policy-violation", "SAFE_STOPPED")
    negative_replay("retry-exhaustion", "FAILED")


if __name__ == "__main__":
    main()
