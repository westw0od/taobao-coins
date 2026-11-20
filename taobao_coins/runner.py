"""Run visible coin tasks and recover the task panel.

Adapted from the local coin11-tb coin_rewards.py and task_loop.
Modified to run Taobao-only tasks with bounded browsing and recovery.
"""

import re
import time

from .android import ExternalAppOpened, NeedsAttention, ensure_taobao, require_taobao
from .screen import browse_reward_complete, browse_tasks, click_line, find_line, read_screen


class TaskUnavailable(RuntimeError):
    """A task's expected controls are missing; other tasks may still run."""


def is_panel(lines, height):
    headings = ("今日速赚", "更多金币等你赚", "赚金币抵钱", "今日累计奖励")
    return any(line.y < height * 0.19 and any(word in line.text for word in headings) for line in lines)


def open_panel(device, android_user=0):
    for _ in range(12):
        ensure_taobao(device, android_user)
        screen = read_screen(device)
        lines, _, height = screen
        if is_panel(lines, height):
            return screen
        if find_line(lines, ("消费券",)):
            close = device(description="关闭按钮", clickable=True)
            if close.exists:
                require_taobao(device)
                close.click()
                time.sleep(1)
                continue
        entry = find_line(lines, ("赚更多金币", "签到领金币"))
        if entry:
            print(f"打开入口 {entry.text}", flush=True)
            click_line(device, entry)
            continue
        coin = device(description="领淘金币", clickable=True)
        if coin.exists:
            require_taobao(device)
            coin.click()
            time.sleep(2)
            continue
        my_tab = device(description="我的淘宝", clickable=True)
        if my_tab.exists:
            require_taobao(device)
            my_tab.click()
        else:
            require_taobao(device)
            device.press("back")
        time.sleep(1)
    raise NeedsAttention("十二次导航后仍未找到金币面板，请打开淘宝淘金币页后重新运行。")


def prepare_search(device):
    require_taobao(device)
    source = device.dump_hierarchy()
    expressions = (
        '(//android.widget.TextView[@text="历史搜索"]/following-sibling::android.widget.ListView)/android.view.View[1]',
        '//android.widget.TextView[@text="猜你想搜"]/following-sibling::android.view.View[1]/android.view.View[1]/android.widget.TextView',
        '//*[@resource-id="com.taobao.taobao:id/fl_history_container"]/android.view.ViewGroup/android.widget.FrameLayout[1]',
    )
    for expression in expressions:
        suggestion = device.xpath(expression, source=source)
        if suggestion.exists:
            require_taobao(device)
            print("选择当前搜索建议", flush=True)
            suggestion.click()
            time.sleep(2)
            return
    if "搜索有福利" not in source:
        return
    lines, _, height = read_screen(device)
    history = find_line(lines, ("历史搜索", "猜你想搜", "搜索发现"))
    if history is None:
        raise NeedsAttention("搜索页暂未显示搜索建议，请在手机上完成搜索后重新运行。")
    suggestion = next((
        line for line in lines
        if history.y + height * 0.015 < line.y < history.y + height * 0.13
        and line.text not in ("搜索发现", "历史搜索", "猜你想搜")
    ), None)
    if suggestion is None:
        raise NeedsAttention("当前搜索建议无法识别，请手动选择一项后重新运行。")
    click_line(device, suggestion)


def browse(device, duration):
    prepare_search(device)
    width, height = device.window_size()
    started = time.monotonic()
    deadline = started + duration
    next_check = started
    while time.monotonic() < deadline:
        require_taobao(device)
        if time.monotonic() >= next_check:
            if browse_reward_complete(device):
                print(f"任务已完成，提前返回，浏览阶段耗时 {time.monotonic() - started:.1f} 秒", flush=True)
                return True
            next_check = time.monotonic() + 3
        if time.monotonic() >= deadline:
            break
        require_taobao(device)
        device.swipe(width * 0.5, height * 0.76, width * 0.5, height * 0.51, 0.65)
        time.sleep(2)
    print("浏览等待结束，返回面板核对任务进度", flush=True)
    return False


def click_reward_products(device):
    for _ in range(5):
        lines, _, _ = read_screen(device)
        if find_line(lines, ("恭喜！完成任务", "恭喜!完成任务", "(3/3)", "（3/3）")):
            return
        button = find_line(lines, ("点我得淘金币",))
        if button is None:
            raise TaskUnavailable("商品点击任务未显示奖励入口")
        click_line(device, button)
        product, _, _ = read_screen(device)
        if not find_line(product, ("领券购买", "加入购物车", "立即购买")):
            raise TaskUnavailable("商品详情页未识别到预期内容")
        require_taobao(device)
        device.press("back")
        time.sleep(1)
    raise TaskUnavailable("商品点击任务经过五次检查仍未显示完成")


def answer_quiz(device):
    lines, _, height = read_screen(device)
    question = " ".join(line.text for line in lines)
    known_answers = (
        (("春雨惊春清谷天", "后一句"), "夏满芒夏暑相连"),
        (("亚洲美人鱼", "淡水鱼"), "胭脂鱼"),
        (("辛辣", "植物"), "茱萸"),
        (("淘金币用法", "不对"), "火车站举牌接人服务"),
    )
    choices = list("ABC")
    for words, answer_text in known_answers:
        if all(word in question for word in words):
            answer = find_line(lines, (answer_text,))
            if answer:
                choices.insert(0, answer.text)
            break
    tried = set()
    for choice in choices:
        options = {
            line.text[0]: line for line in lines
            if height * 0.2 < line.y < height * 0.93
            and re.match(r"^[ABC](?:$|[.、．:：\u4e00-\u9fff])", line.text)
        }
        candidate = options.get(choice) if len(choice) == 1 else find_line(lines, (choice,))
        if candidate is None:
            continue
        letter = next((key for key, option in options.items() if abs(option.y - candidate.y) < height * 0.025), choice)
        if letter in tried:
            continue
        tried.add(letter)
        print(f"答题尝试 {letter}", flush=True)
        click_line(device, candidate)
        lines, _, height = read_screen(device)
        submit = find_line(lines, ("我选好了",))
        if submit is None:
            return False
        click_line(device, submit)
        for _ in range(3):
            lines, _, height = read_screen(device)
            correct = find_line(lines, ("回答正确", "答对了", "已成功领取奖励", "奖励领取成功"))
            retry = find_line(lines, ("重新答题", "重新作答", "再答一次", "再试一次", "重新挑战"))
            if correct or retry:
                break
            time.sleep(1)
        if correct:
            claim = next((line for line in lines if line.text in ("领取奖励", "点击领取奖励")), None)
            if claim:
                click_line(device, claim)
            print("答题已显示正确", flush=True)
            return True
        if retry is None:
            print("当前页面未提供重答入口，保留本题", flush=True)
            return False
        if len(tried) < len(options):
            click_line(device, retry)
            lines, _, height = read_screen(device)
    return False


def run(device, android_user=0):
    screen = open_panel(device, android_user)
    _, width, height = screen
    for _ in range(3):
        require_taobao(device)
        device.swipe(width * 0.5, height * 0.4, width * 0.5, height * 0.85, 0.5)
        time.sleep(0.5)
    screen = read_screen(device)
    attempts = {}
    updated = 0
    external = []
    unavailable = []
    idle_pages = 0
    while True:
        lines, width, height = screen
        if not is_panel(lines, height):
            screen = open_panel(device, android_user)
            continue
        arrival = next((
            line for line in lines if line.text == "领取奖励"
            and any("任务到访" in title.text and abs(title.y - line.y) < height * 0.04 for title in lines)
        ), None)
        if arrival:
            print("领取任务到访奖励", flush=True)
            click_line(device, arrival)
            screen = read_screen(device)
            continue
        task = next((item for item in browse_tasks(lines, width, height) if attempts.get(item.title, 0) < 2), None)
        if task:
            attempts[task.title] = attempts.get(task.title, 0) + 1
            started = time.monotonic()
            skipped = False
            print(f"执行免费任务 {task.description}", flush=True)
            try:
                click_line(device, task.button)
                if task.kind == "products":
                    click_reward_products(device)
                elif task.kind == "quiz":
                    if not answer_quiz(device):
                        attempts[task.title] = 2
                else:
                    browse(device, task.duration)
            except TaskUnavailable as error:
                print(f"跳过本项任务 {task.title}：{error}，继续其他任务", flush=True)
                attempts[task.title] = 2
                unavailable.append({"title": task.title, "reason": str(error)})
                skipped = True
            except ExternalAppOpened as error:
                print(f"{error}，返回淘宝并保留本项任务", flush=True)
                attempts[task.title] = 2
                external.append(task.title)
                skipped = True
            screen = open_panel(device, android_user)
            remaining = browse_tasks(*screen)
            if skipped:
                print(f"本项已跳过，未确认完成 {task.title}", flush=True)
            elif not any(item.title == task.title for item in remaining):
                updated += 1
                print(f"进度已更新 {task.title}", flush=True)
            else:
                print(f"进度暂未更新 {task.title}", flush=True)
            print(f"本项耗时 {time.monotonic() - started:.1f} 秒", flush=True)
            idle_pages = 0
            continue
        expand = find_line(lines, ("展开",))
        if expand and find_line(lines, ("更多金币等你赚",)):
            click_line(device, expand)
            screen = read_screen(device)
            idle_pages = 0
            continue
        idle_pages += 1
        if idle_pages >= 7:
            break
        require_taobao(device)
        device.swipe(width * 0.5, height * 0.82, width * 0.5, height * 0.49, 0.65)
        time.sleep(1)
        screen = read_screen(device)
    print(f"扫描结束，本轮观察到 {updated} 次任务进度更新。奖励以手机实际到账为准。", flush=True)
    return {
        "progress_updates": updated,
        "external_tasks_skipped": external,
        "unavailable_tasks_skipped": unavailable,
    }
