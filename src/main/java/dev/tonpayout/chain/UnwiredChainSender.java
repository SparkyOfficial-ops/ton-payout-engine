package dev.tonpayout.chain;

import java.util.List;

import dev.tonpayout.queue.Transfer;

public final class UnwiredChainSender implements ChainSender {
    @Override
    public boolean isReady() {
        return false;
    }

    @Override
    public String send(List<Transfer> transfers) {
        throw new IllegalStateException("chain sender is not wired");
    }
}
