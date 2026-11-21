package io.github.westw0od.taobaocoins;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
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
    @Test public void verificationPausesBeforeAnyRecoveryGestureAndStopEndsRun() throws Exception {
        CoinService phone = mock(CoinService.class);
        when(phone.foreground()).thenReturn(PageModel.TAOBAO);
        when(phone.read()).thenReturn(panel(false), panel(false))
            .thenThrow(new CoinService.Attention("手机需要完成验证"));
        doThrow(new InterruptedException()).when(phone).awaitUser(anyString());
        try { new CoinEngine(phone).run(); fail("Stop during manual pause must interrupt the run"); }
        catch (InterruptedException expected) { }
        verify(phone).awaitUser("手机需要完成验证");
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
    @Test public void unresponsiveExpansionPausesAfterTwoAttempts() throws Exception {
        CoinService phone = mock(CoinService.class);
        when(phone.foreground()).thenReturn(PageModel.TAOBAO);
        when(phone.read()).thenReturn(new PageModel.Page(List.of(
            line("更多金币等你赚", 400, 1600), line("展开", 960, 1600)), 1080, 2340));
        doThrow(new InterruptedException()).when(phone).awaitUser(anyString());
        try { new CoinEngine(phone).run(); fail("Unresponsive expansion must pause"); }
        catch (InterruptedException expected) { }
        verify(phone, times(2)).tap(any());
        verify(phone).awaitUser(contains("展开后仍未变化"));
        verify(phone, never()).back();
        verify(phone, never()).log(contains("本轮结束"));
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
    @Test public void loggedJoinedRowIsNeverTappedOrBackedOutOf() throws Exception {
        CoinService phone = mock(CoinService.class);
        when(phone.foreground()).thenReturn(PageModel.TAOBAO);
        when(phone.read()).thenReturn(new PageModel.Page(List.of(new PageModel.Line(
            "淘工厂红包签到领淘金币芭芭农场菜鸟驿站", 30, 900, 1050, 955)), 1080, 2340));
        doThrow(new InterruptedException()).when(phone).awaitUser(anyString());
        try { new CoinEngine(phone).run(); fail("A joined icon row is not a button"); }
        catch (InterruptedException expected) { }
        verify(phone).awaitUser(contains("整排图标"));
        verify(phone, never()).tap(any());
        verify(phone, never()).back();
    }
    @Test public void narrowCoinEntryIsTappedInsteadOfJoinedRow() throws Exception {
        CoinService phone = mock(CoinService.class);
        when(phone.foreground()).thenReturn(PageModel.TAOBAO);
        PageModel.Line coin = new PageModel.Line("领淘金币", 440, 900, 580, 955);
        PageModel.Page home = new PageModel.Page(List.of(new PageModel.Line(
            "红包签到领淘金币芭芭农场菜鸟驿站", 30, 900, 1050, 955), coin), 1080, 2340);
        when(phone.read()).thenReturn(home, panel(false), panel(false));
        doThrow(new InterruptedException()).when(phone).checkStopped();
        try { new CoinEngine(phone).run(); fail("Stop after checking navigation"); }
        catch (InterruptedException expected) { }
        verify(phone).tap(same(coin));
        verify(phone).log("打开入口：领淘金币");
        verify(phone, never()).back();
    }
    @Test public void unchangedNavigationEntryIsNotTappedForever() throws Exception {
        CoinService phone = mock(CoinService.class);
        when(phone.foreground()).thenReturn(PageModel.TAOBAO);
        when(phone.read()).thenReturn(new PageModel.Page(List.of(line("领淘金币", 500, 900)), 1080, 2340));
        doThrow(new InterruptedException()).when(phone).awaitUser(anyString());
        try { new CoinEngine(phone).run(); fail("Unchanged navigation must stop"); }
        catch (InterruptedException expected) { }
        verify(phone).awaitUser(contains("点击后"));
        verify(phone, times(2)).tap(any());
        verify(phone, never()).back();
    }
    @Test public void navigationWaitsWithoutGesturesThenRechecksManualPage() throws Exception {
        CoinService phone = mock(CoinService.class);
        when(phone.foreground()).thenReturn(PageModel.TAOBAO);
        AtomicReference<PageModel.Page> page = new AtomicReference<>(new PageModel.Page(List.of(new PageModel.Line(
            "红包签到领淘金币芭芭农场菜鸟驿站", 30, 900, 1050, 955)), 1080, 2340));
        when(phone.read()).thenAnswer(invocation -> page.get());
        ManualPause gate = new ManualPause();
        CountDownLatch paused = new CountDownLatch(1);
        doAnswer(invocation -> { gate.awaitResume(paused::countDown); return null; }).when(phone).awaitUser(anyString());
        FutureTask<Void> work = new FutureTask<>(() -> { new CoinEngine(phone).run(); return null; });
        Thread thread = new Thread(work);
        thread.start();
        try {
            assertTrue(paused.await(2, TimeUnit.SECONDS));
            assertFalse(work.isDone());
            verify(phone, never()).tap(any());
            verify(phone, never()).back();
            verify(phone, never()).swipe(anyInt(), anyInt(), anyBoolean());
            verify(phone, times(3)).read();
            page.set(new PageModel.Page(List.of(line("今日速赚200淘金币", 500, 250)), 1080, 2340));
            assertTrue(gate.resume());
            work.get(2, TimeUnit.SECONDS);
            verify(phone, times(1)).awaitUser(anyString());
            verify(phone).log(contains("本轮结束"));
        } finally { gate.stop(); thread.interrupt(); thread.join(2000); }
    }
    @Test public void stopWhileWaitingDoesNotReturnToTaobaoOrClickAnything() throws Exception {
        CoinService phone = mock(CoinService.class);
        when(phone.foreground()).thenReturn(PageModel.TAOBAO);
        when(phone.read()).thenThrow(new CoinService.Attention("请手动打开金币任务页"));
        ManualPause gate = new ManualPause();
        CountDownLatch paused = new CountDownLatch(1);
        doAnswer(invocation -> { gate.awaitResume(paused::countDown); return null; }).when(phone).awaitUser(anyString());
        FutureTask<Void> work = new FutureTask<>(() -> { new CoinEngine(phone).run(); return null; });
        Thread thread = new Thread(work);
        thread.start();
        try {
            assertTrue(paused.await(2, TimeUnit.SECONDS));
            gate.stop();
            try { work.get(2, TimeUnit.SECONDS); fail("Stop must interrupt the waiting engine"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof InterruptedException); }
            verify(phone, never()).tap(any());
            verify(phone, never()).back();
            verify(phone, never()).swipe(anyInt(), anyInt(), anyBoolean());
            verify(phone, never()).log(contains("本轮结束"));
        } finally { gate.stop(); thread.interrupt(); thread.join(2000); }
    }
    @Test public void continueRechecksVerificationAndPausesAgainIfUnresolved() throws Exception {
        CoinService phone = mock(CoinService.class);
        when(phone.foreground()).thenReturn(PageModel.TAOBAO);
        PageModel.Page ready = new PageModel.Page(List.of(line("今日速赚200淘金币", 500, 250)), 1080, 2340);
        when(phone.read()).thenThrow(new CoinService.Attention("手机需要完成验证"))
            .thenThrow(new CoinService.Attention("手机需要完成验证")).thenReturn(ready);
        new CoinEngine(phone).run();
        verify(phone, times(2)).awaitUser("手机需要完成验证");
        verify(phone, never()).tap(any());
        verify(phone, never()).back();
    }
    @Test public void verificationResumePreservesPreviouslySkippedTasksAndRetryBudget() throws Exception {
        CoinService phone = mock(CoinService.class);
        when(phone.foreground()).thenReturn(PageModel.TAOBAO);
        AtomicReference<PageModel.Page> current = new AtomicReference<>(panel(true));
        AtomicBoolean verification = new AtomicBoolean(), completed = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger goodClicks = new java.util.concurrent.atomic.AtomicInteger();
        when(phone.read()).thenAnswer(invocation -> {
            if (verification.get()) throw new CoinService.Attention("手机需要完成验证");
            return current.get();
        });
        doAnswer(invocation -> {
            PageModel.Line tapped = invocation.getArgument(0);
            if (tapped.y < 1000) current.set(new PageModel.Page(List.of(line("活动页面", 500, 900)), 1080, 2340));
            else if (goodClicks.incrementAndGet() == 1) verification.set(true);
            else { completed.set(true); current.set(new PageModel.Page(List.of(line("任务已完成", 900, 1800)), 1080, 2340)); }
            return null;
        }).when(phone).tap(any());
        doAnswer(invocation -> { verification.set(false); current.set(panel(true)); return null; }).when(phone).awaitUser(anyString());
        doAnswer(invocation -> { current.set(panel(!completed.get())); return null; }).when(phone).back();
        new CoinEngine(phone).run();
        verify(phone, times(3)).tap(any());
        verify(phone, times(1)).awaitUser("手机需要完成验证");
        verify(phone).log(contains("观察到 1 次进度更新，跳过 1 项"));
    }
    @Test public void quickTaskThenLowerSectionExpandsAgainAfterEachReturn() throws Exception {
        CoinService phone = mock(CoinService.class);
        when(phone.foreground()).thenReturn(PageModel.TAOBAO);
        PageModel.Line expand = line("展开更多任务∨", 960, 1700);
        PageModel.Page collapsed = new PageModel.Page(List.of(
            line("更多金币等你赚", 400, 1700), expand), 1080, 2340);
        PageModel.Page finished = new PageModel.Page(List.of(line("今日速赚200淘金币", 500, 250)), 1080, 2340);
        AtomicReference<PageModel.Page> current = new AtomicReference<>(new PageModel.Page(List.of(
            line("今日速赚200淘金币", 500, 250), line("快速赚(0/1)", 450, 710),
            line("浏览5秒", 450, 760), line("去完成", 960, 745)), 1080, 2340));
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
        when(phone.read()).thenAnswer(invocation -> current.get());
        doAnswer(invocation -> {
            PageModel.Line button = invocation.getArgument(0);
            if (button == expand) {
                // The fixed heading has scrolled away in the expanded section.
                int index = completed.get();
                current.set(new PageModel.Page(List.of(line("剩余浏览" + index + "(0/1)", 450, 1110),
                    line("浏览5秒", 450, 1160), line("去完成", 960, 1145)), 1080, 2340));
            } else {
                completed.incrementAndGet();
                current.set(new PageModel.Page(List.of(line("任务已完成", 900, 1800)), 1080, 2340));
            }
            return null;
        }).when(phone).tap(any());
        doAnswer(invocation -> { current.set(completed.get() < 3 ? collapsed : finished); return null; }).when(phone).back();
        new CoinEngine(phone).run();
        assertEquals(3, completed.get());
        verify(phone, times(2)).tap(same(expand));
        verify(phone, times(5)).tap(any());
        verify(phone, never()).awaitUser(anyString());
        verify(phone, never()).launchTaobao();
        verify(phone).log(contains("观察到 3 次进度更新，跳过 0 项"));
    }
    @Test public void touchInterruptedTaskPausesAndCanRunAfterContinue() throws Exception {
        CoinService phone = mock(CoinService.class);
        when(phone.foreground()).thenReturn(PageModel.TAOBAO);
        PageModel.Page ready = new PageModel.Page(List.of(line("今日速赚200淘金币", 500, 250),
            line("浏览好物(0/1)", 450, 710), line("浏览5秒", 450, 760), line("去完成", 960, 745)), 1080, 2340);
        PageModel.Page done = new PageModel.Page(List.of(line("今日速赚200淘金币", 500, 250)), 1080, 2340);
        AtomicReference<PageModel.Page> current = new AtomicReference<>(ready);
        java.util.concurrent.atomic.AtomicInteger clicks = new java.util.concurrent.atomic.AtomicInteger();
        when(phone.read()).thenAnswer(invocation -> current.get());
        doAnswer(invocation -> {
            if (clicks.incrementAndGet() == 1) throw new CoinService.Attention("自动手势被触屏或系统打断");
            current.set(new PageModel.Page(List.of(line("任务已完成", 900, 1800)), 1080, 2340));
            return null;
        }).when(phone).tap(any());
        doAnswer(invocation -> { current.set(done); return null; }).when(phone).back();
        new CoinEngine(phone).run();
        verify(phone).awaitUser("自动手势被触屏或系统打断");
        verify(phone, times(2)).tap(any());
        verify(phone, never()).requestStop();
        verify(phone).log(contains("观察到 1 次进度更新，跳过 0 项"));
    }
    @Test public void completedRowsWithoutHeadingCanScrollDownToExpansion() throws Exception {
        CoinService phone = mock(CoinService.class);
        when(phone.foreground()).thenReturn(PageModel.TAOBAO);
        PageModel.Page top = new PageModel.Page(List.of(line("今日速赚200淘金币", 500, 250)), 1080, 2340);
        PageModel.Page middle = new PageModel.Page(List.of(line("快速赚(1/1)", 450, 710),
            line("浏览5秒", 450, 760), line("已完成", 960, 745)), 1080, 2340);
        PageModel.Line expand = line("展开", 960, 1600);
        PageModel.Page lower = new PageModel.Page(List.of(line("更多金币等你赚", 400, 1600), expand), 1080, 2340);
        AtomicReference<PageModel.Page> current = new AtomicReference<>(top);
        when(phone.read()).thenAnswer(invocation -> current.get());
        doAnswer(invocation -> {
            boolean down = invocation.getArgument(2);
            if (!down) current.set(current.get() == top ? middle : lower);
            return null;
        }).when(phone).swipe(anyInt(), anyInt(), anyBoolean());
        doThrow(new InterruptedException()).when(phone).tap(same(expand));
        try { new CoinEngine(phone).run(); fail("Stop after verifying expansion is reached"); }
        catch (InterruptedException expected) { }
        verify(phone).tap(same(expand));
        verify(phone, never()).back();
        verify(phone, never()).awaitUser(anyString());
    }
}
