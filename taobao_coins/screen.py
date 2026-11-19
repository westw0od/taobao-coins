"""Screenshot OCR and visible task selection.

Adapted from the local coin11-tb reward_ui.py; modified task filtering,
regional completion checks, and foreground guards for this distribution.
"""

from dataclasses import dataclass
import os
import re
import time

import cv2
from rapidocr_onnxruntime import RapidOCR

from .android import NeedsAttention, require_taobao


_reader = None


@dataclass
class Line:
    text: str
    x: int
    y: int
    bounds: tuple


@dataclass
class Task:
    title: str
    description: str
    button: Line
    duration: int
    kind: str


EXTERNAL_TASKS = (
    "微信", "支付宝", "蚂蚁", "神奇海洋", "百度", "UC", "闲鱼", "点淘",
    "抖音", "快手", "微博", "京东", "拼多多", "饿了么", "美团", "菜鸟",
    "天猫App", "天猫APP", "淘宝特价版", "淘特", "一淘", "跨App", "跨APP",
    "打开其他应用", "跳转应用", "金豆",
)
MANUAL_TASKS = (
    "付款", "支付订单", "确认收货", "借金币", "分享", "邀请", "开通", "捐",
    "拍立淘", "订阅", "添加桌面", "桌面组件", "游戏", "斗地主", "消消乐",
    "三国", "闯关", "试玩", "种树", "浇水",
)


def read_screen(device, *, regions=None):
    global _reader
    require_taobao(device)
    if _reader is None:
        _reader = RapidOCR(
            intra_op_num_threads=min(8, os.cpu_count() or 2),
            inter_op_num_threads=1,
        )
    screen = device.screenshot(format="opencv")
    height, width = screen.shape[:2]
    scale = min(1, 720 / width)
    lines = []
    for left, top, right, bottom in regions or ((0, 0, 1, 1),):
        x_offset, y_offset = round(width * left), round(height * top)
        crop = screen[y_offset:round(height * bottom), x_offset:round(width * right)]
        resized = cv2.resize(crop, (round(crop.shape[1] * scale), round(crop.shape[0] * scale)))
        detected, _ = _reader(resized)
        for box, text, confidence in detected or []:
            if confidence < 0.65:
                continue
            xs = [round(point[0] / scale) + x_offset for point in box]
            ys = [round(point[1] / scale) + y_offset for point in box]
            bounds = (min(xs), min(ys), max(xs), max(ys))
            lines.append(Line(text.replace(" ", ""), sum(xs) // 4, sum(ys) // 4, bounds))
    verification = ("验证码", "滑块验证", "拖动滑块", "向右滑动验证", "向右滑动完成验证")
    if any(any(word in line.text for word in verification) for line in lines):
        raise NeedsAttention("手机需要完成验证，请手动处理后重新运行。")
    return lines, width, height


def find_line(lines, words):
    return next((line for line in lines if any(word in line.text for word in words)), None)


def click_line(device, line):
    require_taobao(device)
    device.click(line.x, line.y)
    time.sleep(1.5)


def reward_complete(lines):
    text = "".join(line.text for line in lines)
    if re.search(r"(?:再|继续)(?:逛|浏览|看).{0,8}\d+秒", text):
        return False
    if re.search(r"淘?金币(?:已|已经)到账|任务已完成", text):
        return True
    return any(re.fullmatch(r"(?:任务完成|浏览完成|浏览已完成)[!！。]?", line.text) for line in lines)


def browse_reward_complete(device):
    lines, _, _ = read_screen(
        device, regions=((0, 0, 1, 0.25), (0.65, 0.3, 1, 0.96)),
    )
    return reward_complete(lines)


def allowed_task(title, description):
    text = title + " " + description
    if any(word.lower() in text.lower() for word in EXTERNAL_TASKS + MANUAL_TASKS):
        return False
    if not any(word in text for word in ("浏览", "点击去逛", "点击3个商品", "逛逛", "逛清单", "参与答题")):
        return False
    # Shopping promotions may offer a separate free browse reward.
    if any(word in text for word in ("下单", "充值", "购买")):
        return re.search(r"浏览\d{1,2}(?:秒|[sS])", text) is not None
    return True


def browse_tasks(lines, width, height):
    tasks = []
    actions = ("去完成", "去浏览", "去逛逛", "逛一逛", "去领币", "去看看", "去领取", "立即领", "去搜索")
    for button in lines:
        if button.text not in actions and not re.fullmatch(r"[币市]?\+\d+", button.text):
            continue
        if button.x < width * 0.76 or not height * 0.19 < button.y < height * 0.94:
            continue
        row = [
            line for line in lines
            if width * 0.14 < line.x < width * 0.75
            and abs(line.y - button.y) < height * 0.033
        ]
        row.sort(key=lambda line: (line.y, line.x))
        titles = [
            line for line in row if line.y < button.y
            and not re.fullmatch(r"[币市]?\+?\d*", line.text)
        ]
        if not titles:
            continue
        title = titles[0].text
        description = " ".join(line.text for line in row)
        if not allowed_task(title, description):
            continue
        progress = re.search(r"[(（](\d+)/(\d+)[)）]", title)
        if progress and int(progress.group(1)) >= int(progress.group(2)):
            continue
        seconds = re.search(r"浏览(\d{1,2})(?:秒|[sS])", description)
        duration = min(65, int(seconds.group(1)) + 5) if seconds else 10
        if "逛清单" in description:
            duration = 38
        kind = "browse"
        if "点击3个商品" in description:
            kind = "products"
        elif "参与答题" in description:
            kind = "quiz"
        tasks.append(Task(title, description, button, duration, kind))
    return tasks
