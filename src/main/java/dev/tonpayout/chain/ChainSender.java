package dev.tonpayout.chain;

import java.util.List;

import dev.tonpayout.queue.Transfer;

/**
 * Sends one highload batch. Implementations must accept at most 254 transfers.
 * Amounts are nanotons.
 */
public interface ChainSender {
    /** True when the wallet address and the private-key file are both configured. */
    boolean isReady();

    /**
     * Broadcasts the batch.
     * @return message hash when the chain client exposes one, otherwise null
     */
    String send(List<Transfer> transfers) throws Exception;

    /** Wallet balance in nanotons, or null when the address is not configured. */
    String balanceNano() throws Exception;
}
