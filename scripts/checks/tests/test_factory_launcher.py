import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

MODULE_PATH = Path(__file__).resolve().parents[2] / "factory_cli.py"
SPEC = importlib.util.spec_from_file_location("factory_cli", MODULE_PATH)
launcher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(launcher)


class FactoryLauncherTest(unittest.TestCase):
    def test_retired_limits_are_ignored_in_shell_and_env_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".env").write_text("FACTORY_MAX_MODEL_CALLS=1\nFACTORY_CHAT_DAILY_REQUESTS=many\nCLAUDE_MODEL=test-model\n")
            with patch.object(launcher, "ROOT", root), patch.dict(os.environ, {"FACTORY_MAX_MODEL_CALLS": "2"}, clear=True):
                values = launcher.environment()
            self.assertEqual("test-model", values["CLAUDE_MODEL"])
            self.assertFalse(launcher.RETIRED.intersection(values))

    def test_unknown_settings_still_fail_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".env").write_text("UNKNOWN_SETTING=value\n")
            with patch.object(launcher, "ROOT", root):
                with self.assertRaises(ValueError):
                    launcher.environment()


if __name__ == "__main__":
    unittest.main()
