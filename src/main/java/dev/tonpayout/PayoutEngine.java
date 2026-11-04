package dev.tonpayout;

import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.tonpayout.api.ApiServer;
import dev.tonpayout.chain.CtonChainSender;
import dev.tonpayout.chain.TonCenterBlockInclusion;
import dev.tonpayout.config.EngineConfig;
import dev.tonpayout.log.LoggingSetup;
import dev.tonpayout.notify.WebhookNotifier;
import dev.tonpayout.notify.WebhookOutbox;
import dev.tonpayout.queue.Housekeeping;
import dev.tonpayout.queue.PayoutBatcher;
import dev.tonpayout.queue.PayoutStore;
import dev.tonpayout.queue.SendingRecovery;
import dev.tonpayout.queue.TxPoller;

public final class PayoutEngine {
    private static final Logger LOG = Logger.getLogger(PayoutEngine.class.getName());

    private PayoutEngine() {
    }

    public static void main(String[] args) throws Exception {
        LoggingSetup.install();
        EngineConfig config = EngineConfig.load();
        CtonChainSender chain = new CtonChainSender(config);
        chain.requireReady();
        WebhookNotifier webhook = new WebhookNotifier(config);
        PayoutStore store = new PayoutStore(Path.of(config.dbPath()));
        if (!config.webhookUrl().isBlank()) {
            store.recordWebhooks(true);
        }
        PayoutBatcher batcher = new PayoutBatcher(config, chain, webhook, store);
        TonCenterBlockInclusion inclusion = new TonCenterBlockInclusion(config);
        SendingRecovery recovery = new SendingRecovery(config, store, inclusion);
        TxPoller poller = new TxPoller(config, store, inclusion, recovery);
        WebhookOutbox outbox = new WebhookOutbox(store, webhook);
        Housekeeping housekeeping = new Housekeeping(config, store);
        ApiServer api = new ApiServer(config, batcher, chain, recovery, store);
        poller.start();
        batcher.start();
        if (!config.webhookUrl().isBlank()) {
            outbox.start();
        }
        housekeeping.start();
        api.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            api.stop();
            batcher.stop();
            poller.stop();
            outbox.stop();
            housekeeping.stop();
            try {
                store.close();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "database close failed", e);
            }
        }));
        LOG.info("ton-payout-engine started, highload sender ready");
        Thread.currentThread().join();
    }
}
