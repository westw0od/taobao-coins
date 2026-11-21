package io.github.westw0od.taobaocoins;

import java.util.HashMap;
import java.util.Map;

/** Bounded workflow: panel -> one free task -> panel -> verify progress. */
final class CoinEngine {
    private final CoinService phone;
    private long pausedNanos;
    CoinEngine(CoinService phone) { this.phone = phone; }

    private PageModel.Page openPanel() throws Exception {
        if (!PageModel.TAOBAO.equals(phone.foreground())) phone.launchTaobao();
        Map<String, Integer> clicked = new HashMap<>();
        int backs = 0, unreadable = 0;
        for (int step = 0; step < 12; step++) {
            PageModel.Page page = phone.read();
            if (page.isPanel()) return page;
            PageModel.Line entry = page.navigation("赚更多金币", "签到领金币", "领淘金币");
            if (entry == null && page.find("消费券") != null) entry = page.navigation("关闭按钮");
            if (entry == null) entry = page.navigation("我的淘宝");
            if (entry != null) {
                int count = clicked.getOrDefault(entry.text, 0);
                if (count >= 2) throw new CoinService.Attention("入口“" + entry.text + "”点击后仍未找到任务面板，请手动打开金币任务页");
                clicked.put(entry.text, count + 1);
                unreadable = 0;
                phone.log("打开入口：" + entry.text);
                phone.tap(entry);
            } else {
                // Give a loading page time to settle before any Back action.
                if (++unreadable < 3) { phone.waitMs(800); continue; }
                if (page.find("领淘金币") != null)
                    throw new CoinService.Attention("金币入口被识别成整排图标，未点击。请手动打开淘金币任务页");
                if (++backs > 2) throw new CoinService.Attention("缺少明确导航入口，请手动打开淘金币任务页");
                unreadable = 0;
                phone.back();
            }
            phone.waitMs(700);
        }
        throw new CoinService.Attention("没有找到金币任务面板，请手动打开淘宝的淘金币任务页");
    }
    private void waitForUser(String reason) throws InterruptedException {
        long started = System.nanoTime();
        try { phone.awaitUser(reason); }
        finally { pausedNanos += System.nanoTime() - started; }
    }
    private PageModel.Page recoverPanel() throws Exception {
        while (true) {
            try { return openPanel(); }
            catch (CoinService.Attention | CoinService.LeftTaobao | CoinService.SkipTask error) {
                waitForUser(error.getMessage());
            }
        }
    }
    void run() throws Exception {
        PageModel.Page page = recoverPanel();
        try {
            for (int n = 0; n < 3; n++) phone.swipe(page.width, page.height, true);
            page = phone.read();
        } catch (CoinService.Attention | CoinService.LeftTaobao | CoinService.SkipTask error) {
            waitForUser(error.getMessage());
            page = recoverPanel();
        }
        Map<String, Integer> attempts = new HashMap<>();
        int updated = 0, skipped = 0, idlePages = 0, performed = 0;
        int arrivalAttempts = 0;
        int expandAttempts = 0;
        long deadline = System.nanoTime() - pausedNanos + 45L * 60 * 1_000_000_000;
        while (idlePages < 7 && performed < 100 && System.nanoTime() - pausedNanos < deadline) {
            try {
                phone.checkStopped();
                if (!page.isPanel()) { page = recoverPanel(); continue; }
                if (page.moreTasksExpand() == null) expandAttempts = 0;
                PageModel.Line arrival = page.exact("领取奖励");
                if (arrival != null && arrivalAttempts < 3) {
                    boolean isArrival = false;
                    for (PageModel.Line line : page.lines)
                        if (line.text.contains("任务到访") && Math.abs(line.y - arrival.y) < page.height * .04)
                            isArrival = true;
                    if (isArrival) { arrivalAttempts++; phone.tap(arrival); page = phone.read(); continue; }
                }
                PageModel.Task task = null;
                for (PageModel.Task candidate : page.tasks()) if (attempts.getOrDefault(candidate.title, 0) < 2) {
                    task = candidate; break;
                }
                if (task == null) {
                    PageModel.Line expand = page.moreTasksExpand();
                    if (expand != null) {
                        if (expandAttempts >= 2) {
                            expandAttempts = 0;
                            throw new CoinService.Attention("更多金币任务区展开后仍未变化，请手动展开后点继续");
                        }
                        phone.log("展开更多金币任务");
                        phone.tap(expand);
                        expandAttempts++;
                        idlePages = 0;
                        phone.waitMs(600);
                    }
                    else { idlePages++; phone.swipe(page.width, page.height, false); }
                    page = phone.read();
                    continue;
                }
                idlePages = 0;
                expandAttempts = 0;
                performed++;
                attempts.put(task.title, attempts.getOrDefault(task.title, 0) + 1);
                phone.log("执行：" + task.title);
                boolean failed = false;
                try {
                    phone.tap(task.button);
                    switch (task.kind) {
                        case "quiz": if (!quiz()) throw new CoinService.SkipTask("答题页面暂不支持或没有重答入口"); break;
                        case "products": products(); break;
                        default: browse(task.duration);
                    }
                } catch (CoinService.SkipTask | CoinService.LeftTaobao error) {
                    failed = true;
                    skipped++;
                    attempts.put(task.title, 2);
                    phone.log("跳过：" + task.title + "（" + error.getMessage() + "）");
                } catch (CoinService.Attention error) {
                    // Manual intervention does not consume a task retry or discard
                    // the attempts/progress already accumulated in this run.
                    attempts.put(task.title, attempts.get(task.title) - 1);
                    performed--;
                    waitForUser(error.getMessage());
                }
                page = recoverPanel();
                boolean remains = false;
                for (PageModel.Task remaining : page.tasks()) if (remaining.title.equals(task.title)) remains = true;
                if (!failed && !remains) { updated++; phone.log("已观察到任务进度更新"); }
                else if (!failed) phone.log("进度暂未更新，最多尝试两次");
            } catch (CoinService.Attention | CoinService.LeftTaobao | CoinService.SkipTask error) {
                waitForUser(error.getMessage());
                page = recoverPanel();
                idlePages = 0;
            }
        }
        phone.log("本轮结束：观察到 " + updated + " 次进度更新，跳过 " + skipped + " 项；奖励以淘宝到账为准");
    }
    private void searchSuggestion(PageModel.Page page) throws Exception {
        PageModel.Line history = page.exact("历史搜索", "猜你想搜", "搜索发现");
        if (history == null) {
            if (page.find("搜索有福利") != null) throw new CoinService.SkipTask("搜索页没有可识别的建议");
            return;
        }
        for (PageModel.Line line : page.lines) {
            if (line.y > history.y + page.height * .015 && line.y < history.y + page.height * .13
                && line.x > page.width * .08 && line.x < page.width * .85
                && !PageModel.containsAny(line.text, "历史搜索", "猜你想搜", "搜索发现", "清空", "删除")
                && line.text.length() >= 2 && line.text.length() <= 28) {
                phone.log("选择当前搜索建议");
                phone.tap(line);
                phone.waitMs(800);
                return;
            }
        }
        throw new CoinService.SkipTask("没有找到可点击的搜索建议");
    }
    private void browse(int seconds) throws Exception {
        PageModel.Page page = phone.read();
        searchSuggestion(page);
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        long nextCheck = 0;
        while (System.nanoTime() < deadline) {
            phone.requireTaobao();
            if (System.nanoTime() >= nextCheck) {
                page = phone.read();
                if (page.rewardComplete()) { phone.log("任务显示完成，提前返回"); return; }
                nextCheck = System.nanoTime() + 3_000_000_000L;
            }
            if (System.nanoTime() >= deadline) break;
            phone.swipe(page.width, page.height, false);
            phone.waitMs(500);
        }
    }
    private void products() throws Exception {
        for (int n = 0; n < 5; n++) {
            PageModel.Page page = phone.read();
            if (page.find("恭喜！完成任务", "恭喜!完成任务", "(3/3)", "（3/3）") != null) return;
            PageModel.Line entry = page.exact("点我得淘金币");
            if (entry == null) throw new CoinService.SkipTask("商品任务未显示奖励入口");
            phone.tap(entry);
            PageModel.Page detail = phone.read();
            // These labels only confirm the page; they are never tapped.
            if (detail.find("领券购买", "加入购物车", "立即购买") == null)
                throw new CoinService.SkipTask("没有识别到商品详情页");
            phone.back();
        }
        throw new CoinService.SkipTask("商品任务未确认完成");
    }
    private boolean quiz() throws Exception {
        PageModel.Page page = phone.read();
        for (String choice : new String[]{"A", "B", "C"}) {
            PageModel.Line option = null;
            for (PageModel.Line line : page.lines)
                if (line.y > page.height * .2 && line.y < page.height * .93
                    && line.text.matches("^" + choice + "(?:$|[.、．:：\\p{IsHan}].*)")) {
                    option = line; break;
                }
            if (option == null) continue;
            phone.log("答题尝试 " + choice);
            phone.tap(option);
            page = phone.read();
            PageModel.Line submit = page.exact("我选好了");
            if (submit == null) return false;
            phone.tap(submit);
            PageModel.Line retry = null;
            for (int n = 0; n < 3; n++) {
                page = phone.read();
                if (page.find("回答正确", "答对了", "已成功领取奖励", "奖励领取成功") != null) {
                    PageModel.Line claim = page.exact("领取奖励", "点击领取奖励");
                    if (claim != null) phone.tap(claim);
                    phone.log("答题显示正确");
                    return true;
                }
                retry = page.exact("重新答题", "重新作答", "再答一次", "再试一次", "重新挑战");
                if (retry != null) break;
                phone.waitMs(700);
            }
            if (retry == null || choice.equals("C")) return false;
            phone.tap(retry);
            page = phone.read();
        }
        return false;
    }
}
