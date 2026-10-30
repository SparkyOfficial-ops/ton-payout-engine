package dev.tonpayout.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class MessageHashesTest {
    @Test
    void hexAndBase64OfTheSameBytesMatch() {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (0xA0 + i);
        }
        StringBuilder builder = new StringBuilder(64);
        for (byte piece : raw) {
            builder.append(Character.forDigit((piece >> 4) & 0xf, 16));
            builder.append(Character.forDigit(piece & 0xf, 16));
        }
        String hex = builder.toString();
        String base64 = Base64.getEncoder().encodeToString(raw);
        assertEquals(64, hex.length());
        assertEquals(44, base64.length());
        assertTrue(MessageHashes.normalize(hex).equals(MessageHashes.normalize(base64)));
        assertEquals(hex, MessageHashes.normalize(base64));
    }
}
