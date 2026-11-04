package dev.tonpayout.log;

import org.slf4j.MDC;

/** Puts {@code batchId} on the current thread so JSON logs can be filtered by it. */
public final class BatchMdc {
    public static final String BATCH_ID = "batchId";

    private BatchMdc() {
    }

    public static void set(String batchId) {
        if (batchId == null || batchId.isBlank()) {
            MDC.remove(BATCH_ID);
            return;
        }
        MDC.put(BATCH_ID, batchId);
    }

    public static void clear() {
        MDC.remove(BATCH_ID);
    }
}
