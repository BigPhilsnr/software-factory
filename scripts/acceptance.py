#!/usr/bin/env python3
"""Independent HTTP contract check against a running candidate service."""

import json
import os
import time
import urllib.error
import urllib.request
from uuid import uuid4


BASE = os.environ.get("SHORTENER_BASE_URL", "http://localhost:8080").rstrip("/")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


OPENER = urllib.request.build_opener(NoRedirect)


def call(path, *, payload=None):
    body = None if payload is None else json.dumps(payload).encode()
    request = urllib.request.Request(
        BASE + path,
        data=body,
        headers={"Content-Type": "application/json"} if body is not None else {},
    )
    try:
        with OPENER.open(request, timeout=5) as response:
            return response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), error.read()


def main():
    target = "https://example.com/a?q=1"
    status, _, data = call("/api/shorten", payload={"url": target})
    assert status == 201, (status, data)
    code = json.loads(data)["code"]
    assert len(code) == 8 and code.isalnum(), code

    status, headers, _ = call("/" + code)
    assert status == 302 and headers["Location"] == target, (status, headers)
    for _ in range(40):
        status, _, data = call("/api/urls/" + code + "/analytics")
        if status == 200 and json.loads(data)["redirectCount"] >= 1:
            break
        time.sleep(0.05)
    else:
        raise AssertionError((status, data))

    alias = "alias-" + uuid4().hex[:10]
    status, _, data = call("/api/shorten", payload={"url": target, "alias": alias.upper()})
    assert status == 201 and json.loads(data)["code"] == alias, (status, data)
    status, _, _ = call("/api/shorten", payload={"url": target, "alias": alias})
    assert status == 409, status
    status, _, _ = call("/api/shorten", payload={"url": "file:///etc/passwd"})
    assert status == 400, status
    status, _, _ = call("/missing8")
    assert status == 404, status
    print("PASS: create, redirect, analytics, alias conflict, invalid URL, unknown code")


if __name__ == "__main__":
    main()
