package dev.tonpayout;

import java.util.logging.Logger;

import dev.tonpayout.api.ApiServer;
import dev.tonpayout.chain.UnwiredChainSender;
import dev.tonpayout.config.EngineConfig;
import dev.tonpayout.notify.WebhookNotifier;
import dev.tonpayout.queue.PayoutBatcher;

public final class PayoutEngine {
    private static final Logger LOG = Logger.getLogger(PayoutEngine.class.getName());

    private PayoutEngine() {
    }

    public static void main(String[] args) throws Exception {
        EngineConfig config = EngineConfig.load();
        WebhookNotifier webhook = new WebhookNotifier(config);
        PayoutBatcher batcher = new PayoutBatcher(config, new UnwiredChainSender(), webhook);
        ApiServer api = new ApiServer(config, batcher);
        batcher.start();
        api.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            api.stop();
            batcher.stop();
        }));
        LOG.info("ton-payout-engine started (chain send is not wired)");
        Thread.currentThread().join();
    }
}
