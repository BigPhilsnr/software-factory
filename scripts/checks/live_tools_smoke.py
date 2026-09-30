#!/usr/bin/env python3
"""Exercise real tools through native ADK chat. Explicit --live incurs provider charges."""
import argparse
import json
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import Request, urlopen

BASE = "http://localhost:8000"


def request(path, body=None):
    data = None if body is None else json.dumps(body).encode()
    with urlopen(Request(BASE + path, data=data, headers={"Content-Type": "application/json"}), timeout=480) as response:
        return response.read().decode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true", help="Authorize paid chat/search calls")
    args = parser.parse_args()
    if not args.live:
        parser.error("Pass --live to run paid provider checks; Maven tests use a local fake provider.")
    before = {run["id"] for run in json.loads(request("/factory/api/runs")) if run["mode"] == "live"}
    session = str(uuid.uuid4())
    request(f"/apps/software_factory/users/tools-smoke/sessions/{session}", {})
    cases = [
        ("Use list_files for factory/src/main/java/dev/softwarefactory/agents/tools, read_file for the first 30 lines of factory/README.md, "
         "search_repository for MAX_MODEL_REQUESTS, inspect_git for status, and current_time. Summarize the observed facts in under 80 words.",
         ["list_files OK", "read_file OK", "search_repository OK", "inspect_git OK", "current_time OK"]),
        ("Use fetch_page to read https://example.com and summarize its visible text in one sentence with the source URL.", ["fetch_page OK", "https://example.com"]),
        ("Use search_web to find official Google ADK Java documentation about function tools. Give one source link and a two-sentence answer.", ["search_web OK", "https://"]),
    ]
    for question, expected in cases:
        raw = request("/run_sse", {"appName": "software_factory", "userId": "tools-smoke", "sessionId": session,
            "newMessage": {"role": "user", "parts": [{"text": question}]}, "streaming": False})
        texts = []
        for line in raw.splitlines():
            if line.startswith("data:"):
                event = json.loads(line[5:])
                texts.extend(part.get("text", "") for part in event.get("content", {}).get("parts", []))
        answer = "\n".join(texts)
        assert "request failed" not in answer and "Action not performed" not in answer, answer
        for value in expected:
            assert value in answer, f"Missing {value}: {answer}"
        print("PASS", ", ".join(expected), flush=True)
    after = {run["id"] for run in json.loads(request("/factory/api/runs")) if run["mode"] == "live"}
    assert not after - before, "A new live workflow appeared during read-only tool chat"
    report = Path(__file__).resolve().parents[2] / ".runs" / "tool-checks" / f"{session}.json"
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps({"at": datetime.now(timezone.utc).isoformat(), "session": session,
        "checks": [expected for _, expected in cases], "newLiveRuns": [], "passed": True}, indent=2) + "\n")
    print("PASS all seven tools through native ADK chat; no live workflow created. Evidence:", report, flush=True)


if __name__ == "__main__":
    main()
