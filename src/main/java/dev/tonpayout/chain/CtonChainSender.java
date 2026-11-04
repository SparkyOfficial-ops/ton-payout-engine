package dev.tonpayout.chain;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import com.cton.api.TonApiClient;
import com.cton.contract.BaseWallet;
import com.cton.contract.BulkTransfer;
import com.cton.contract.HighloadWallet;
import com.cton.sdk.Address;
import com.cton.sdk.Cell;
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

    /**
     * Reads the key and opens {@code WALLET_ADDRESS} before the API accepts payouts.
     * A broken key file or an address the sender cannot open aborts startup.
     */
    public void requireReady() {
        if (config.walletAddress().isBlank()) {
            throw new IllegalStateException("WALLET_ADDRESS is not set");
        }
        if (!Files.isRegularFile(keyPath)) {
            throw new IllegalStateException("wallet key file is missing: " + keyPath);
        }
        try (Crypto.PrivateKey key = loadKey();
                Crypto.PublicKey publicKey = key.getPublicKey();
                Address self = new Address(config.walletAddress())) {
            byte[] pub = publicKey.getData();
            if (pub.length != 32) {
                throw new IllegalStateException("wallet key does not yield an Ed25519 public key");
            }
            byte[] probe = new byte[] { 0x54, 0x4f, 0x4e };
            byte[] signature = Crypto.sign(key, probe);
            if (signature.length != 64 || !Crypto.verify(publicKey, probe, signature)) {
                throw new IllegalStateException("wallet key cannot sign");
            }
            String configured = TonAddress.canonical(config.walletAddress(), config.tonNetwork());
            if (configured == null) {
                throw new IllegalStateException("WALLET_ADDRESS is not a TON address");
            }
            int colon = configured.indexOf(':');
            int workchain = Integer.parseInt(configured.substring(0, colon));
            String derived = HighloadAddress.raw(pub, config.subwalletId(), workchain);
            WalletKeyFile.sameAccount(configured, derived, config.tonNetwork());
            if (self.toRaw() == null || self.toRaw().isBlank()) {
                throw new IllegalStateException("WALLET_ADDRESS cannot be opened");
            }
            LOG.info("wallet key matches " + configured);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new IllegalStateException("wallet key is invalid: " + detail, e);
        }
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
        List<Cell> bodies = new ArrayList<>();
        try (Crypto.PrivateKey key = loadKey();
             Address self = new Address(config.walletAddress())) {
            HighloadWallet wallet = new HighloadWallet(
                    self, api, config.subwalletId(), queryTtlSeconds(config.messageTtlMs()));
            BulkTransfer[] bulk = new BulkTransfer[transfers.size()];
            String responseRaw = TonAddress.canonical(config.walletAddress(), config.tonNetwork());
            String jettonWallet = TonAddress.canonical(config.usdtJettonWallet(), config.tonNetwork());
            for (int i = 0; i < transfers.size(); i++) {
                Transfer transfer = transfers.get(i);
                String comment = transfer.comment() == null || transfer.comment().isBlank()
                        ? null
                        : transfer.comment();
                if (transfer.usdt()) {
                    if (jettonWallet == null) {
                        throw new IllegalStateException("USDT_JETTON_WALLET is not set");
                    }
                    Address destination = new Address(jettonWallet);
                    destinations.add(destination);
                    Cell body = JettonTransfer.toCell(
                            transfer.to(), responseRaw, new BigInteger(transfer.amountNano()), comment);
                    bodies.add(body);
                    bulk[i] = new BulkTransfer(
                            destination, BigInteger.valueOf(config.usdtGasNano()), body, 3, true);
                } else {
                    Address destination = new Address(transfer.to());
                    destinations.add(destination);
                    bulk[i] = new BulkTransfer(
                            destination, new BigInteger(transfer.amountNano()), comment, 3, transfer.bounceable());
                }
            }
            HighloadWallet.PreparedBulk prepared = wallet.prepareBulkTransfer(bulk, key);
            return new Outgoing(prepared.hashHex(), prepared.boc());
        } finally {
            for (Cell body : bodies) {
                body.close();
            }
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
        WalletKeyFile.Parsed parsed = WalletKeyFile.parse(Files.readAllBytes(keyPath));
        if (parsed.isMnemonic()) {
            return Crypto.mnemonicToPrivateKey(parsed.mnemonic());
        }
        return new Crypto.PrivateKey(parsed.seed());
    }
}
