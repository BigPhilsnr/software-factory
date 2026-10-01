#!/usr/bin/env python3
"""Run the product with local database configuration, without passing model credentials."""
import subprocess
from factory_cli import environment, ROOT

values = environment()
for name in ("ANTHROPIC_API_KEY", "CLAUDE_MODEL", "FACTORY_MAX_MODEL_CALLS", "FACTORY_CHAT_DAILY_REQUESTS"):
    values.pop(name, None)
raise SystemExit(subprocess.call(["mvn", "-q", "-f", "shortener/pom.xml", "spring-boot:run"], cwd=ROOT, env=values))
