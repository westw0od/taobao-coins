"""Regressions for task scope, completion, navigation, and quiz retries."""

import unittest
from unittest.mock import Mock, patch

from taobao_coins import runner, screen
from taobao_coins.android import ExternalAppOpened, NeedsAttention, ensure_taobao


def snapshot(*items):
    return ([screen.Line(text, x, y, (x - 30, y - 15, x + 30, y + 15)) for text, x, y in items], 1080, 2340)


class TaskScopeTests(unittest.TestCase):
    def test_cross_app_and_manual_tasks_are_filtered_from_entire_row(self):
        descriptions = (
            "去支付宝农场逛逛 浏览5秒", "去微信 浏览15秒", "打开百度App 浏览15秒",
            "逛UC领红包 浏览5秒", "去闲鱼 浏览15秒", "去神奇海洋逛逛 点击去逛",
            "玩游戏 浏览15秒", "邀请好友 点击去逛", "开通会员 浏览5秒",
        )
        for description in descriptions:
            with self.subTest(description=description):
                self.assertFalse(screen.allowed_task("币", description))

    def test_free_browse_part_of_promotion_is_supported(self):
        self.assertTrue(screen.allowed_task("快速得金币", "下单最高10000，浏览10秒得20金币"))
        self.assertFalse(screen.allowed_task("买限时折扣好物", "下单得300金币"))
        self.assertTrue(screen.allowed_task("好物沉浸看(0/3)", "浏览15秒 +30"))

    def test_reward_glyph_does_not_hide_external_app_name(self):
        view = snapshot(
            ("币", 200, 690), ("去支付宝农场逛逛(0/1)", 460, 710),
            ("点击去逛", 450, 760), ("去完成", 960, 745),
        )
        self.assertEqual(screen.browse_tasks(*view), [])

    def test_completed_progress_and_unreadable_rows_are_skipped(self):
        done = snapshot(("好物沉浸看(3/3)", 450, 710), ("浏览15秒", 450, 760), ("去完成", 960, 745))
        self.assertEqual(screen.browse_tasks(*done), [])
        self.assertEqual(screen.browse_tasks(*snapshot(("去完成", 960, 745))), [])


class CompletionTests(unittest.TestCase):
    def test_completed_and_active_statuses(self):
        cases = (
            (("完成！30淘金币已到账",), True),
            (("30淘金币", "已到账"), True),
            (("任务已完成", "继续逛逛吧"), True),
            (("任务完成",), True),
            (("浏览15秒可领30金币", "14"), False),
            (("任务未完成",), False),
            (("任务完成后领取奖励",), False),
            (("30淘金币已到账", "继续浏览15秒再得30金币"), False),
        )
        for texts, expected in cases:
            with self.subTest(texts=texts):
                lines, _, _ = snapshot(*[(text, 800, 1800) for text in texts])
                self.assertEqual(screen.reward_complete(lines), expected)

    def test_completed_browse_returns_before_next_swipe(self):
        device = Mock()
        device.window_size.return_value = (1080, 2340)
        with (
            patch.object(runner, "prepare_search"),
            patch.object(runner, "require_taobao"),
            patch.object(runner, "browse_reward_complete", return_value=True),
        ):
            self.assertTrue(runner.browse(device, 38))
        device.swipe.assert_not_called()

    def test_unfinished_browse_keeps_swiping_until_timeout(self):
        device = Mock()
        device.window_size.return_value = (1080, 2340)
        clock = [0.0]

        def sleep(seconds):
            clock[0] += seconds

        with (
            patch.object(runner, "prepare_search"),
            patch.object(runner, "require_taobao"),
            patch.object(runner, "browse_reward_complete", return_value=False),
            patch.object(runner.time, "monotonic", side_effect=lambda: clock[0]),
            patch.object(runner.time, "sleep", side_effect=sleep),
        ):
            self.assertFalse(runner.browse(device, 20))
        self.assertEqual(clock[0], 20)
        self.assertGreaterEqual(device.swipe.call_count, 9)

    def test_external_app_ends_task_before_scrolling(self):
        device = Mock()
        with patch.object(runner, "prepare_search", side_effect=ExternalAppOpened("微信")):
            with self.assertRaises(ExternalAppOpened):
                runner.browse(device, 20)
        device.swipe.assert_not_called()


class RecoveryTests(unittest.TestCase):
    def test_recovery_is_bounded(self):
        device = Mock()
        device.app_current.return_value = {"package": "com.tencent.mm"}
        device.shell.return_value.exit_code = 0
        with patch("taobao_coins.android.time.sleep"):
            with self.assertRaises(NeedsAttention):
                ensure_taobao(device)
        self.assertEqual(device.shell.call_count, 3)
        device.click.assert_not_called()

    def test_unsigned_navigation_signs_in_before_opening_tasks(self):
        device = Mock()
        views = [
            snapshot(("签到领金币", 500, 650)),
            snapshot(("赚更多金币", 500, 650)),
            snapshot(("今日速赚200淘金币", 500, 250)),
        ]
        with (
            patch.object(runner, "ensure_taobao"),
            patch.object(runner, "read_screen", side_effect=views),
            patch.object(runner, "click_line") as click,
        ):
            self.assertIs(runner.open_panel(device), views[-1])
        self.assertEqual([call.args[1].text for call in click.call_args_list], ["签到领金币", "赚更多金币"])
        device.press.assert_not_called()


class QuizTests(unittest.TestCase):
    def test_unknown_question_tries_a_then_b_then_c(self):
        question = snapshot(("新题", 500, 600), ("A苹果", 300, 850), ("B香蕉", 300, 1100), ("C樱桃", 300, 1350))
        submit = snapshot(("我选好了", 500, 2150))
        wrong = snapshot(("回答错误", 500, 800), ("重新答题", 500, 2000))
        right = snapshot(("回答正确", 500, 800))
        views = [question, submit, wrong, question, submit, wrong, question, submit, right]
        with patch.object(runner, "read_screen", side_effect=views), patch.object(runner, "click_line") as click:
            self.assertTrue(runner.answer_quiz(Mock()))
        self.assertEqual(
            [call.args[1].text for call in click.call_args_list],
            ["A苹果", "我选好了", "重新答题", "B香蕉", "我选好了", "重新答题", "C樱桃", "我选好了"],
        )


if __name__ == "__main__":
    unittest.main()
