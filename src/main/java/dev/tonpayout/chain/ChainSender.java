package dev.tonpayout.chain;

import java.util.List;

import dev.tonpayout.queue.Transfer;

/**
 * Port to the existing cton-sdk highload wallet.
 * Real send belongs in a later class that calls
 * {@code com.cton.contract.HighloadWallet.sendBulkTransfer(BulkTransfer[], Crypto.PrivateKey)}.
 * One call carries at most 254 transfers. Amounts are nanotons.
 */
public interface ChainSender {
    boolean isReady();

    String send(List<Transfer> transfers) throws Exception;
}
