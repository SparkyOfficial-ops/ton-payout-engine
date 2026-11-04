package dev.tonpayout.log;

import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import org.slf4j.bridge.SLF4JBridgeHandler;

/** Sends {@code java.util.logging} through SLF4J into the Logback JSON appender. */
public final class LoggingSetup {
    private LoggingSetup() {
    }

    public static void install() {
        LogManager.getLogManager().reset();
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
        Logger.getLogger("").setLevel(Level.INFO);
    }
}
