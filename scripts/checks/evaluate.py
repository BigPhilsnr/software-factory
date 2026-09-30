#!/usr/bin/env python3
"""Run the local evaluation suite and retain command results. Uses no model calls."""
import datetime
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def main():
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    output = ROOT / '.runs' / 'evaluation' / stamp
    output.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ, FACTORY_OPERATOR='synthetic-evaluation-test')
    commands = [
        ('unit-and-integration', ['mvn', '-q', '-Pintegration', 'test']),
        ('scenario-replay', [sys.executable, 'scripts/checks/agent_smoke.py']),
        ('operator-controls', [sys.executable, 'scripts/checks/web_smoke.py']),
        ('product-http', [sys.executable, 'scripts/checks/acceptance.py']),
    ]
    report = {
        'startedAt': stamp,
        'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'workingTreeDirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip()),
        'mode': 'Recorded fixtures and local integration; no live generation; synthetic test approvals',
        'checks': [],
    }
    passed = True
    for name, command in commands:
        print('Running:', name, flush=True)
        started = time.monotonic()
        with (output / (name + '.log')).open('w') as log:
            try:
                result = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=1200)
                code = result.returncode
            except subprocess.TimeoutExpired:
                code = 124
        report['checks'].append({'name': name, 'command': command, 'exitCode': code,
                                 'durationSeconds': round(time.monotonic()-started, 3), 'log': name+'.log'})
        (output / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
        print(('PASS' if code == 0 else 'FAIL') + ': ' + name, flush=True)
        if code:
            passed = False
            break
    reports = list(ROOT.glob('*/target/surefire-reports/TEST-*.xml'))
    totals = {k: 0 for k in ('tests','failures','errors','skipped')}
    for path in reports:
        suite = ET.parse(path).getroot()
        for key in totals: totals[key] += int(suite.attrib[key])
    report['surefireTotals'] = totals
    report['passed'] = passed and totals['failures'] == 0 and totals['errors'] == 0 and totals['skipped'] == 0
    (output / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Evidence:', output / 'results.json')
    raise SystemExit(0 if report['passed'] else 1)


if __name__ == '__main__':
    main()
