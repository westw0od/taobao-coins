"""Command-line entry point for Taobao Coins."""

import argparse
from contextlib import contextmanager, redirect_stderr, redirect_stdout
from datetime import datetime
import json
import os
from pathlib import Path
import sys
import traceback


ROOT = Path(__file__).resolve().parent


class Tee:
    def __init__(self, console, log):
        self.console = console
        self.log = log

    def write(self, text):
        self.console.write(text)
        self.log.write(text)
        self.flush()
        return len(text)

    def flush(self):
        self.console.flush()
        self.log.flush()


@contextmanager
def run_lock():
    directory = ROOT / "logs"
    directory.mkdir(exist_ok=True)
    with (directory / "run.lock").open("a+b") as lock:
        lock.seek(0)
        if not lock.read(1):
            lock.write(b"0")
            lock.flush()
        lock.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise RuntimeError("另一个金币程序正在运行，请先关闭原来的窗口。") from error
        yield


def cleanup_screenshots(directory):
    root = directory.resolve()
    deleted = 0
    failed = []
    for path in root.rglob("*"):
        if path.suffix.lower() not in (".png", ".jpg", ".jpeg", ".webp"):
            continue
        if not path.is_file() or not path.resolve().is_relative_to(root):
            continue
        try:
            path.unlink()
            deleted += 1
        except OSError as error:
            failed.append(str(path.relative_to(root)))
            print(f"截图清理失败 {path.name} {error}", flush=True)
    if deleted:
        print(f"已清理 {deleted} 张历史截图", flush=True)
    return {"deleted": deleted, "failed": failed}


def execute(args):
    from taobao_coins.android import NeedsAttention, connect, foreground
    from taobao_coins.runner import run

    directory = ROOT / "logs" / datetime.now().strftime("coins-%Y%m%d-%H%M%S-%f")
    directory.mkdir()
    result = {"started_at": datetime.now().isoformat(), "status": "running"}
    exit_code = 0
    with (directory / "run.log").open("w", encoding="utf-8") as log:
        with redirect_stdout(Tee(sys.stdout, log)), redirect_stderr(Tee(sys.stderr, log)):
            try:
                result["screenshot_cleanup_on_start"] = cleanup_screenshots(ROOT / "logs")
                device = connect(args.serial, args.adb)
                print(f"设备已连接，前台应用 {foreground(device)}", flush=True)
                if args.check:
                    result["status"] = "device_ready"
                    print("设备检查完成。直接运行 main.py 可开始金币任务。", flush=True)
                else:
                    result.update(run(device, args.android_user))
                    result["status"] = "scan_finished"
            except NeedsAttention as error:
                result.update(status="needs_attention", error=str(error))
                print(f"需要手动处理 {error}", flush=True)
                exit_code = 2
            except KeyboardInterrupt:
                result["status"] = "stopped"
                print("已停止。", flush=True)
                exit_code = 130
            except Exception as error:
                result.update(status="failed", error=str(error))
                traceback.print_exc()
                exit_code = 1
            finally:
                cleanup = cleanup_screenshots(ROOT / "logs")
                result["screenshot_cleanup_on_exit"] = cleanup
                if cleanup["failed"] and exit_code == 0:
                    result.update(status="cleanup_failed", error="部分历史截图清理失败，请查看 run.log。")
                    exit_code = 1
                result.update(finished_at=datetime.now().isoformat(), exit_code=exit_code)
                (directory / "result.json").write_text(
                    json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8",
                )
                print(f"运行记录已保存到 {directory}", flush=True)
    return exit_code


def main():
    parser = argparse.ArgumentParser(description="完成淘宝站内免费淘金币任务")
    parser.add_argument("--serial", help="ADB 设备编号，多台手机连接时使用")
    parser.add_argument("--adb", help="ADB 可执行文件路径，默认从 PATH 查找")
    parser.add_argument("--android-user", type=int, default=0, help="安卓用户 ID，默认 0")
    parser.add_argument("--check", action="store_true", help="只检查设备连接")
    args = parser.parse_args()
    if args.android_user < 0:
        parser.error("--android-user 必须是非负整数")
    try:
        with run_lock():
            return execute(args)
    except (RuntimeError, ImportError) as error:
        print(f"启动失败 {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
