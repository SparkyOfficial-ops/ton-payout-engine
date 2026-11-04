package dev.tonpayout.chain;

import java.util.Locale;

public final class Currency {
    public static final String TON = "TON";
    public static final String USDT = "USDT";

    private Currency() {
    }

    public static String parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return TON;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!TON.equals(value) && !USDT.equals(value)) {
            throw new IllegalArgumentException("currency must be TON or USDT");
        }
        return value;
    }

    public static int decimals(String currency) {
        return USDT.equals(currency) ? 6 : 9;
    }
}
