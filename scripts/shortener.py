#!/usr/bin/env python3
"""Run the product with local database configuration, without passing model credentials."""
import subprocess
import re
from pathlib import Path
from factory_cli import environment, ROOT

values = environment()
java = str(Path(values["JAVA_HOME"]) / "bin/java") if values.get("JAVA_HOME") else "java"
version = subprocess.run([java, "-version"], capture_output=True, text=True).stderr
major = re.search(r'version "(\d+)', version)
if not major or int(major.group(1)) < 21:
    if Path("/opt/homebrew/opt/openjdk@21").is_dir():
        values["JAVA_HOME"] = "/opt/homebrew/opt/openjdk@21"
    else:
        raise SystemExit("Set JAVA_HOME to a JDK 21 installation before starting the shortener")
for name in ("ANTHROPIC_API_KEY", "CLAUDE_MODEL", "FACTORY_MAX_MODEL_CALLS", "FACTORY_CHAT_DAILY_REQUESTS"):
    values.pop(name, None)
raise SystemExit(subprocess.call(["mvn", "-q", "-f", "shortener/pom.xml", "spring-boot:run"], cwd=ROOT, env=values))
