"""Device selection and bounded foreground recovery.

Adapted from czl0325/coin11-tb. Modified for the coin-only public version.
"""

import os
from pathlib import Path
import re
import shutil
import subprocess
import time

import uiautomator2 as u2


TAOBAO = "com.taobao.taobao"
ACTIVITY = "com.taobao.tao.welcome.Welcome"


class NeedsAttention(RuntimeError):
    """The user must handle a verification, login, or connection problem."""


class ExternalAppOpened(RuntimeError):
    """The current task left Taobao."""


def connect(serial=None, adb_path=None):
    adb = adb_path or os.environ.get("ADB") or shutil.which("adb")
    if not adb:
        bundled = Path(__file__).resolve().parents[1] / "platform-tools" / "adb.exe"
        if bundled.is_file():
            adb = str(bundled)
    if not adb or not Path(adb).is_file():
        raise NeedsAttention("找不到 adb，请安装 Android Platform Tools 并加入 PATH，或使用 --adb 指定 adb 路径。")
    os.environ["ADBUTILS_ADB_PATH"] = str(adb)
    started = subprocess.run([adb, "start-server"], capture_output=True, text=True, timeout=30)
    if started.returncode:
        raise NeedsAttention(f"ADB 服务启动失败\n{started.stderr.strip()}")
    listed = subprocess.run([adb, "devices"], capture_output=True, text=True, check=True, timeout=15)
    states = {}
    for line in listed.stdout.splitlines()[1:]:
        fields = line.split()
        if len(fields) >= 2:
            states[fields[0]] = fields[1]
    serial = serial or os.environ.get("TAOBAO_DEVICE")
    if serial:
        if states.get(serial) != "device":
            raise NeedsAttention(f"指定设备当前状态为 {states.get(serial, '未连接')}，请检查数据线和 USB 调试授权。")
    else:
        authorized = [item for item, state in states.items() if state == "device"]
        if len(authorized) > 1:
            raise NeedsAttention("已连接多台手机，请使用 --serial 指定一台。可用设备\n" + "\n".join(authorized))
        if not authorized:
            raise NeedsAttention("没有已授权手机。请解锁手机、开启 USB 调试，并在手机上允许这台电脑。")
        serial = authorized[0]
    device = u2.connect(serial)
    device.screen_on()
    return device


def foreground(device):
    # On recent Samsung Android versions `window windows` omits the focus.
    # adbutils.app_current() then falls back to the last background activity.
    # Read the actual focused window instead; never infer focus from task order.
    for command in (["dumpsys", "window", "displays"], ["dumpsys", "window"]):
        result = device.shell(command, timeout=10)
        if result.exit_code:
            continue
        focus = re.search(r"^\s*mCurrentFocus=(.*)$", result.output, re.MULTILINE)
        if focus:
            component = re.search(r"\s([\w.]+)/[^\s}]+", focus.group(1))
            # Null focus or a system overlay is not permission to click the app
            # underneath it, even if that app is still marked as resumed.
            return component.group(1) if component else None

    result = device.shell(["dumpsys", "activity", "activities"], timeout=10)
    if result.exit_code:
        return None
    for marker in ("topResumedActivity", "mResumedActivity", "ResumedActivity"):
        activity = re.search(
            rf"^\s*{marker}\s*[:=]\s*ActivityRecord\{{[^\n]*?\s([\w.]+)/",
            result.output, re.MULTILINE,
        )
        if activity:
            return activity.group(1)
    return None


def require_taobao(device):
    package = foreground(device)
    if package != TAOBAO:
        raise ExternalAppOpened(f"前台应用已变为 {package or '未知应用'}")


def ensure_taobao(device, android_user=0):
    package = foreground(device)
    for _ in range(3):
        if package == TAOBAO:
            return
        print("返回淘宝", flush=True)
        result = device.shell([
            "am", "start", "--user", str(android_user),
            "-n", f"{TAOBAO}/{ACTIVITY}",
        ])
        if result.exit_code or re.search(r"^\s*(?:Error:|Error type|Exception)", result.output, re.MULTILINE):
            raise NeedsAttention(f"启动淘宝失败，请确认指定安卓用户已安装淘宝\n{result.output}")
        deadline = time.monotonic() + 6
        while True:
            package = foreground(device)
            if package == TAOBAO:
                return
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            time.sleep(min(0.5, remaining))
    raise NeedsAttention(
        f"连续三次启动后仍未进入淘宝，当前前台为 {package or '未知应用'}。"
        "请在手机上打开淘宝并检查授权、登录页面或锁屏状态。"
    )
