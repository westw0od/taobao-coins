"""Coordinate and foreground regressions for regional OCR."""

import unittest
from unittest.mock import Mock, patch

import numpy as np

from taobao_coins import screen
from taobao_coins.android import ExternalAppOpened, NeedsAttention


class ScreenTests(unittest.TestCase):
    def setUp(self):
        self.device = Mock()
        self.device.app_current.return_value = {"package": "com.taobao.taobao"}
        self.device.screenshot.return_value = np.zeros((2340, 1080, 3), dtype=np.uint8)
        self.box = [[0, 0], [60, 0], [60, 20], [0, 20]]

    def test_default_and_regional_coordinates_keep_original_screen_size(self):
        reader = Mock(return_value=([(self.box, "任务已完成", 0.99)], None))
        with patch.object(screen, "_reader", reader):
            _, width, height = screen.read_screen(self.device)
            self.assertEqual((width, height), (1080, 2340))
            self.assertEqual(reader.call_args.args[0].shape[:2], (1560, 720))
            lines, width, height = screen.read_screen(
                self.device, regions=((0, 0, 1, 0.25), (0.65, 0.3, 1, 0.96)),
            )
            self.assertEqual((width, height), (1080, 2340))
            self.assertEqual(lines[1].bounds, (702, 702, 792, 732))

    def test_verification_stops_page_actions(self):
        reader = Mock(return_value=([(self.box, "请向右滑动验证", 0.99)], None))
        with patch.object(screen, "_reader", reader):
            with self.assertRaises(NeedsAttention):
                screen.read_screen(self.device)
        self.device.click.assert_not_called()

    def test_foreign_app_is_checked_before_clicking(self):
        self.device.app_current.return_value = {"package": "com.tencent.mm"}
        line = screen.Line("去完成", 960, 745, (900, 710, 1000, 780))
        with self.assertRaises(ExternalAppOpened):
            screen.click_line(self.device, line)
        self.device.click.assert_not_called()


if __name__ == "__main__":
    unittest.main()
