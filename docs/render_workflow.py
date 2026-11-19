"""Render the README workflow diagram with Pillow."""

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


def render(font_path):
    image = Image.new("RGB", (1800, 410), "#fff9f1")
    draw = ImageDraw.Draw(image)
    heading = ImageFont.truetype(str(font_path), 42)
    caption = ImageFont.truetype(str(font_path), 23)
    loop_font = ImageFont.truetype(str(font_path), 25)
    ink = "#282b30"
    muted = "#666b73"
    orange = "#eb702b"

    def centered(text, x, y, font, fill):
        bounds = draw.textbbox((0, 0), text, font=font)
        width = bounds[2] - bounds[0]
        draw.text((x - width / 2, y - bounds[1]), text, font=font, fill=fill)

    nodes = (
        (60, "连接手机", "USB 调试授权", "#fff0df", "#f0b47d"),
        (490, "识别任务", "OCR 读取 · 过滤跨 App", "#ffffff", "#e6dcd0"),
        (920, "自动执行", "浏览 / 搜索 / 点击 / 答题", "#ffffff", "#e6dcd0"),
        (1350, "核对进度", "到账提示 · 面板进度", "#eff9f2", "#afd4b9"),
    )
    for left, title, subtitle, fill, outline in nodes:
        draw.rounded_rectangle((left, 45, left + 390, 225), radius=24, fill=fill, outline=outline, width=2)
        centered(title, left + 195, 89, heading, ink)
        centered(subtitle, left + 195, 158, caption, muted)
        if left < 1350:
            start, end = left + 400, left + 420
            draw.line((start, 135, end, 135), fill=orange, width=4)
            draw.polygon(((end, 135), (end - 10, 128), (end - 10, 142)), fill=orange)

    draw.line(((1545, 225), (1545, 309), (685, 309), (685, 225)), fill=orange, width=3)
    draw.polygon(((685, 225), (677, 237), (693, 237)), fill=orange)
    centered("有可执行任务时，继续下一项", 1115, 337, loop_font, muted)
    output = Path(__file__).with_name("assets") / "workflow.png"
    output.parent.mkdir(exist_ok=True)
    image.save(output, optimize=True)
    print(output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="绘制 README 任务流程图")
    parser.add_argument("--font", default="C:/Windows/Fonts/msyh.ttc", help="支持中文的字体文件")
    args = parser.parse_args()
    render(Path(args.font))
