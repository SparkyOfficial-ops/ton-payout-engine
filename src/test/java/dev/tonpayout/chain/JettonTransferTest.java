package dev.tonpayout.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class JettonTransferTest {
    @Test
    void transferBodyTargetsTheRecipientAndReturnsGasToThePayer() {
        List<String> ops = new ArrayList<>();
        JettonTransfer.write(sink(ops), "0:" + "ab".repeat(32), "0:" + "cd".repeat(32),
                new BigInteger("1500000"), null);
        assertEquals(List.of(
                "u32:" + JettonTransfer.TRANSFER_OP,
                "u64:0",
                "c:1500000",
                "a:0:" + "ab".repeat(32),
                "a:0:" + "cd".repeat(32),
                "u1:0",
                "c:0",
                "u1:0"), ops);
    }

    @Test
    void commentRidesInAForwardPayloadReference() {
        List<String> ops = new ArrayList<>();
        JettonTransfer.write(sink(ops), "0:" + "11".repeat(32), "0:" + "22".repeat(32),
                BigInteger.ONE, "salary");
        assertEquals("u32:" + JettonTransfer.TRANSFER_OP, ops.get(0));
        assertEquals(0x0f8a7ea5L, JettonTransfer.TRANSFER_OP);
        assertEquals("c:1", ops.get(6));
        assertEquals("u1:1", ops.get(7));
        assertEquals("comment:salary", ops.get(8));
    }

    @Test
    void zeroJettonAmountIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> JettonTransfer.write(
                sink(new ArrayList<>()), "0:" + "11".repeat(32), "0:" + "22".repeat(32),
                BigInteger.ZERO, null));
    }

    private static JettonTransfer.Sink sink(List<String> ops) {
        return new JettonTransfer.Sink() {
            @Override
            public void storeUint(int bits, long value) {
                ops.add("u" + bits + ":" + value);
            }

            @Override
            public void storeCoins(BigInteger amount) {
                ops.add("c:" + amount);
            }

            @Override
            public void storeAddress(String raw) {
                ops.add("a:" + raw);
            }

            @Override
            public void storeCommentRef(String comment) {
                ops.add("comment:" + comment);
            }
        };
    }
}
