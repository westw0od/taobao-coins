"""Screenshot retention and cleanup across task exit paths."""

from argparse import Namespace
from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock, patch

import main as entrypoint
from taobao_coins.android import NeedsAttention


class CleanupTests(unittest.TestCase):
    def test_only_images_in_logs_are_removed(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            logs = root / "logs" / "old-run"
            logs.mkdir(parents=True)
            for name in ("start.png", "finish.PNG", "debug.jpg", "view.jpeg", "frame.webp"):
                (logs / name).write_bytes(b"image")
            for name in ("run.log", "result.json"):
                (logs / name).write_text("preserve", encoding="utf-8")
            outside = root / "outside.png"
            outside.write_bytes(b"preserve")
            with redirect_stdout(io.StringIO()):
                result = entrypoint.cleanup_screenshots(root / "logs")
            self.assertEqual(result, {"deleted": 5, "failed": []})
            self.assertTrue(outside.exists())
            self.assertTrue((logs / "run.log").exists())
            self.assertTrue((logs / "result.json").exists())

    def test_failed_deletion_is_reported(self):
        with TemporaryDirectory() as temporary:
            image = Path(temporary) / "locked.png"
            image.write_bytes(b"image")
            with patch.object(Path, "unlink", side_effect=PermissionError("locked")):
                with redirect_stdout(io.StringIO()):
                    result = entrypoint.cleanup_screenshots(Path(temporary))
            self.assertEqual(result, {"deleted": 0, "failed": ["locked.png"]})
            self.assertTrue(image.exists())

    def test_every_task_exit_cleans_images_and_keeps_results(self):
        cases = (
            (None, "scan_finished", 0),
            (NeedsAttention("verification"), "needs_attention", 2),
            (RuntimeError("task failed"), "failed", 1),
            (KeyboardInterrupt(), "stopped", 130),
        )
        for error, status, expected_code in cases:
            with self.subTest(status=status), TemporaryDirectory() as temporary:
                root = Path(temporary)
                args = Namespace(serial=None, adb=None, android_user=0, check=False)
                device = Mock()
                captured = io.StringIO()

                def task(*_):
                    (root / "logs" / "during-task.png").write_bytes(b"image")
                    if error is not None:
                        raise error
                    return {"progress_updates": 1}

                with (
                    patch.object(entrypoint, "ROOT", root),
                    patch("taobao_coins.android.connect", return_value=device),
                    patch("taobao_coins.android.foreground", return_value="com.taobao.taobao"),
                    patch("taobao_coins.runner.run", side_effect=task),
                    redirect_stdout(captured),
                    redirect_stderr(captured),
                    entrypoint.run_lock(),
                ):
                    (root / "logs" / "old.png").write_bytes(b"image")
                    self.assertEqual(entrypoint.execute(args), expected_code)
                self.assertEqual(list((root / "logs").rglob("*.png")), [])
                result_file = next((root / "logs").rglob("result.json"))
                result = json.loads(result_file.read_text(encoding="utf-8"))
                self.assertEqual(result["status"], status)
                self.assertEqual(result["screenshot_cleanup_on_start"]["deleted"], 1)
                self.assertEqual(result["screenshot_cleanup_on_exit"]["deleted"], 1)
                self.assertTrue(result_file.with_name("run.log").is_file())
                device.screenshot.assert_not_called()


if __name__ == "__main__":
    unittest.main()
