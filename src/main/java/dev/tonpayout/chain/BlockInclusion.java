package dev.tonpayout.chain;

import java.util.List;

public interface BlockInclusion {
    /**
     * The newest page of wallet transactions.
     * {@code null} means this implementation has no shared history and each
     * {@link #allIncluded(List)} call looks up on its own.
     * The returned list may later grow with older pages.
     */
    default List<?> recentTransactions() throws Exception {
        return null;
    }

    /** True when every message hash is already inside a block. */
    boolean allIncluded(List<String> messageHashes) throws Exception;

    /**
     * True when every hash is in {@code transactions}.
     * An implementation may append older transactions to that list.
     */
    default boolean allIncluded(List<?> transactions, List<String> messageHashes) throws Exception {
        return allIncluded(messageHashes);
    }
}
