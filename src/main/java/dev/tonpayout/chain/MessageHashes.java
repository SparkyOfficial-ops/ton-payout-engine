package dev.tonpayout.chain;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Compares a sendBoc message hash with Toncenter {@code in_msg.hash} or {@code in_msg.body_hash}.
 * Hex and base64 (including url-safe) of the same 32 bytes are equal.
 * {@code transaction_id.hash} is a different value and is never consulted.
 */
public final class MessageHashes {
    private MessageHashes() {
    }

    public static List<String> split(String stored) {
        List<String> hashes = new ArrayList<>();
        if (stored == null || stored.isBlank()) {
            return hashes;
        }
        for (String part : stored.split(",")) {
            if (!part.isBlank()) {
                hashes.add(part.trim());
            }
        }
        return hashes;
    }

    /**
     * @return {@code hash} or {@code body_hash} when that {@code in_msg} field matches, otherwise null
     */
    public static String inMessageField(Object transaction, String expected) {
        if (!(transaction instanceof Map<?, ?> tx)) {
            return null;
        }
        Object inMsg = tx.get("in_msg");
        if (!(inMsg instanceof Map<?, ?> message)) {
            return null;
        }
        if (same(message.get("hash"), expected)) {
            return "hash";
        }
        if (same(message.get("body_hash"), expected)) {
            return "body_hash";
        }
        return null;
    }

    public static String fieldText(Object transaction, String field) {
        if (!(transaction instanceof Map<?, ?> tx)) {
            return null;
        }
        Object inMsg = tx.get("in_msg");
        if (!(inMsg instanceof Map<?, ?> message)) {
            return null;
        }
        Object value = message.get(field);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * @return lowercase hex of the hash bytes, or an empty string when the text is not hex or base64
     */
    public static String normalize(String hash) {
        byte[] raw = decode(hash);
        if (raw == null) {
            return "";
        }
        StringBuilder hex = new StringBuilder(raw.length * 2);
        for (byte piece : raw) {
            hex.append(Character.forDigit((piece >> 4) & 0xf, 16));
            hex.append(Character.forDigit(piece & 0xf, 16));
        }
        return hex.toString();
    }

    private static byte[] decode(String hash) {
        if (hash == null) {
            return null;
        }
        String value = hash.trim();
        if (value.startsWith("0x") || value.startsWith("0X")) {
            value = value.substring(2);
        }
        if (value.isEmpty()) {
            return null;
        }
        if (isSha256Hex(value)) {
            return hexToBytes(value);
        }
        String base64 = value.replace('-', '+').replace('_', '/');
        int pad = (4 - base64.length() % 4) % 4;
        base64 = base64 + "=".repeat(pad);
        try {
            byte[] decoded = Base64.getDecoder().decode(base64);
            return decoded.length == 0 ? null : decoded;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isSha256Hex(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean digit = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!digit) {
                return false;
            }
        }
        return true;
    }

    private static byte[] hexToBytes(String hex) {
        byte[] raw = new byte[hex.length() / 2];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return raw;
    }

    private static boolean same(Object actual, String expected) {
        if (actual == null) {
            return false;
        }
        String needle = normalize(expected);
        return !needle.isEmpty() && needle.equals(normalize(String.valueOf(actual)));
    }
}
