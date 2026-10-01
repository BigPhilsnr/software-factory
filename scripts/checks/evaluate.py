#!/usr/bin/env python3
"""Reproduce the local quality gate and fixture demonstrations; never invoke a model."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MODULES = ('shortener', 'factory')


def source_manifest(root):
    """Hash code/config/scenarios, including local edits; never copy credentials into evidence."""
    names = subprocess.check_output(
        ['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=root).decode().split('\0')
    selected = {}
    for name in sorted(set(names)):
        path = root / name
        if not name or not path.is_file() or path.is_symlink():
            continue
        if not (name.startswith(('factory/', 'shortener/', 'scenarios/', 'scripts/', 'build-config/', '.github/'))
                or name in ('pom.xml', 'compose.yaml')):
            continue
        selected[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    digest = hashlib.sha256(json.dumps(selected, sort_keys=True).encode()).hexdigest()
    return {'sha256': digest, 'files': selected}


def test_results(root):
    modules = {}
    totals = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    for module in MODULES:
        counts = dict.fromkeys(totals, 0)
        reports = list((root / module / 'target/surefire-reports').glob('TEST-*.xml'))
        for path in reports:
            suite = ET.parse(path).getroot()
            for key in counts:
                counts[key] += int(suite.attrib[key])
        modules[module] = {'suites': len(reports), **counts}
        coverage = root / module / 'target/site/jacoco/jacoco.xml'
        if coverage.exists():
            modules[module]['coverage'] = {
                node.attrib['type']: {key: int(node.attrib[key]) for key in ('covered', 'missed')}
                for node in ET.parse(coverage).getroot().findall('counter')
                if node.attrib['type'] in ('LINE', 'BRANCH')}
        for key in totals:
            totals[key] += counts[key]
    passed = all(module['tests'] > 0 and not any(module[key] for key in ('failures', 'errors', 'skipped'))
                 for module in modules.values())
    return {'modules': modules, 'totals': totals, 'passed': passed}


def run_check(command, log, env):
    """A timed-out Maven check must not leave its child JVM running."""
    with log.open('w') as stream:
        try:
            with subprocess.Popen(command, cwd=ROOT, env=env, stdout=stream,
                                  stderr=subprocess.STDOUT, start_new_session=True) as process:
                try:
                    return process.wait(timeout=1200)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
                    stream.write('\nEvaluation timeout after 1200 seconds.\n')
                    return 124
        except OSError as error:
            stream.write(f'Cannot start command: {error}\n')
            return 127


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--browser', action='store_true', help='Also run Chrome operator and ADK checks (requires Node and puppeteer-core)')
    arguments = parser.parse_args()
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    output = ROOT / '.runs' / 'evaluation' / stamp
    output.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ, FACTORY_OPERATOR='synthetic-evaluation-test')
    for name in ('ANTHROPIC_API_KEY', 'OPENAI_API_KEY', 'GOOGLE_API_KEY'):
        env.pop(name, None)
    commands = [
        ('check-script-tests', [sys.executable, '-m', 'unittest', 'discover', '-s', 'scripts/checks/tests']),
        ('quality-and-integration', ['mvn', '-q', '-Pintegration', 'clean', 'verify']),
        ('recorded-evidence-integrity', [sys.executable, 'scripts/checks/verify_evidence.py']),
        ('scenario-replay', [sys.executable, 'scripts/checks/agent_smoke.py']),
        ('operator-controls', [sys.executable, 'scripts/checks/web_smoke.py']),
        ('product-http', [sys.executable, 'scripts/checks/acceptance.py']),
    ]
    if arguments.browser:
        commands.extend([
            ('operator-browser', ['node', 'scripts/checks/browser_smoke.cjs']),
            ('adk-browser', ['node', 'scripts/checks/adk_chat_smoke.cjs']),
        ])
    report = {
        'startedAt': stamp,
        'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'workingTreeDirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip()),
        'source': source_manifest(ROOT),
        'mode': 'Recorded fixtures and local integration; no live generation; synthetic test approvals',
        'limitations': [
            'Fixture success does not establish model output quality.',
            'HTTP smoke checks exercise the already running processes; Maven integration tests start current-code servers.',
            'Browser checks run only with --browser.',
        ],
        'checks': [],
    }
    passed = True
    for name, command in commands:
        print('Running:', name, flush=True)
        started = time.monotonic()
        code = run_check(command, output / (name + '.log'), env)
        report['checks'].append({'name': name, 'command': command, 'exitCode': code,
                                'durationSeconds': round(time.monotonic() - started, 3), 'log': name + '.log'})
        (output / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
        print(('PASS' if code == 0 else 'FAIL') + ': ' + name, flush=True)
        if code:
            passed = False
            break
    try:
        report['testResults'] = test_results(ROOT)
    except (ET.ParseError, ValueError, KeyError) as error:
        report['testResults'] = {'passed': False, 'error': str(error)}
    report['sourceUnchangedDuringEvaluation'] = report['source'] == source_manifest(ROOT)
    report['passed'] = passed and report['testResults']['passed'] and report['sourceUnchangedDuringEvaluation']
    report['finishedAt'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    (output / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Evidence:', output / 'results.json', flush=True)
    raise SystemExit(0 if report['passed'] else 1)


if __name__ == '__main__':
    main()
