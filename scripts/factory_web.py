#!/usr/bin/env python3
"""Start the local ADK UI and factory operator page with the ignored .env configuration."""
import os
import re
import subprocess
from pathlib import Path
from factory_cli import environment, ROOT

env = environment()
configured_java = str(Path(env['JAVA_HOME']) / 'bin/java') if env.get('JAVA_HOME') else 'java'
version = subprocess.run([configured_java, '-version'], capture_output=True, text=True).stderr
major = re.search(r'version \"(\d+)', version)
if not major or int(major.group(1)) < 21:
    if Path('/opt/homebrew/opt/openjdk@21').is_dir():
        env['JAVA_HOME'] = '/opt/homebrew/opt/openjdk@21'
    else:
        raise SystemExit('Set JAVA_HOME to a JDK 21 installation before starting the UI')
subprocess.run(['mvn', '-q', '-f', 'orchestrator/pom.xml', '-DskipTests', 'compile',
                'dependency:build-classpath', '-Dmdep.outputFile=target/web-classpath.txt'], cwd=ROOT, env=env, check=True)
classpath = str(ROOT / 'orchestrator/target/classes') + os.pathsep + (ROOT / 'orchestrator/target/web-classpath.txt').read_text().strip()
java = str(Path(env['JAVA_HOME']) / 'bin/java') if env.get('JAVA_HOME') else 'java'
os.chdir(ROOT)
os.execvpe(java, [java, '-cp', classpath, 'com.example.factory.web.FactoryWebServer'], env)
