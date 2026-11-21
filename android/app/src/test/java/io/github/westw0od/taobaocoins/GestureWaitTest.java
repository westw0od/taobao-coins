package io.github.westw0od.taobaocoins;

import org.junit.Test;
import java.util.concurrent.CompletableFuture;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class GestureWaitTest {
    @Test public void successCompletesNormally() throws Exception {
        GestureWait.await(CompletableFuture.completedFuture(true), 50, "manual pause");
    }
    @Test public void cancelledAndFailedGesturesRequestAttentionInsteadOfSkippingTask() throws Exception {
        CompletableFuture<Boolean> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("dispatch failed"));
        CompletableFuture<Boolean> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        for (CompletableFuture<Boolean> result : java.util.List.of(CompletableFuture.completedFuture(false), failed, cancelled)) {
            try { GestureWait.await(result, 50, "请处理触屏后点继续"); fail("Interrupted gesture must pause"); }
            catch (CoinService.Attention expected) { assertTrue(expected.getMessage().contains("继续")); }
        }
    }
    @Test public void gestureTimeoutAlsoRequestsPause() throws Exception {
        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        try { GestureWait.await(pending, 1, "手势等待超时"); fail("Timeout must pause"); }
        catch (CoinService.Attention expected) { assertEquals("手势等待超时", expected.getMessage()); }
        assertTrue(pending.isCancelled());
    }
    @Test public void explicitThreadInterruptionStillStopsImmediately() throws Exception {
        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        Thread.currentThread().interrupt();
        try { GestureWait.await(pending, 1000, "manual pause"); fail("Stop must interrupt"); }
        catch (InterruptedException expected) { }
        finally { Thread.interrupted(); }
        assertTrue(pending.isCancelled());
    }
    @Test public void accessibilityInterruptRequestsPauseNotStop() {
        CoinService service = mock(CoinService.class);
        doCallRealMethod().when(service).onInterrupt();
        service.onInterrupt();
        verify(service).requestPause(contains("系统中断"));
        verify(service, never()).requestStop();
    }
}
