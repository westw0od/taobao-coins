package io.github.westw0od.taobaocoins;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class ManualPauseTest {
    @Test public void eachPauseNeedsItsOwnExplicitContinue() throws Exception {
        ManualPause gate = new ManualPause();
        assertFalse(gate.resume()); // An early click cannot approve a future pause.
        for (int n = 0; n < 2; n++) {
            CountDownLatch paused = new CountDownLatch(1);
            FutureTask<Void> task = new FutureTask<>(() -> { gate.awaitResume(paused::countDown); return null; });
            Thread thread = new Thread(task);
            thread.start();
            try {
                assertTrue(paused.await(2, TimeUnit.SECONDS));
                assertTrue(gate.isPaused());
                assertFalse(task.isDone());
                assertTrue(gate.resume());
                task.get(2, TimeUnit.SECONDS);
                assertFalse(gate.isPaused());
                assertFalse(gate.resume());
            } finally { thread.interrupt(); thread.join(2000); }
        }
    }
    @Test public void stopWakesWaiterAndOnlyNewRunCanResetIt() throws Exception {
        ManualPause gate = new ManualPause();
        CountDownLatch paused = new CountDownLatch(1);
        FutureTask<Void> task = new FutureTask<>(() -> { gate.awaitResume(paused::countDown); return null; });
        Thread thread = new Thread(task);
        thread.start();
        try {
            assertTrue(paused.await(2, TimeUnit.SECONDS));
            gate.stop();
            assertFalse(gate.resume());
            try { task.get(2, TimeUnit.SECONDS); fail("Stopped wait must throw"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof InterruptedException); }
            try { gate.awaitResume(() -> fail("Stopped run must not pause again")); fail("Stopped run must throw"); }
            catch (InterruptedException expected) { }
            gate.reset();
            gate.awaitResume(() -> assertTrue(gate.resume()));
        } finally { gate.stop(); thread.interrupt(); thread.join(2000); }
    }
}
