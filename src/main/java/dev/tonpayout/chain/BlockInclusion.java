package dev.tonpayout.chain;

import java.util.List;

public interface BlockInclusion {
    /** True when every message hash is already inside a block. */
    boolean allIncluded(List<String> messageHashes) throws Exception;
}
