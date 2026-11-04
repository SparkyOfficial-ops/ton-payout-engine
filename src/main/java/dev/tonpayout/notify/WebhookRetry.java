package dev.tonpayout.notify;

/**
 * Delays after a failed delivery: 5s, 30s, 2m, then 10m for every later attempt.
 * {@code attempts} is the number of tries already taken, starting at 1.
 */
public final class WebhookRetry {
    private static final long[] DELAYS_MS = {5_000L, 30_000L, 120_000L, 600_000L};

    private WebhookRetry() {
    }

    public static long delayMs(int attempts) {
        int index = Math.max(0, attempts - 1);
        if (index >= DELAYS_MS.length) {
            return DELAYS_MS[DELAYS_MS.length - 1];
        }
        return DELAYS_MS[index];
    }
}
