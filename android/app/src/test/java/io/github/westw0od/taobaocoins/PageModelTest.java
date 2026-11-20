package io.github.westw0od.taobaocoins;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class PageModelTest {
    private PageModel.Line line(String text, float x, float y) {
        return new PageModel.Line(text, x - 30, y - 15, x + 30, y + 15);
    }
    private PageModel.Page page(PageModel.Line... lines) {
        return new PageModel.Page(new ArrayList<>(Arrays.asList(lines)), 1080, 2340);
    }
    @Test public void freeBrowseIsAllowedButPurchaseIsNot() {
        assertTrue(PageModel.allowed("快速得金币 下单最高10000，浏览10秒得20金币"));
        assertFalse(PageModel.allowed("购买商品 下单得300金币"));
        assertTrue(PageModel.allowed("好物沉浸看(0/3) 浏览15秒 +30"));
    }
    @Test public void externalAndNonFreeTasksAreFiltered() {
        for (String text : List.of("去微信 浏览15秒", "打开百度App 浏览15秒", "去支付宝农场逛逛 浏览5秒",
            "逛UC领红包 浏览5秒", "玩游戏 浏览15秒", "邀请好友 点击去逛", "开通会员 浏览5秒",
            "确认收货 浏览15秒", "订阅 浏览5秒", "分享 浏览5秒")) assertFalse(text, PageModel.allowed(text));
    }
    @Test public void wholeRowIsUsedEvenWhenTitleIsCoinGlyph() {
        PageModel.Page view = page(line("币", 200, 690), line("去支付宝农场逛逛(0/1)", 460, 710),
            line("点击去逛", 450, 760), line("去完成", 960, 745));
        assertTrue(view.tasks().isEmpty());
    }
    @Test public void unfinishedBrowseTaskHasDurationAndCompletedRowsAreSkipped() {
        PageModel.Page view = page(line("好物沉浸看(0/3)", 450, 710), line("浏览15秒", 450, 760), line("去完成", 960, 745));
        assertEquals(1, view.tasks().size());
        assertEquals(20, view.tasks().get(0).duration);
        assertEquals("browse", view.tasks().get(0).kind);
        assertTrue(page(line("好物沉浸看(3/3)", 450, 710), line("浏览15秒", 450, 760), line("去完成", 960, 745)).tasks().isEmpty());
    }
    @Test public void duplicateOcrAndElementButtonsDoNotRepeatTask() {
        PageModel.Line button = line("去完成", 960, 745);
        assertEquals(1, page(line("好物沉浸看(0/3)", 450, 710), line("浏览15秒", 450, 760), button, button).tasks().size());
    }
    @Test public void unsafeAndUnidentifiedRowsAreNotClicked() {
        assertTrue(page(line("去完成", 960, 745)).tasks().isEmpty());
        assertTrue(page(line("邀请好友(0/1)", 450, 710), line("浏览15秒", 450, 760), line("去完成", 960, 745)).tasks().isEmpty());
    }
    @Test public void rewardsDistinguishCompletionFromCountdownAndMoreRewards() {
        assertTrue(page(line("30淘金币已到账", 900, 1800)).rewardComplete());
        assertTrue(page(line("任务已完成", 500, 100)).rewardComplete());
        assertFalse(page(line("浏览15秒可领30金币", 900, 1800)).rewardComplete());
        assertFalse(page(line("任务未完成", 900, 1800)).rewardComplete());
        assertFalse(page(line("30淘金币已到账", 900, 1800), line("继续浏览15秒再得30金币", 900, 1850)).rewardComplete());
        assertFalse(page(line("30淘金币已到账", 400, 900)).rewardComplete());
    }
    @Test public void verificationAndLoginStopButMissingProductButtonDoesNot() {
        assertNotNull(page(line("请向右滑动验证", 500, 900)).verification());
        assertNotNull(page(line("登录后继续", 500, 900)).verification());
        assertNull(page(line("活动页面", 500, 900)).verification());
    }
    @Test public void panelRequiresHeadingNearTop() {
        assertTrue(page(line("今日速赚200淘金币", 500, 250)).isPanel());
        assertFalse(page(line("今日速赚200淘金币", 500, 1500)).isPanel());
    }
    @Test public void navigationNeverUsesJoinedIconRow() {
        PageModel.Page home = page(new PageModel.Line("淘工厂红包签到领淘金币芭芭农场菜鸟驿站", 30, 900, 1050, 955));
        assertNotNull(home.find("领淘金币"));
        assertNull(home.navigation("领淘金币"));
        assertFalse(PageModel.isNavigationLabel("红包签到领淘金币芭芭农场菜鸟驿站"));
    }
    @Test public void exactNavigationPrefersSmallControlAndRejectsWideParent() {
        PageModel.Line parent = new PageModel.Line("领淘金币", 0, 700, 1080, 1100);
        PageModel.Line control = new PageModel.Line("领淘金币", 430, 900, 570, 955);
        assertNull(page(parent).navigation("领淘金币"));
        assertSame(control, page(parent, control).navigation("领淘金币"));
    }
    @Test public void ocrFragmentsKeepOnlyActualLabelBounds() {
        List<PageModel.Line> tokens = List.of(
            new PageModel.Line("红包签到", 160, 900, 300, 950),
            new PageModel.Line("领淘", 440, 900, 510, 950),
            new PageModel.Line("金币", 511, 900, 581, 950),
            new PageModel.Line("芭芭农场", 700, 900, 860, 950));
        List<PageModel.Line> found = PageModel.navigationFragments(tokens, 1080, 2340);
        assertEquals(1, found.size());
        assertEquals("领淘金币", found.get(0).text);
        assertEquals(440f, found.get(0).left, .01f);
        assertEquals(581f, found.get(0).right, .01f);
    }
    @Test public void fragmentsAcrossDifferentRowsOrDistantIconsAreNotCombined() {
        assertTrue(PageModel.navigationFragments(List.of(line("领淘", 300, 900), line("金币", 800, 900)), 1080, 2340).isEmpty());
        assertTrue(PageModel.navigationFragments(List.of(line("领淘", 300, 900), line("金币", 360, 1200)), 1080, 2340).isEmpty());
    }
    @Test public void characterBoxesLocateLabelInsideMergedOcrText() {
        List<PageModel.Line> symbols = List.of(
            new PageModel.Line("红", 140, 900, 170, 940), new PageModel.Line("包", 172, 900, 202, 940),
            new PageModel.Line("领", 440, 900, 470, 940), new PageModel.Line("淘", 472, 900, 502, 940),
            new PageModel.Line("金", 504, 900, 534, 940), new PageModel.Line("币", 536, 900, 566, 940),
            new PageModel.Line("芭", 700, 900, 730, 940));
        List<PageModel.Line> found = PageModel.navigationFragments(symbols, 1080, 2340);
        assertEquals(1, found.size());
        assertEquals("领淘金币", found.get(0).text);
        assertEquals(503f, found.get(0).x, .01f);
    }
}
