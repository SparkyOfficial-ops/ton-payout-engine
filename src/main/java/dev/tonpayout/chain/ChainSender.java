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
     * Builds and signs the external message. Nothing is sent.
     * The hash is the cell hash and stays the same for these bytes.
     */
    Outgoing prepare(List<Transfer> transfers) throws Exception;

    /** Broadcasts the BOC from {@link #prepare}. */
    void broadcast(Outgoing message) throws Exception;

    /**
     * Signs and broadcasts. Prefer {@link #prepare} when the hash must be stored first.
     */
    default String send(List<Transfer> transfers) throws Exception {
        Outgoing message = prepare(transfers);
        broadcast(message);
        return message.hash();
    }

    /** Wallet balance in nanotons, or null when the address is not configured. */
    String balanceNano() throws Exception;

    /** Signed external message. {@code boc} is a private copy. */
    final class Outgoing {
        private final String hash;
        private final byte[] boc;

        public Outgoing(String hash, byte[] boc) {
            if (hash == null || hash.isBlank()) {
                throw new IllegalArgumentException("message hash is empty");
            }
            if (boc == null || boc.length == 0) {
                throw new IllegalArgumentException("message boc is empty");
            }
            this.hash = hash;
            this.boc = boc.clone();
        }

        public String hash() {
            return hash;
        }

        public byte[] boc() {
            return boc.clone();
        }
    }
}
