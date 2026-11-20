"""Foreground detection on Samsung and bounded startup recovery."""

import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

from taobao_coins.android import (
    ExternalAppOpened, NeedsAttention, TAOBAO, ensure_taobao, foreground, require_taobao,
)


def reply(output, code=0):
    return SimpleNamespace(output=output, exit_code=code)


class ForegroundTests(unittest.TestCase):
    def test_samsung_focus_overrides_stale_third_party_result(self):
        device = Mock()
        device.app_current.return_value = {"package": "com.ss.android.ugc.aweme"}
        device.shell.return_value = reply(
            "  mCurrentFocus=Window{c0719f1 u0 com.taobao.taobao/com.taobao.tao.welcome.Welcome}\n"
            "  mFocusedApp=ActivityRecord{808b2d8 u0 com.taobao.taobao/.Welcome t30998}\n"
        )
        ensure_taobao(device)
        require_taobao(device)
        device.app_current.assert_not_called()
        self.assertTrue(all(call.args[0] == ["dumpsys", "window", "displays"]
                            for call in device.shell.call_args_list))

    def test_full_window_dump_is_used_when_displays_omits_focus(self):
        device = Mock()
        device.shell.side_effect = [reply("Display #0"), reply(
            "mCurrentFocus=Window{abc u0 com.taobao.taobao/.Welcome}\n"
        )]
        self.assertEqual(foreground(device), TAOBAO)
        self.assertEqual(device.shell.call_args.args[0], ["dumpsys", "window"])

    def test_new_resumed_marker_does_not_use_last_background_task(self):
        device = Mock()
        device.shell.side_effect = [reply(""), reply(""), reply(
            " topResumedActivity=ActivityRecord{808b u0 com.taobao.taobao/.Welcome t30}\n"
            " mResumedActivity: ActivityRecord{aaa u0 com.tencent.mm/.Main t31}\n"
        )]
        self.assertEqual(foreground(device), TAOBAO)
        device.app_current.assert_not_called()

    def test_null_focus_and_system_overlay_do_not_allow_underlying_taobao(self):
        for focus in ("null", "Window{abc u0 NotificationShade}",
                      "Window{abc u0 com.android.permissioncontroller/.GrantPermissionsActivity}",
                      "Window{abc u0 com.tencent.mm/.Main}"):
            with self.subTest(focus=focus):
                device = Mock()
                device.shell.return_value = reply(
                    f"mCurrentFocus={focus}\nmFocusedApp=ActivityRecord{{abc u0 {TAOBAO}/.Welcome t1}}"
                )
                with self.assertRaises(ExternalAppOpened):
                    require_taobao(device)
                self.assertEqual(device.shell.call_count, 1)

    def test_unreadable_focus_is_unknown(self):
        device = Mock()
        device.shell.return_value = reply("Permission denied", 1)
        self.assertIsNone(foreground(device))
        device.app_current.assert_not_called()


class StartupTests(unittest.TestCase):
    def test_slow_cold_start_is_polled_without_relaunching(self):
        device = Mock()
        device.shell.return_value = reply("Starting: Intent")
        clock = [0.0]

        def sleep(seconds):
            clock[0] += seconds

        def current(_):
            return TAOBAO if clock[0] >= 3 else "com.sec.android.app.launcher"

        with (patch("taobao_coins.android.foreground", side_effect=current),
              patch("taobao_coins.android.time.monotonic", side_effect=lambda: clock[0]),
              patch("taobao_coins.android.time.sleep", side_effect=sleep)):
            ensure_taobao(device)
        self.assertEqual(device.shell.call_count, 1)
        self.assertEqual(clock[0], 3)

    def test_am_start_error_is_reported_even_with_zero_exit_status(self):
        device = Mock()
        device.shell.return_value = reply("Error type 3\nError: Activity class does not exist.")
        with patch("taobao_coins.android.foreground", return_value=None):
            with self.assertRaisesRegex(NeedsAttention, "Activity class does not exist"):
                ensure_taobao(device)
        self.assertEqual(device.shell.call_count, 1)


if __name__ == "__main__":
    unittest.main()
