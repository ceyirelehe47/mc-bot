from __future__ import annotations

import importlib.util
import json
import pathlib
import tempfile
import unittest

P = pathlib.Path(__file__).parent
SPEC = importlib.util.spec_from_file_location("real_client_supervisor", P / "real_client_supervisor.py")
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class SupervisorTest(unittest.TestCase):
    def test_offline_uuid_matches_known_bob_value(self):
        self.assertEqual(str(MODULE.offline_uuid("Bob")), "faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b")

    def test_offline_uuid_is_case_sensitive_like_minecraft(self):
        self.assertNotEqual(MODULE.offline_uuid("Bob"), MODULE.offline_uuid("bob"))

    def test_expand_is_deterministic(self):
        self.assertEqual(MODULE.expand("x-{username}-{body_id}", {"username": "Bob", "body_id": "bob"}), "x-Bob-bob")

    def test_config_rejects_shell_string(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "config.json"
            path.write_text(json.dumps({"username": "Bob", "command": "gradlew runClient"}))
            with self.assertRaisesRegex(ValueError, "command_must"):
                MODULE.load_config(path)

    def test_config_requires_offline_identity_placeholders(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "config.json"
            path.write_text(json.dumps({"username": "Bob", "command": ["gradlew", "runClient"]}))
            with self.assertRaisesRegex(ValueError, "offline_identity"):
                MODULE.load_config(path)

    def test_config_accepts_offline_argument_array(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "config.json"
            path.write_text(json.dumps({
                "username": "Bob",
                "command": ["java", "--username", "{username}", "--uuid", "{offline_uuid}",
                            "--accessToken", "0", "--userType", "legacy"]
            }))
            self.assertEqual(MODULE.load_config(path)["command"][2], "{username}")

    def test_config_rejects_non_offline_access_token(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "config.json"
            path.write_text(json.dumps({
                "username": "Bob",
                "command": ["java", "--username", "{username}", "--uuid", "{offline_uuid}",
                            "--accessToken", "secret-token"]
            }))
            with self.assertRaisesRegex(ValueError, "non_offline"):
                MODULE.load_config(path)

    def test_config_rejects_sensitive_account_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "config.json"
            path.write_text(json.dumps({
                "username": "Bob",
                "command": ["java", "--username", "{username}", "--uuid", "{offline_uuid}"],
                "environment": {"MICROSOFT_REFRESH_TOKEN": "secret"}
            }))
            with self.assertRaisesRegex(ValueError, "microsoft_credentials"):
                MODULE.load_config(path)

    def test_config_rejects_non_loopback_control_host(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "config.json"
            path.write_text(json.dumps({
                "username": "Bob",
                "command": ["java", "--username", "{username}", "--uuid", "{offline_uuid}"],
                "control_host": "192.0.2.1"
            }))
            with self.assertRaisesRegex(ValueError, "loopback"):
                MODULE.load_config(path)


if __name__ == "__main__":
    unittest.main(verbosity=2)
