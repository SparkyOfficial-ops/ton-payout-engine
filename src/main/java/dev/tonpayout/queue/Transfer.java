package dev.tonpayout.queue;

public record Transfer(String to, String amountNano, String comment) {
}
