package io.github.westw0od.taobaocoins;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CoinEngineTest {
    private PageModel.Line line(String text, float x, float y) {
        return new PageModel.Line(text, x - 30, y - 15, x + 30, y + 15);
    }
    private PageModel.Page panel(boolean goodTask) {
        List<PageModel.Line> lines = new ArrayList<>(List.of(
            line("今日速赚200淘金币", 500, 250),
            line("点击商品任务(0/1)", 450, 710), line("点击3个商品立得", 450, 760), line("去完成", 960, 745)));
        if (goodTask) lines.addAll(List.of(line("浏览好物(0/1)", 450, 1210),
            line("浏览15秒", 450, 1260), line("去完成", 960, 1245)));
        return new PageModel.Page(lines, 1080, 2340);
    }
    @Test public void missingProductEntrySkipsOnlyThatTaskAndDoesNotCountItCompleted() throws Exception {
        CoinService phone = mock(CoinService.class);
        when(phone.foreground()).thenReturn(PageModel.TAOBAO);
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<PageModel.Page> current = new AtomicReference<>(panel(true));
        when(phone.read()).thenAnswer(invocation -> current.get());
        doAnswer(invocation -> {
            PageModel.Line tapped = invocation.getArgument(0);
            if (tapped.y < 1000) current.set(new PageModel.Page(List.of(line("活动页面", 500, 900)), 1080, 2340));
            else {
                completed.set(true);
                current.set(new PageModel.Page(List.of(line("任务已完成", 900, 1800)), 1080, 2340));
            }
            return null;
        }).when(phone).tap(any());
        doAnswer(invocation -> { current.set(panel(!completed.get())); return null; }).when(phone).back();
        new CoinEngine(phone).run();
        verify(phone, times(2)).tap(any());
        verify(phone).log(contains("跳过：点击商品任务"));
        verify(phone).log(contains("观察到 1 次进度更新，跳过 1 项"));
    }
    @Test public void verificationPropagatesWithoutMoreGestures() throws Exception {
        CoinService phone = mock(CoinService.class);
        when(phone.foreground()).thenReturn(PageModel.TAOBAO);
        when(phone.read()).thenReturn(panel(false), panel(false))
            .thenThrow(new CoinService.Attention("手机需要完成验证"));
        try { new CoinEngine(phone).run(); fail("Verification must stop the run"); }
        catch (CoinService.Attention expected) { assertTrue(expected.getMessage().contains("验证")); }
        verify(phone, times(1)).tap(any());
        verify(phone, never()).back();
        verify(phone, never()).log(contains("本轮结束"));
    }
    @Test public void stopInterruptsBeforeAnyTaskClick() throws Exception {
        CoinService phone = mock(CoinService.class);
        when(phone.foreground()).thenReturn(PageModel.TAOBAO);
        when(phone.read()).thenReturn(panel(false));
        doThrow(new InterruptedException()).when(phone).checkStopped();
        try { new CoinEngine(phone).run(); fail("Stop must interrupt the run"); }
        catch (InterruptedException expected) { }
        verify(phone, never()).tap(any());
        verify(phone, never()).log(contains("本轮结束"));
    }
    @Test public void unresponsiveExpandButtonIsOnlyTappedOnce() throws Exception {
        CoinService phone = mock(CoinService.class);
        when(phone.foreground()).thenReturn(PageModel.TAOBAO);
        when(phone.read()).thenReturn(new PageModel.Page(List.of(
            line("更多金币等你赚", 500, 250), line("展开", 500, 1900)), 1080, 2340));
        new CoinEngine(phone).run();
        verify(phone, times(1)).tap(any());
        verify(phone).log(contains("本轮结束"));
    }
    @Test public void staleArrivalRewardIsBounded() throws Exception {
        CoinService phone = mock(CoinService.class);
        when(phone.foreground()).thenReturn(PageModel.TAOBAO);
        when(phone.read()).thenReturn(new PageModel.Page(List.of(
            line("今日速赚200淘金币", 500, 250), line("任务到访", 450, 720),
            line("领取奖励", 960, 745)), 1080, 2340));
        new CoinEngine(phone).run();
        verify(phone, times(3)).tap(any());
        verify(phone).log(contains("本轮结束"));
    }
}
