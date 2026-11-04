package dev.tonpayout.metrics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EngineMetricsTest {
    @Test
    void histogramAndCounterRenderAsOpenMetrics() {
        EngineMetrics metrics = EngineMetrics.instance();
        metrics.resetForTest();
        metrics.observedBlock(12_000);
        metrics.observedBlock(2_000_000);
        metrics.toncenterError();
        metrics.noteBalance("not-a-number");
        String emptyBalance = metrics.render(3);
        assertFalse(emptyBalance.lines().anyMatch(line -> line.startsWith("wallet_balance_nano ")));
        metrics.noteBalance("42");
        String text = metrics.render(3);
        assertTrue(text.contains("payout_queue_size 3\n"));
        assertTrue(text.contains("wallet_balance_nano 42\n"));
        assertTrue(text.contains("batch_processing_duration_seconds_bucket{le=\"5\"} 0\n"));
        assertTrue(text.contains("batch_processing_duration_seconds_bucket{le=\"15\"} 1\n"));
        assertTrue(text.contains("batch_processing_duration_seconds_bucket{le=\"960\"} 1\n"));
        assertTrue(text.contains("batch_processing_duration_seconds_bucket{le=\"+Inf\"} 2\n"));
        assertTrue(text.contains("batch_processing_duration_seconds_count 2\n"));
        assertTrue(text.contains("toncenter_errors_total 1\n"));
        assertTrue(text.endsWith("# EOF\n"));
        metrics.resetForTest();
    }
}
