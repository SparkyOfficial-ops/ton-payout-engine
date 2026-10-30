package dev.tonpayout.chain;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One wallet history for a poller tick. The first lookup loads transactions;
 * every later batch in the same tick is matched against that list.
 */
public final class ChainLook {
    private static final Logger LOG = Logger.getLogger(ChainLook.class.getName());

    private final BlockInclusion inclusion;
    private boolean loaded;
    private boolean announced;
    private List<?> transactions;
    private Exception error;

    public ChainLook(BlockInclusion inclusion) {
        this.inclusion = inclusion;
    }

    public boolean allIncluded(List<String> hashes) throws Exception {
        if (hashes.isEmpty()) {
            return true;
        }
        if (!loaded) {
            loaded = true;
            try {
                transactions = inclusion.recentTransactions();
            } catch (Exception e) {
                error = e;
            }
        }
        if (error != null) {
            if (!announced) {
                LOG.log(Level.WARNING, "block lookup failed: " + error.getMessage());
                announced = true;
            }
            throw error;
        }
        if (transactions == null) {
            return inclusion.allIncluded(hashes);
        }
        return inclusion.allIncluded(transactions, hashes);
    }
}
