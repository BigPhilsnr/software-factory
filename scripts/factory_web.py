#!/usr/bin/env python3
"""Start the local ADK UI and factory operator page with the ignored .env configuration."""
import os
import re
import subprocess
from pathlib import Path
from factory_cli import environment, ROOT

env = environment()
configured_java = str(Path(env['JAVA_HOME']) / 'bin/java') if env.get('JAVA_HOME') else 'java'
try:
    version = subprocess.run([configured_java, '-version'], capture_output=True, text=True).stderr
except FileNotFoundError:
    version = ''  # No usable java; fall back to a known JDK 21 location or explain below.
major = re.search(r'version \"(\d+)', version)
if not major or int(major.group(1)) < 21:
    if Path('/opt/homebrew/opt/openjdk@21').is_dir():
        env['JAVA_HOME'] = '/opt/homebrew/opt/openjdk@21'
    else:
        raise SystemExit('Set JAVA_HOME to a JDK 21 installation before starting the UI')
try:
    subprocess.run(['mvn', '-q', '-f', 'factory/pom.xml', '-DskipTests', 'compile',
                    'dependency:build-classpath', '-Dmdep.outputFile=target/web-classpath.txt'], cwd=ROOT, env=env, check=True)
except FileNotFoundError:
    raise SystemExit('Maven (mvn) was not found on PATH. Install Maven 3.9+ and retry.') from None
except subprocess.CalledProcessError as failure:
    raise SystemExit(f'Building the factory failed (exit {failure.returncode}); see the Maven output above.') from None
classpath = str(ROOT / 'factory/target/classes') + os.pathsep + (ROOT / 'factory/target/web-classpath.txt').read_text().strip()
java = str(Path(env['JAVA_HOME']) / 'bin/java') if env.get('JAVA_HOME') else 'java'
os.chdir(ROOT)
try:
    os.execvpe(java, [java, '-cp', classpath, 'dev.softwarefactory.operator.web.FactoryWebServer'], env)
except FileNotFoundError:
    raise SystemExit('Java was not found. Set JAVA_HOME to a JDK 21 installation before starting the UI.') from None
