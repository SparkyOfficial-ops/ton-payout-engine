package dev.tonpayout.chain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    public static boolean contains(Object node, String expected) {
        String needle = normalize(expected);
        if (needle.isEmpty()) {
            return false;
        }
        return walk(node, needle);
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

    private static boolean walk(Object node, String needle) {
        if (node instanceof Map<?, ?> map) {
            Object hash = map.get("hash");
            if (hash != null && needle.equals(normalize(String.valueOf(hash)))) {
                return true;
            }
            for (Object value : map.values()) {
                if (walk(value, needle)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof Iterable<?> list) {
            for (Object value : list) {
                if (walk(value, needle)) {
                    return true;
                }
            }
        }
        return false;
    }
}
