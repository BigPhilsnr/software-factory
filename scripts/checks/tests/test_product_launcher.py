"""The product process must not inherit control-plane credentials."""
from pathlib import Path
import runpy
from types import SimpleNamespace
import unittest
from unittest.mock import patch


class ProductLauncherTest(unittest.TestCase):
    def test_model_audit_and_control_database_credentials_never_reach_maven(self):
        root = Path(__file__).resolve().parents[3]
        settings = {
            'JAVA_HOME': '/test/jdk', 'PATH': '/test/bin',
            'SHORTENER_DB_PASSWORD': 'product-test-value',
            'ANTHROPIC_API_KEY': 'provider-test-value', 'OPENAI_API_KEY': 'other-test-value',
            'GOOGLE_API_KEY': 'google-test-value', 'CLAUDE_MODEL': 'test-model',
            'FACTORY_AUDIT_KEY': 'audit-test-value', 'FACTORY_OPERATOR': 'operator',
            'CONTROL_DB_PASSWORD': 'control-test-value', 'CONTROL_DB_URL': 'control-url',
        }
        config = SimpleNamespace(ROOT=root, environment=lambda: dict(settings))
        with patch.dict('sys.modules', {'factory_cli': config}), \
                patch('subprocess.run', return_value=SimpleNamespace(stderr='openjdk version "21.0.1"')), \
                patch('subprocess.call', return_value=0) as launch:
            with self.assertRaises(SystemExit) as result:
                runpy.run_path(str(root / 'scripts/shortener.py'), run_name='__main__')
        self.assertEqual(0, result.exception.code)
        self.assertEqual({'JAVA_HOME': '/test/jdk', 'PATH': '/test/bin',
                          'SHORTENER_DB_PASSWORD': 'product-test-value'}, launch.call_args.kwargs['env'])


if __name__ == '__main__':
    unittest.main()
