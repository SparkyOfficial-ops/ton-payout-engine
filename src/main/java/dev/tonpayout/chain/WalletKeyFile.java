package dev.tonpayout.chain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Reads a wallet key file and checks that two address texts are the same account.
 * Native signing stays in {@link CtonChainSender}.
 */
final class WalletKeyFile {
    private WalletKeyFile() {
    }

    static Parsed parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("wallet key file is empty");
        }
        if (bytes.length == 32) {
            return new Parsed(bytes.clone(), List.of());
        }
        String text = new String(bytes, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("wallet key file is empty");
        }
        if (text.regionMatches(true, 0, "0x", 0, 2)) {
            text = text.substring(2).trim();
        }
        if (text.matches("(?i)[0-9a-f]{64}")) {
            return new Parsed(hex(text), List.of());
        }
        if (text.indexOf(' ') >= 0) {
            List<String> words = new ArrayList<>();
            for (String word : text.split("\\s+")) {
                if (!word.isBlank()) {
                    words.add(word);
                }
            }
            if (words.isEmpty()) {
                throw new IllegalArgumentException("wallet key mnemonic is empty");
            }
            return new Parsed(null, List.copyOf(words));
        }
        byte[] decoded;
        try {
            decoded = Base64.getMimeDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "wallet key must be 32 raw bytes, 64 hex characters, base64, or a mnemonic");
        }
        if (decoded.length != 32) {
            throw new IllegalArgumentException("decoded wallet key must be 32 bytes");
        }
        return new Parsed(decoded, List.of());
    }

    /**
     * {@code configured} is {@code WALLET_ADDRESS}. {@code opened} is the account the sender
     * actually opens. Friendly and raw forms of one account match; a different account does not.
     */
    static void sameAccount(String configured, String opened, String network) {
        String left = TonAddress.canonical(configured, network);
        String right = TonAddress.canonical(opened, network);
        if (left == null || right == null || !left.equals(right)) {
            throw new IllegalStateException("WALLET_ADDRESS does not match the opened wallet address");
        }
    }

    private static byte[] hex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    record Parsed(byte[] seed, List<String> mnemonic) {
        boolean isMnemonic() {
            return seed == null;
        }
    }
}
