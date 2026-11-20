package io.github.westw0od.taobaocoins;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Device-independent rules shared by OCR and accessibility text. */
public final class PageModel {
    public static final String TAOBAO = "com.taobao.taobao";
    private static final String[] EXTERNAL = {
        "微信", "支付宝", "蚂蚁", "神奇海洋", "百度", "UC", "闲鱼", "点淘", "抖音",
        "快手", "微博", "京东", "拼多多", "饿了么", "美团", "菜鸟", "天猫App", "淘特",
        "淘宝特价版", "一淘", "跨App", "打开其他应用", "跳转应用", "金豆"
    };
    private static final String[] MANUAL = {
        "付款", "支付订单", "确认收货", "借金币", "分享", "邀请", "开通", "捐", "拍立淘",
        "订阅", "添加桌面", "桌面组件", "游戏", "斗地主", "消消乐", "三国", "闯关",
        "试玩", "种树", "浇水"
    };
    private static final Set<String> ACTIONS = Set.of(
        "去完成", "去浏览", "去逛逛", "逛一逛", "去领币", "去看看", "去领取", "立即领", "去搜索"
    );
    private static final Pattern SECONDS = Pattern.compile("浏览(\\d{1,2})(?:秒|[sS])");
    private static final Pattern PROGRESS = Pattern.compile("[(（](\\d+)/(\\d+)[)）]");
    private static final Set<String> NAVIGATION = Set.of("领淘金币", "我的淘宝", "关闭按钮", "赚更多金币", "签到领金币");

    public static final class Line {
        public final String text;
        public final float x, y, left, top, right, bottom;
        public Line(String text, float left, float top, float right, float bottom) {
            this.text = text.replaceAll("\\s+", "");
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
            x = (left + right) / 2; y = (top + bottom) / 2;
        }
    }

    public static final class Page {
        public final List<Line> lines;
        public final int width, height;
        public Page(List<Line> lines, int width, int height) {
            this.lines = lines; this.width = width; this.height = height;
        }
        public Line find(String... words) {
            for (String word : words) for (Line line : lines)
                if (line.text.contains(word)) return line;
            return null;
        }
        public Line exact(String... words) {
            for (String word : words) for (Line line : lines)
                if (line.text.equals(word)) return line;
            return null;
        }
        public Line navigation(String... labels) {
            // A joined OCR row or a full-width accessibility parent is never a button.
            for (String label : labels) {
                Line best = null;
                for (Line line : lines) if (line.text.equals(label) && validNavigationBounds(line, width, height))
                    if (best == null || area(line) < area(best)) best = line;
                if (best != null) return best;
            }
            return null;
        }
        public boolean isPanel() {
            for (Line line : lines)
                if (line.y < height * .19 && containsAny(line.text,
                    "今日速赚", "更多金币等你赚", "赚金币抵钱", "今日累计奖励")) return true;
            return false;
        }
        public String verification() {
            if (find("验证码", "滑块验证", "拖动滑块", "向右滑动验证", "向右滑动完成验证",
                     "安全验证", "请完成验证") != null) return "手机需要完成验证，请手动处理后点继续";
            if (exact("亲，请登录", "登录/注册", "登录后继续", "登录后领取") != null)
                return "请先在淘宝中登录，再点继续";
            return null;
        }
        public boolean rewardComplete() {
            // Restrict completion checks to the task badge, not product descriptions.
            StringBuilder status = new StringBuilder();
            for (Line line : lines) {
                if (line.y < height * .25 || (line.x > width * .65 && line.y > height * .3))
                    status.append(line.text);
            }
            String text = status.toString();
            if (Pattern.compile("(?:再|继续)(?:逛|浏览|看).{0,8}\\d+秒").matcher(text).find()) return false;
            return Pattern.compile("淘?金币(?:已|已经)到账|任务已完成|浏览已完成").matcher(text).find()
                || (exact("任务完成", "任务完成！", "浏览完成", "浏览完成！") != null);
        }
        public List<Task> tasks() {
            List<Task> tasks = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Line button : lines) {
                if (!isTaskAction(button.text) || button.x < width * .76
                    || button.y < height * .19 || button.y > height * .94) continue;
                List<Line> row = new ArrayList<>();
                for (Line line : lines) if (line.x > width * .14 && line.x < width * .75
                    && line.right < button.left + width * .02
                    && Math.abs(line.y - button.y) < height * .034) row.add(line);
                row.sort(Comparator.comparingDouble((Line line) -> line.y).thenComparingDouble(line -> line.x));
                Line title = null;
                StringBuilder description = new StringBuilder();
                for (Line line : row) {
                    description.append(line.text).append(' ');
                    if (title == null && line.y < button.y && !line.text.matches("[币市]?\\+?\\d*")) title = line;
                }
                if (title == null || !allowed(title.text + " " + description)) continue;
                Matcher progress = PROGRESS.matcher(title.text);
                if (progress.find() && Integer.parseInt(progress.group(1)) >= Integer.parseInt(progress.group(2))) continue;
                Matcher seconds = SECONDS.matcher(description);
                int duration = seconds.find() ? Math.min(65, Integer.parseInt(seconds.group(1)) + 5) : 10;
                if (description.indexOf("逛清单") >= 0) duration = 38;
                String kind = description.indexOf("点击3个商品") >= 0 ? "products"
                    : description.indexOf("参与答题") >= 0 ? "quiz" : "browse";
                if (seen.add(title.text)) tasks.add(new Task(title.text, description.toString(), kind, duration, button));
            }
            return tasks;
        }
    }

    public static final class Task {
        public final String title, description, kind;
        public final int duration;
        public final Line button;
        public Task(String title, String description, String kind, int duration, Line button) {
            this.title = title; this.description = description; this.kind = kind;
            this.duration = duration; this.button = button;
        }
    }

    public static boolean isTaskAction(String text) {
        return ACTIONS.contains(text) || text.matches("[币市]?\\+\\d+");
    }
    public static boolean isNavigationLabel(String text) { return NAVIGATION.contains(text.replaceAll("\\s+", "")); }
    public static boolean validNavigationBounds(Line line, int width, int height) {
        return line.left >= 0 && line.top >= 0 && line.right <= width && line.bottom <= height
            && line.right > line.left && line.bottom > line.top
            && line.right - line.left <= width * .65 && line.bottom - line.top <= height * .18;
    }
    private static float area(Line line) { return (line.right - line.left) * (line.bottom - line.top); }
    public static List<Line> navigationFragments(List<Line> fragments, int width, int height) {
        List<Line> found = new ArrayList<>();
        for (int start = 0; start < fragments.size(); start++) {
            Line first = fragments.get(start), previous = first;
            StringBuilder text = new StringBuilder();
            float left = first.left, top = first.top, right = first.right, bottom = first.bottom;
            for (int end = start; end < Math.min(start + 8, fragments.size()); end++) {
                Line next = fragments.get(end);
                float rowHeight = Math.max(previous.bottom - previous.top, next.bottom - next.top);
                if (end > start && (Math.abs(next.y - previous.y) > rowHeight * .6
                    || next.left < previous.right - rowHeight * .2 || next.left - previous.right > rowHeight * .9)) break;
                text.append(next.text);
                left = Math.min(left, next.left); top = Math.min(top, next.top);
                right = Math.max(right, next.right); bottom = Math.max(bottom, next.bottom);
                Line candidate = new Line(text.toString(), left, top, right, bottom);
                if (isNavigationLabel(candidate.text) && validNavigationBounds(candidate, width, height)) found.add(candidate);
                previous = next;
                if (text.length() > 6) break;
            }
        }
        return found;
    }
    public static boolean allowed(String text) {
        for (String term : EXTERNAL) if (text.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT))) return false;
        if (containsAny(text, MANUAL)) return false;
        if (!containsAny(text, "浏览", "点击去逛", "点击3个商品", "逛逛", "逛清单", "参与答题")) return false;
        return !containsAny(text, "下单", "充值", "购买") || SECONDS.matcher(text).find();
    }
    public static boolean containsAny(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }
}
