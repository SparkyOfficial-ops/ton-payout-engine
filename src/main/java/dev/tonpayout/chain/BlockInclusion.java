package dev.tonpayout.chain;

import java.util.List;

public interface BlockInclusion {
    /**
     * One {@code getTransactions} for the wallet.
     * {@code null} means this implementation has no shared history and each
     * {@link #allIncluded(List)} call looks up on its own.
     */
    default List<?> recentTransactions() throws Exception {
        return null;
    }

    /** True when every message hash is already inside a block. */
    boolean allIncluded(List<String> messageHashes) throws Exception;

    /** True when every hash is in an already loaded transaction list. No network. */
    default boolean allIncluded(List<?> transactions, List<String> messageHashes) throws Exception {
        return allIncluded(messageHashes);
    }
}
