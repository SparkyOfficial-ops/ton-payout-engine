package dev.tonpayout.chain;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import com.cton.sdk.Address;
import com.cton.sdk.Cell;
import com.cton.sdk.CellBuilder;

/**
 * TEP-74 {@code transfer#0x0f8a7ea5}. The internal message that carries this cell
 * is sent to the payer's jetton wallet, with TON attached only as gas.
 */
public final class JettonTransfer {
    public static final long TRANSFER_OP = 0x0f8a7ea5L;
    /** One nanoton so a text comment is forwarded and an empty wallet does not trap a larger sum. */
    public static final BigInteger FORWARD_WITH_COMMENT = BigInteger.ONE;

    private JettonTransfer() {
    }

    public static void write(Sink sink, String recipientRaw, String responseRaw, BigInteger jettonAmount, String comment) {
        if (jettonAmount == null || jettonAmount.signum() <= 0) {
            throw new IllegalArgumentException("jetton amount must be positive");
        }
        boolean hasComment = comment != null && !comment.isEmpty();
        sink.storeUint(32, TRANSFER_OP);
        sink.storeUint(64, 0);
        sink.storeCoins(jettonAmount);
        sink.storeAddress(recipientRaw);
        sink.storeAddress(responseRaw);
        sink.storeUint(1, 0);
        sink.storeCoins(hasComment ? FORWARD_WITH_COMMENT : BigInteger.ZERO);
        sink.storeUint(1, hasComment ? 1 : 0);
        if (hasComment) {
            sink.storeCommentRef(comment);
        }
    }

    public static Cell toCell(String recipientRaw, String responseRaw, BigInteger jettonAmount, String comment) {
        CellBuilder body = new CellBuilder();
        write(new CellSink(body), recipientRaw, responseRaw, jettonAmount, comment);
        return body.build();
    }

    public interface Sink {
        void storeUint(int bits, long value);

        void storeCoins(BigInteger amount);

        void storeAddress(String raw);

        void storeCommentRef(String comment);
    }

    private static final class CellSink implements Sink {
        private final CellBuilder body;

        private CellSink(CellBuilder body) {
            this.body = body;
        }

        @Override
        public void storeUint(int bits, long value) {
            body.storeUInt(bits, value);
        }

        @Override
        public void storeCoins(BigInteger amount) {
            body.storeCoins(amount);
        }

        @Override
        public void storeAddress(String raw) {
            try (Address address = new Address(raw)) {
                body.storeAddress(address);
            }
        }

        @Override
        public void storeCommentRef(String comment) {
            Cell text = new CellBuilder()
                    .storeUInt(32, 0)
                    .storeBytes(comment.getBytes(StandardCharsets.UTF_8))
                    .build();
            body.storeRef(text);
        }
    }
}
