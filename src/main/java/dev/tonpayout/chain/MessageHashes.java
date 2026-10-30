package dev.tonpayout.chain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Compares a sendBoc message hash with Toncenter {@code in_msg.hash} or {@code in_msg.body_hash}.
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

    public static String normalize(String hash) {
        if (hash == null) {
            return "";
        }
        String value = hash.trim().replace('-', '+').replace('_', '/');
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '=') {
            end--;
        }
        return value.substring(0, end);
    }

    private static boolean same(Object actual, String expected) {
        if (actual == null) {
            return false;
        }
        String needle = normalize(expected);
        return !needle.isEmpty() && needle.equals(normalize(String.valueOf(actual)));
    }
}
