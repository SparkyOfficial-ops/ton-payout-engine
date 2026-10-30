package dev.tonpayout.chain;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;

import com.cton.api.TonApiClient;
import com.cton.contract.BaseWallet;
import com.cton.contract.BulkTransfer;
import com.cton.contract.HighloadWallet;
import com.cton.sdk.Address;
import com.cton.sdk.Crypto;

import dev.tonpayout.config.EngineConfig;
import dev.tonpayout.queue.Transfer;

/**
 * Uses four types from cton-sdk:
 * TonApiClient, HighloadWallet, BulkTransfer, Address, and Crypto.PrivateKey.
 * Cell, Boc, and HashmapE stay inside HighloadWallet.sendBulkTransfer.
 */
public final class CtonChainSender implements ChainSender {
    private static final Logger LOG = Logger.getLogger(CtonChainSender.class.getName());

    private final EngineConfig config;
    private final TonApiClient api;
    private final Path keyPath;

    public CtonChainSender(EngineConfig config) {
        this.config = config;
        this.keyPath = Path.of(config.walletKeyPath());
        String apiKey = config.tonApiKey().isBlank() ? null : config.tonApiKey();
        this.api = new TonApiClient(config.tonEndpoint(), apiKey);
    }

    @Override
    public boolean isReady() {
        return !config.walletAddress().isBlank() && Files.isRegularFile(keyPath);
    }

    @Override
    public Outgoing prepare(List<Transfer> transfers) throws Exception {
        if (transfers.isEmpty() || transfers.size() > 254) {
            throw new IllegalArgumentException("a highload batch must contain 1..254 transfers");
        }
        if (!isReady()) {
            throw new IllegalStateException("wallet address or key file is missing");
        }

        List<Address> destinations = new ArrayList<>(transfers.size());
        try (Crypto.PrivateKey key = loadKey();
             Address self = new Address(config.walletAddress())) {
            HighloadWallet wallet = new HighloadWallet(
                    self, api, config.subwalletId(), queryTtlSeconds(config.messageTtlMs()));
            BulkTransfer[] bulk = new BulkTransfer[transfers.size()];
            for (int i = 0; i < transfers.size(); i++) {
                Transfer transfer = transfers.get(i);
                Address destination = new Address(transfer.to());
                destinations.add(destination);
                String comment = transfer.comment() == null || transfer.comment().isBlank()
                        ? null
                        : transfer.comment();
                bulk[i] = new BulkTransfer(destination, new BigInteger(transfer.amountNano()), comment);
            }
            HighloadWallet.PreparedBulk prepared = wallet.prepareBulkTransfer(bulk, key);
            return new Outgoing(prepared.hashHex(), prepared.boc());
        } finally {
            for (Address destination : destinations) {
                destination.close();
            }
        }
    }

    @Override
    public void broadcast(Outgoing message) throws Exception {
        String reported = BaseWallet.messageHash(api.sendBoc(message.boc()));
        if (!MessageHashes.normalize(message.hash()).equals(MessageHashes.normalize(reported))) {
            LOG.warning("toncenter sendBoc hash " + reported
                    + " differs from the stored message hash " + message.hash());
        }
    }

    /**
     * On-chain lifetime of the external message. The engine marks the batch failed
     * only after {@code messageTtlMs}, one minute later.
     */
    public static int queryTtlSeconds(long messageTtlMs) {
        long seconds = (messageTtlMs - 60_000L) / 1000L;
        if (seconds < 15 * 60L || seconds > 24 * 60 * 60L) {
            throw new IllegalArgumentException("highload query ttl is out of range");
        }
        return (int) seconds;
    }

    @Override
    public String balanceNano() throws Exception {
        if (config.walletAddress().isBlank()) {
            return null;
        }
        try (Address self = new Address(config.walletAddress())) {
            HighloadWallet wallet = new HighloadWallet(self, api, config.subwalletId());
            return wallet.getBalance().toString();
        }
    }

    private Crypto.PrivateKey loadKey() throws Exception {
        byte[] bytes = Files.readAllBytes(keyPath);
        if (bytes.length == 32) {
            return new Crypto.PrivateKey(bytes);
        }
        String text = new String(bytes, StandardCharsets.UTF_8).trim();
        if (text.regionMatches(true, 0, "0x", 0, 2)) {
            text = text.substring(2).trim();
        }
        if (text.matches("(?i)[0-9a-f]{64}")) {
            return new Crypto.PrivateKey(hex(text));
        }
        if (text.indexOf(' ') >= 0) {
            List<String> words = new ArrayList<>();
            for (String word : text.split("\\s+")) {
                if (!word.isBlank()) {
                    words.add(word);
                }
            }
            return Crypto.mnemonicToPrivateKey(words);
        }
        byte[] decoded;
        try {
            decoded = Base64.getMimeDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "wallet key must be 32 raw bytes, 64 hex characters, base64, or a mnemonic");
        }
        if (decoded.length != 32) {
            throw new IllegalArgumentException("decoded wallet key must be 32 bytes");
        }
        return new Crypto.PrivateKey(decoded);
    }

    private static byte[] hex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
