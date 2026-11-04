package dev.tonpayout.queue;

import dev.tonpayout.chain.Currency;

/**
 * {@code bounceable} is false when the payout address was {@code UQ} or {@code 0Q}.
 * The stored {@code to} is still the raw account id, which has no bounce bit of its own.
 * {@code amountNano} is nanotons for {@code TON} and jetton base units (6 decimals) for {@code USDT}.
 */
public record Transfer(String to, String amountNano, String comment, boolean bounceable, String currency) {
    public Transfer(String to, String amountNano, String comment, boolean bounceable) {
        this(to, amountNano, comment, bounceable, Currency.TON);
    }

    public boolean usdt() {
        return Currency.USDT.equals(currency);
    }
}
