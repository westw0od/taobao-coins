package io.github.westw0od.taobaocoins;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Recoverable gesture failures request manual pause; explicit Stop still interrupts. */
final class GestureWait {
    static void await(CompletableFuture<Boolean> result, long timeoutMs, String reason)
        throws CoinService.Attention, InterruptedException {
        try {
            if (!Boolean.TRUE.equals(result.get(timeoutMs, TimeUnit.MILLISECONDS)))
                throw new CoinService.Attention(reason);
        } catch (TimeoutException | ExecutionException | CancellationException error) {
            result.cancel(false); // A delayed main-thread command must not run during manual pause.
            throw new CoinService.Attention(reason);
        } catch (InterruptedException error) {
            result.cancel(false);
            throw error;
        }
    }
}
