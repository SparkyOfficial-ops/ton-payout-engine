package dev.tonpayout.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class TonAddressTest {
    private static final String RAW = "0:" + "12".repeat(32);

    @Test
    void bounceableReturnsRawAccount() {
        String friendly = friendly(0x11, RAW);
        assertTrue(friendly.startsWith("EQ"));
        assertEquals(RAW, TonAddress.canonical(friendly));
    }

    @Test
    void nonBounceableReturnsRawAccount() {
        String friendly = friendly(0x51, RAW);
        assertTrue(friendly.startsWith("UQ"));
        assertEquals(RAW, TonAddress.canonical(friendly));
    }

    @Test
    void testnetFriendlyRejectedOnMainnet() {
        String bounceable = friendly(0x91, RAW);
        String plain = friendly(0xD1, RAW);
        assertTrue(bounceable.startsWith("kQ"));
        assertTrue(plain.startsWith("0Q"));
        assertNull(TonAddress.canonical(bounceable, "mainnet"));
        assertNull(TonAddress.canonical(plain, "mainnet"));
        assertNull(TonAddress.canonical(bounceable));
    }

    @Test
    void testnetFriendlyAcceptedOnTestnet() {
        String bounceable = friendly(0x91, RAW);
        assertEquals(RAW, TonAddress.canonical(bounceable, "testnet"));
    }

    @Test
    void brokenChecksumOrGarbageIsNull() {
        String friendly = friendly(0x11, RAW);
        char[] broken = friendly.toCharArray();
        broken[10] = broken[10] == 'A' ? 'B' : 'A';
        assertNull(TonAddress.canonical(new String(broken)));
        assertNull(TonAddress.canonical("not-an-address"));
        assertNull(TonAddress.canonical("0:abcd"));
        assertNull(TonAddress.canonical(null));
    }

    private static String friendly(int tag, String raw) {
        int colon = raw.indexOf(':');
        byte[] body = new byte[34];
        body[0] = (byte) tag;
        body[1] = (byte) Integer.parseInt(raw.substring(0, colon));
        String hash = raw.substring(colon + 1);
        for (int i = 0; i < 32; i++) {
            body[2 + i] = (byte) Integer.parseInt(hash.substring(i * 2, i * 2 + 2), 16);
        }
        int crc = 0xFFFF;
        for (byte piece : body) {
            crc ^= (piece & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
                } else {
                    crc = (crc << 1) & 0xFFFF;
                }
            }
        }
        byte[] full = new byte[36];
        System.arraycopy(body, 0, full, 0, body.length);
        full[34] = (byte) (crc >> 8);
        full[35] = (byte) crc;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(full);
    }
}
