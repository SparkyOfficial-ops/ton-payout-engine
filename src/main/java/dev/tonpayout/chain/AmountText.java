package dev.tonpayout.chain;

import java.math.BigDecimal;

public final class AmountText {
    private AmountText() {
    }

    /** Decimal amount in TON (9 places) or USDT (6 places), without trailing zeros. */
    public static String fromNano(String amountNano, String currency) {
        int scale = Currency.decimals(currency == null || currency.isBlank() ? Currency.TON : currency);
        return new BigDecimal(amountNano).movePointLeft(scale).stripTrailingZeros().toPlainString();
    }
}
