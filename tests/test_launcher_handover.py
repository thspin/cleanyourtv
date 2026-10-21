"""Run launcher/build-and-install.sh against a simulated TV, without gradle or a real device.

The handover is the step that can leave a TV with no working Home, so the failures worth testing are
the ones no TV reproduces on demand: a launcher that does not start, and a connection that drops
between disabling the stock launcher and confirming the result (#239).

Run from the repository root: python3 -m unittest discover -s tests -v
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
STOCK_HOME = "com.google.android.tvlauncher/.MainActivity"
NEW_HOME = "com.gathin.tvhome/.MainActivity"

# Stands in for tools/tv.py: a TV that answers shell commands and keeps the state the script changes.
FAKE_TV = r'''
import json
import os
import sys


class LinkLost(Exception):
    pass


STATE_PATH = os.environ["FAKE_TV_STATE"]


def load():
    with open(STATE_PATH) as f:
        return json.load(f)


def save():
    with open(STATE_PATH, "w") as f:
        json.dump(state, f)


state = load()


def run(command):
    if "pm install" in command:
        return "Success\n"
    if "am start" in command:
        if state["start_fails"]:
            return ("Starting: Intent { cmp=com.gathin.tvhome/.MainActivity }\n"
                    "Error: Activity not started, unable to resolve Intent\n")
        return ("Starting: Intent { cmp=com.gathin.tvhome/.MainActivity }\n"
                "Status: ok\nActivity: com.gathin.tvhome/.MainActivity\n")
    if "pm disable-user" in command:
        if state["disable_fails"]:
            return "Error: java.lang.SecurityException\n"
        state["stock_disabled"] = True
        if state["drop_after_handover"]:
            state["link_lost"] = True
        return ""
    if "pm enable" in command:
        if state["enable_fails"]:
            return "Error: java.lang.SecurityException\n"
        state["stock_disabled"] = False
        return ""
    if "get-role-holders" in command:
        return state["role"] + "\n"
    if "pm list packages" in command and " -d" in command:
        return "package:" + STOCK.split("/")[0] + "\n" if state["stock_disabled"] else ""
    if "pm list packages" in command and " -e" in command:
        # Without --user, Android 14 answers for every user: a second profile can still have it enabled.
        enabled = not state["stock_disabled"] or ("--user 0" not in command and state["enabled_for_other_user"])
        return "package:" + STOCK.split("/")[0] + "\n" if enabled else ""
    if "set-home-activity" in command:
        if not state["set_home_fails"]:
            state["home"] = STOCK if "resolve-activity" in command else NEW
            state["role"] = state["home"].split("/")[0]
        return ""
    if "resolve-activity" in command:
        if state["link_lost"]:
            raise LinkLost()
        # Google TV: an enabled stock launcher outranks this one for HOME whatever the preference says.
        if state["stock_outranks"] and not state["stock_disabled"]:
            return STOCK + "\n"
        # A disabled package is not a HOME candidate, so the answer falls back to the chooser.
        if state["stock_disabled"] and state["home"] == STOCK:
            return "android\n"
        return state["home"] + "\n"
    return ""


def main():
    args = [a for a in sys.argv[1:] if a != "-I"]
    if any(a.endswith("b64push.py") for a in args):
        print("pushed")
        return 0
    if "--batch" in args:
        commands = [line.strip() for line in sys.stdin
                    if line.strip() and not line.strip().startswith("#")]
    elif args:
        commands = [args[-1]]
    else:
        commands = []
    for command in commands:
        state["commands"].append(command)
        try:
            sys.stdout.write(run(command))
        except LinkLost:
            save()
            sys.stderr.write("adb: connection reset by peer\n")
            return 2
        sys.stdout.flush()
    save()
    return 0


sys.exit(main())
'''.replace("STOCK", repr(STOCK_HOME)).replace("NEW", repr(NEW_HOME))


class HandoverTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.base = Path(tmp.name)
        self.launcher = self.base / "launcher"
        self.launcher.mkdir()
        shutil.copy2(ROOT / "launcher" / "build-and-install.sh", self.launcher)
        # No JDK and no Android SDK here: the build step only has to produce a file to push.
        gradlew = self.launcher / "gradlew"
        gradlew.write_text('#!/bin/sh\n'
                           'mkdir -p app/build/outputs/apk/release\n'
                           'printf apk > app/build/outputs/apk/release/app-release.apk\n')
        gradlew.chmod(0o755)
        tools = self.base / "tools"
        (tools / "venv" / "bin").mkdir(parents=True)
        (tools / "b64push.py").write_text("")
        fake = self.base / "fake_tv.py"
        fake.write_text(FAKE_TV)
        client = tools / "venv" / "bin" / "python"
        client.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{fake}" "$@"\n')
        client.chmod(0o755)
        self.state_path = self.base / "tv-state.json"
        self.home = self.base / "home"
        self.home.mkdir()

    def handover(self, env_extra=None, **overrides):
        state = {
            "stock_home": STOCK_HOME,
            "home": STOCK_HOME,
            "stock_disabled": False,
            "start_fails": False,
            "disable_fails": False,
            "set_home_fails": False,
            "enable_fails": False,
            "drop_after_handover": False,
            "link_lost": False,
            "stock_outranks": False,
            "enabled_for_other_user": False,
            "role": STOCK_HOME.split("/")[0],
            "commands": [],
        }
        state.update(overrides)
        self.state_path.write_text(json.dumps(state))
        env = {k: v for k, v in os.environ.items()
               if k not in ("STOCK_LAUNCHER", "INPUT_KEY_SERVICE", "HOME_KEY_SERVICE")}
        env.update(TV_HOST="192.168.1.50", FAKE_TV_STATE=str(self.state_path),
                   HOME=str(self.home), JAVA_HOME=str(self.base / "java"),
                   ANDROID_HOME=str(self.base / "android-sdk"))
        env.update(env_extra or {})
        result = subprocess.run(
            ["bash", str(self.launcher / "build-and-install.sh")],
            cwd=self.launcher, env=env, capture_output=True, text=True, timeout=180,
        )
        with open(self.state_path) as f:
            state = json.load(f)
        return result, state

    def config(self, take_over_home, comment=""):
        values = self.launcher / "app" / "src" / "main" / "res" / "values"
        values.mkdir(parents=True)
        # Spread over lines on purpose: the script must not depend on how the XML is formatted.
        (values / "config.xml").write_text(
            '<resources>\n    %s\n    <bool name="take_over_home">\n        %s\n    </bool>\n</resources>\n'
            % (comment, "true" if take_over_home else "false"))

    def record(self):
        files = list(self.home.glob("cleanyourtv-handover-*.txt"))
        self.assertLessEqual(len(files), 1)
        return files[0].read_text() if files else None

    def test_success_hands_home_over(self):
        result, state = self.handover()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Installed.", result.stdout)
        commands = state["commands"]
        start = next(i for i, c in enumerate(commands) if "am start" in c)
        disable = next(i for i, c in enumerate(commands) if "pm disable-user" in c)
        self.assertLess(start, disable, "the launcher is started before the stock Home is disabled")
        self.assertTrue(state["stock_disabled"])
        self.assertEqual(state["home"], NEW_HOME)
        self.assertIsNone(self.record())

    def test_launcher_that_does_not_start_leaves_stock_home_enabled(self):
        result, state = self.handover(start_fails=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("did not start", result.stderr)
        self.assertIn("was left enabled", result.stderr)
        self.assertNotIn("Installed.", result.stdout)
        self.assertFalse(any("pm disable-user" in c for c in state["commands"]))
        self.assertFalse(state["stock_disabled"])

    def test_lost_connection_reports_incomplete_recovery(self):
        result, state = self.handover(drop_after_handover=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("stopped answering", result.stderr)
        self.assertIn("recovery is incomplete", result.stderr)
        self.assertTrue(any("pm enable --user 0" in c for c in state["commands"]))
        text = self.record()
        self.assertIsNotNone(text, "an unreachable TV needs the recovery commands on disk")
        self.assertIn("pm enable --user 0 com.google.android.tvlauncher", text or "")

    def test_home_that_stays_on_another_launcher_is_rolled_back(self):
        result, state = self.handover(set_home_fails=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("owns Home again", result.stderr)
        self.assertIn("setupwraith", result.stderr)
        self.assertFalse(state["stock_disabled"])
        self.assertIsNone(self.record())

    def test_failed_rollback_is_not_reported_as_recovered(self):
        result, state = self.handover(set_home_fails=True, enable_fails=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("owns Home again", result.stderr)
        self.assertIn("recovery is incomplete", result.stderr)
        self.assertTrue(any("pm enable --user 0" in c for c in state["commands"]))
        self.assertIsNotNone(self.record())

    def assert_stock_launcher_kept(self, state):
        commands = state["commands"]
        self.assertFalse(any("pm disable-user" in c for c in commands))
        stock = commands.index("pm enable --user 0 com.google.android.tvlauncher")
        setup = commands.index("pm enable --user 0 com.google.android.tungsten.setupwraith")
        self.assertLess(stock, setup, "setupwraith is never enabled while the stock launcher is disabled")
        self.assertFalse(state["stock_disabled"])

    def test_take_over_home_keeps_the_stock_launcher(self):
        # A Chromecast that keeps setupwraith for casting: the stock launcher stays Android's Home.
        self.config(take_over_home=True)
        result, state = self.handover(stock_outranks=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("HomeKeyService opens this launcher instead", result.stdout)
        self.assert_stock_launcher_kept(state)
        self.assertTrue(any("HomeKeyService" in c and "enabled_accessibility_services" in c
                            for c in state["commands"]))
        self.assertEqual(state["role"], "com.gathin.tvhome")
        self.assertIsNone(self.record())

    def test_take_over_home_brings_a_disabled_stock_launcher_back(self):
        # Cleaned up before: the stock launcher is disabled and Home answers with this launcher.
        self.config(take_over_home=True)
        result, state = self.handover(home=NEW_HOME, stock_disabled=True, stock_outranks=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("will be enabled again", result.stdout)
        self.assert_stock_launcher_kept(state)
        self.assertEqual(state["role"], "com.gathin.tvhome")

    def test_take_over_home_stops_if_the_stock_launcher_stays_disabled(self):
        # A batch carries on past a failed command; setupwraith must not follow a failed enable.
        self.config(take_over_home=True)
        result, state = self.handover(home=NEW_HOME, stock_disabled=True, stock_outranks=True,
                                      enable_fails=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("could not be enabled", result.stderr)
        self.assertFalse(any("setupwraith" in c for c in state["commands"]))
        self.assertFalse(any("HomeKeyService" in c for c in state["commands"]))

    def test_take_over_home_checks_the_stock_launcher_for_user_0_only(self):
        # Still enabled in a second profile, and the enable for the main one failed.
        self.config(take_over_home=True)
        result, state = self.handover(home=NEW_HOME, stock_disabled=True, stock_outranks=True,
                                      enable_fails=True, enabled_for_other_user=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("could not be enabled", result.stderr)
        self.assertFalse(any("setupwraith" in c for c in state["commands"]))

    def test_take_over_home_needs_the_home_role(self):
        self.config(take_over_home=True)
        result, state = self.handover(stock_outranks=True, set_home_fails=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("HOME role", result.stderr)
        self.assertFalse(any("HomeKeyService" in c for c in state["commands"]))
        self.assert_stock_launcher_kept(state)

    def test_take_over_home_needs_a_stock_launcher_to_keep(self):
        self.config(take_over_home=True)
        result, state = self.handover(env_extra={"STOCK_LAUNCHER": "none"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("none was found to keep", result.stderr)
        self.assertFalse(any("pm install" in c for c in state["commands"]),
                         "nothing is installed or changed before the check")

    def test_take_over_home_false_hands_home_over_as_before(self):
        self.config(take_over_home=False)
        result, state = self.handover()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(state["stock_disabled"])
        self.assertEqual(state["home"], NEW_HOME)
        self.assertFalse(any("HomeKeyService" in c for c in state["commands"]))

    def test_take_over_home_in_a_comment_does_not_count(self):
        self.config(take_over_home=False,
                    comment='<!-- e.g. <bool name="take_over_home">true</bool> on a Chromecast -->')
        result, state = self.handover()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(state["stock_disabled"])
        self.assertFalse(any("setupwraith" in c for c in state["commands"]))

if __name__ == "__main__":
    unittest.main()
