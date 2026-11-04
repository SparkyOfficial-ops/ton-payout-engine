package dev.tonpayout.chain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class WalletKeyFileTest {
    private static final byte[] SEED = new byte[32];

    static {
        for (int i = 0; i < SEED.length; i++) {
            SEED[i] = (byte) (i + 1);
        }
    }

    @Test
    void rawHexAndBase64AreTheSameSeed() {
        assertArrayEquals(SEED, WalletKeyFile.parse(SEED).seed());
        StringBuilder hex = new StringBuilder();
        for (byte piece : SEED) {
            hex.append(Character.forDigit((piece >> 4) & 0xf, 16));
            hex.append(Character.forDigit(piece & 0xf, 16));
        }
        assertArrayEquals(SEED, WalletKeyFile.parse(("0x" + hex).getBytes(StandardCharsets.US_ASCII)).seed());
        byte[] base64 = Base64.getEncoder().encode(SEED);
        assertArrayEquals(SEED, WalletKeyFile.parse(base64).seed());
        assertFalse(WalletKeyFile.parse(SEED).isMnemonic());
    }

    @Test
    void brokenFileIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> WalletKeyFile.parse(new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> WalletKeyFile.parse("not-a-key".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(IllegalArgumentException.class,
                () -> WalletKeyFile.parse(Base64.getEncoder().encode(new byte[16])));
    }

    @Test
    void spacedTextIsAMnemonic() {
        WalletKeyFile.Parsed parsed = WalletKeyFile.parse(
                "abandon abandon abandon about".getBytes(StandardCharsets.US_ASCII));
        assertTrue(parsed.isMnemonic());
        assertEquals(4, parsed.mnemonic().size());
    }

    @Test
    void highloadAddressFollowsThePublicKey() {
        byte[] pub = new byte[32];
        java.util.Arrays.fill(pub, (byte) 0x11);
        assertEquals(
                "0:3cbd98f4b3c144396a9da75c43e9a787a5d7c7f46ff4b59e3ebb1fe9a8b1ed23",
                HighloadAddress.raw(pub, 698983191, 0));
        pub[0] = 0x12;
        assertFalse(HighloadAddress.raw(pub, 698983191, 0).endsWith(
                "3cbd98f4b3c144396a9da75c43e9a787a5d7c7f46ff4b59e3ebb1fe9a8b1ed23"));
    }

    @Test
    void friendlyAndRawOfOneAccountMatch() {
        String raw = "0:" + "ab".repeat(32);
        String friendly = TonAddress.nonBounceable(raw, "mainnet");
        WalletKeyFile.sameAccount(friendly, raw, "mainnet");
        assertThrows(IllegalStateException.class,
                () -> WalletKeyFile.sameAccount(raw, "0:" + "cd".repeat(32), "mainnet"));
        assertThrows(IllegalStateException.class,
                () -> WalletKeyFile.sameAccount("not-an-address", raw, "mainnet"));
    }
}
