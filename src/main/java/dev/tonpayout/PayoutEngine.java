package dev.tonpayout;

import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.tonpayout.api.ApiServer;
import dev.tonpayout.chain.CtonChainSender;
import dev.tonpayout.config.EngineConfig;
import dev.tonpayout.notify.WebhookNotifier;
import dev.tonpayout.queue.PayoutBatcher;
import dev.tonpayout.queue.PayoutStore;

public final class PayoutEngine {
    private static final Logger LOG = Logger.getLogger(PayoutEngine.class.getName());

    private PayoutEngine() {
    }

    public static void main(String[] args) throws Exception {
        EngineConfig config = EngineConfig.load();
        CtonChainSender chain = new CtonChainSender(config);
        WebhookNotifier webhook = new WebhookNotifier(config);
        PayoutStore store = new PayoutStore(Path.of(config.dbPath()));
        PayoutBatcher batcher = new PayoutBatcher(config, chain, webhook, store);
        ApiServer api = new ApiServer(config, batcher, chain);
        batcher.start();
        api.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            api.stop();
            batcher.stop();
            try {
                store.close();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "database close failed", e);
            }
        }));
        LOG.info(chain.isReady()
                ? "ton-payout-engine started, highload sender ready"
                : "ton-payout-engine started, set WALLET_ADDRESS and WALLET_KEY_PATH before sending");
        Thread.currentThread().join();
    }
}
