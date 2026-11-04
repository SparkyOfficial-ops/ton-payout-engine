package dev.tonpayout.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;

class JsonLogTest {
    @Test
    void batchIdStaysOnTheThreadThatSetIt() {
        BatchMdc.clear();
        BatchMdc.set("batch-1");
        assertEquals("batch-1", org.slf4j.MDC.get(BatchMdc.BATCH_ID));
        BatchMdc.clear();
        assertNull(org.slf4j.MDC.get(BatchMdc.BATCH_ID));
    }

    @Test
    void jsonLineCarriesBatchIdFromMdc() {
        LoggingEvent event = new LoggingEvent();
        event.setTimeStamp(0);
        event.setLevel(Level.INFO);
        event.setLoggerName("dev.tonpayout.queue.PayoutBatcher");
        event.setMessage("accepted payout");
        event.setMDCPropertyMap(Map.of(BatchMdc.BATCH_ID, "batch-1"));
        String line = new String(new JsonLogEncoder().encode(event), StandardCharsets.UTF_8);
        assertTrue(line.startsWith("{"));
        assertTrue(line.endsWith("}\n"));
        assertTrue(line.contains("\"batchId\":\"batch-1\""));
        assertTrue(line.contains("\"message\":\"accepted payout\""));
        assertTrue(line.contains("\"level\":\"INFO\""));
        assertFalse(line.contains("\n{"));
    }
}
