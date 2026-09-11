import importlib.util
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location(
    "check_upstream_compat", ROOT / "dev/check-upstream-compat.py")
COMPAT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COMPAT)


class CompatibilityInputsTest(unittest.TestCase):
    def make_repo(self, root):
        for name in COMPAT.PRIVATE_INPUTS:
            path = root / name
            path.mkdir(parents=True)
            (path / "sentinel").write_text(name)
        config = root / "configs/m3.toml"
        config.write_text("[methods]\nintro = 1\n")
        return config

    def test_missing_required_config_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "missing required compatibility config: configs/m3.toml"):
                COMPAT.required_config_identity(Path(directory))

    def test_private_copy_includes_exact_config_and_detects_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            repo = base / "repo"
            work = base / "work"
            repo.mkdir()
            work.mkdir()
            config = self.make_repo(repo)
            expected = COMPAT.required_config_identity(repo)

            COMPAT.copy_private_inputs(repo, work)
            self.assertEqual(COMPAT.required_config_identity(work), expected)
            self.assertEqual((work / "configs/m3.toml").read_bytes(), config.read_bytes())

            (work / "configs/m3.toml").write_text("[methods]\nintro = 2\n")
            self.assertNotEqual(COMPAT.required_config_identity(work), expected)

    def test_legacy_baseline_requires_forced_fresh_gate(self):
        current = {"configs/m3.toml": "abc"}
        with self.assertRaisesRegex(ValueError, "baseline lacks required config identity"):
            COMPAT.validate_baseline_configs({"schema_version": 1}, current, False)
        COMPAT.validate_baseline_configs({"schema_version": 1}, current, True)

    def test_config_drift_requires_forced_fresh_gate(self):
        current = {"configs/m3.toml": "new"}
        baseline = {"schema_version": 2,
                    "required_configs": {"configs/m3.toml": "old"}}
        with self.assertRaisesRegex(ValueError, "config changed since recorded evidence"):
            COMPAT.validate_baseline_configs(baseline, current, False)
        COMPAT.validate_baseline_configs(baseline, current, True)


if __name__ == "__main__":
    unittest.main()
