package dev.tonpayout.log;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.encoder.EncoderBase;

import dev.tonpayout.json.Json;

/** One JSON object per line, with {@code batchId} taken from MDC. */
public final class JsonLogEncoder extends EncoderBase<ILoggingEvent> {
    @Override
    public byte[] headerBytes() {
        return new byte[0];
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("@timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        row.put("level", event.getLevel().toString());
        row.put("logger", event.getLoggerName());
        row.put("thread", event.getThreadName());
        row.put("message", event.getFormattedMessage());
        String batchId = event.getMDCPropertyMap().get(BatchMdc.BATCH_ID);
        if (batchId != null && !batchId.isBlank()) {
            row.put(BatchMdc.BATCH_ID, batchId);
        }
        IThrowableProxy thrown = event.getThrowableProxy();
        if (thrown != null) {
            String detail = thrown.getMessage() == null ? thrown.getClassName()
                    : thrown.getClassName() + ": " + thrown.getMessage();
            row.put("exception", detail);
        }
        return (Json.stringify(row) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] footerBytes() {
        return new byte[0];
    }
}
