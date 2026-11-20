package io.github.westw0od.taobaocoins;

/** One explicit Continue is required for each pause; Stop always wakes the worker. */
final class ManualPause {
    private boolean paused, stopped;

    synchronized void reset() { paused = false; stopped = false; }
    synchronized boolean isPaused() { return paused; }
    synchronized void stop() { stopped = true; paused = false; notifyAll(); }
    synchronized boolean resume() {
        if (!paused || stopped) return false;
        paused = false;
        notifyAll();
        return true;
    }
    void awaitResume(Runnable onPaused) throws InterruptedException {
        synchronized (this) {
            if (stopped || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            paused = true;
        }
        onPaused.run();
        synchronized (this) {
            while (paused && !stopped) wait();
            if (stopped || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        }
    }
}
