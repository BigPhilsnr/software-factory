"""Submission evidence must not report missing, skipped or malformed suites as a pass."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location('evaluate', Path(__file__).resolve().parents[1] / 'evaluate.py')
evaluate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evaluate)


class EvaluationEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)

    def suite(self, module, tests=2, failures=0, skipped=0):
        folder = self.root / module / 'target/surefire-reports'
        folder.mkdir(parents=True, exist_ok=True)
        path = folder / 'TEST-example.xml'
        path.write_text(f'<testsuite tests="{tests}" failures="{failures}" errors="0" skipped="{skipped}"/>')
        return path

    def test_missing_module_never_counts_as_success(self):
        self.suite('factory')
        self.assertFalse(evaluate.test_results(self.root)['passed'])

    def test_both_modules_must_execute_tests_without_skips_or_failures(self):
        self.suite('factory')
        self.suite('shortener')
        result = evaluate.test_results(self.root)
        self.assertTrue(result['passed'])
        self.assertEqual(4, result['totals']['tests'])
        for change in ({'tests': 0}, {'failures': 1}, {'skipped': 1}):
            with self.subTest(change=change):
                self.suite('shortener', **change)
                self.assertFalse(evaluate.test_results(self.root)['passed'])

    def test_malformed_report_cannot_be_silently_ignored(self):
        self.suite('factory').write_text('incomplete XML')
        with self.assertRaises(ET.ParseError):
            evaluate.test_results(self.root)


if __name__ == '__main__':
    unittest.main()
