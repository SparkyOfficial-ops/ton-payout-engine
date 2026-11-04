package dev.tonpayout.metrics;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide counters scraped from {@code GET /metrics} as OpenMetrics text.
 */
public final class EngineMetrics {
    private static final double[] BOUNDS_SECONDS = {1, 5, 15, 30, 60, 120, 300, 600, 960};
    private static final EngineMetrics INSTANCE = new EngineMetrics();

    private final long[] buckets = new long[BOUNDS_SECONDS.length + 1];
    private long observations;
    private double sumSeconds;
    private final AtomicLong toncenterErrors = new AtomicLong();
    private volatile String balanceNano;

    public static EngineMetrics instance() {
        return INSTANCE;
    }

    public void toncenterError() {
        toncenterErrors.incrementAndGet();
    }

    public void noteBalance(String nano) {
        if (nano != null && nano.trim().matches("[0-9]+")) {
            balanceNano = nano.trim();
        }
    }

    public String balanceNano() {
        return balanceNano;
    }

    public synchronized void observedBlock(long durationMs) {
        double seconds = Math.max(0, durationMs) / 1000.0;
        sumSeconds += seconds;
        observations++;
        int index = BOUNDS_SECONDS.length;
        for (int i = 0; i < BOUNDS_SECONDS.length; i++) {
            if (seconds <= BOUNDS_SECONDS[i]) {
                index = i;
                break;
            }
        }
        buckets[index]++;
    }

    public synchronized String render(long queueSize) {
        StringBuilder text = new StringBuilder();
        gauge(text, "payout_queue_size", "Transfers waiting in the payout queue", Long.toString(queueSize));
        String balance = balanceNano;
        if (balance != null) {
            gauge(text, "wallet_balance_nano", "Wallet balance in nanotons", balance);
        } else {
            type(text, "wallet_balance_nano", "gauge", "Wallet balance in nanotons");
        }
        type(text, "batch_processing_duration_seconds", "histogram",
                "Seconds from payout accept until the batch is in a block");
        long cumulative = 0;
        for (int i = 0; i < BOUNDS_SECONDS.length; i++) {
            cumulative += buckets[i];
            text.append("batch_processing_duration_seconds_bucket{le=\"")
                    .append(formatBound(BOUNDS_SECONDS[i]))
                    .append("\"} ")
                    .append(cumulative)
                    .append('\n');
        }
        cumulative += buckets[BOUNDS_SECONDS.length];
        text.append("batch_processing_duration_seconds_bucket{le=\"+Inf\"} ")
                .append(cumulative)
                .append('\n');
        text.append("batch_processing_duration_seconds_sum ")
                .append(String.format(Locale.US, "%.3f", sumSeconds))
                .append('\n');
        text.append("batch_processing_duration_seconds_count ")
                .append(observations)
                .append('\n');
        type(text, "toncenter_errors_total", "counter", "Toncenter API failures");
        text.append("toncenter_errors_total ").append(toncenterErrors.get()).append('\n');
        text.append("# EOF\n");
        return text.toString();
    }

    private static void gauge(StringBuilder text, String name, String help, String value) {
        type(text, name, "gauge", help);
        text.append(name).append(' ').append(value).append('\n');
    }

    private static void type(StringBuilder text, String name, String kind, String help) {
        text.append("# TYPE ").append(name).append(' ').append(kind).append('\n');
        text.append("# HELP ").append(name).append(' ').append(help).append('\n');
    }

    private static String formatBound(double bound) {
        if (bound == Math.rint(bound)) {
            return Long.toString((long) bound);
        }
        return String.format(Locale.US, "%s", bound);
    }

    synchronized void resetForTest() {
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = 0;
        }
        observations = 0;
        sumSeconds = 0;
        toncenterErrors.set(0);
        balanceNano = null;
    }
}
