package dev.tonpayout.chain;

import java.util.Base64;

/**
 * Accepts the same TON addresses the wallet sender can parse:
 * raw {@code workchain:64hex} and user-friendly base64url with a CRC16 checksum.
 */
public final class TonAddress {
    private TonAddress() {
    }

    public static boolean isValid(String address) {
        return canonical(address) != null;
    }

    /**
     * @return a form {@code Address} will accept, or null when the text is not a TON address
     */
    public static String canonical(String address) {
        if (address == null) {
            return null;
        }
        String value = address.trim();
        if (value.isEmpty() || value.length() > 128) {
            return null;
        }
        int colon = value.indexOf(':');
        if (colon >= 0) {
            return canonicalRaw(value, colon);
        }
        return canonicalFriendly(value);
    }

    private static String canonicalRaw(String value, int colon) {
        if (colon == 0 || colon == value.length() - 1) {
            return null;
        }
        int workchain;
        try {
            workchain = Integer.parseInt(value.substring(0, colon));
        } catch (NumberFormatException e) {
            return null;
        }
        if (workchain < Byte.MIN_VALUE || workchain > Byte.MAX_VALUE) {
            return null;
        }
        String hash = value.substring(colon + 1);
        if (hash.length() != 64 || !isHex(hash)) {
            return null;
        }
        return workchain + ":" + hash.toLowerCase();
    }

    private static String canonicalFriendly(String value) {
        if (value.length() < 48) {
            return null;
        }
        byte[] decoded;
        try {
            String url = value.replace('+', '-').replace('/', '_');
            int pad = (4 - url.length() % 4) % 4;
            decoded = Base64.getUrlDecoder().decode(url + "=".repeat(pad));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (decoded.length != 36) {
            return null;
        }
        int received = ((decoded[34] & 0xFF) << 8) | (decoded[35] & 0xFF);
        if (received != crc16(decoded, 34)) {
            return null;
        }
        int tag = decoded[0] & 0xFF;
        if ((tag & 0x1F) != 0x11 || (tag & 0x20) != 0) {
            return null;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
    }

    private static boolean isHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean digit = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!digit) {
                return false;
            }
        }
        return true;
    }

    /** CRC16-CCITT, polynomial 0x1021, initial 0xFFFF, MSB first. */
    private static int crc16(byte[] data, int length) {
        int crc = 0xFFFF;
        for (int i = 0; i < length; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
                } else {
                    crc = (crc << 1) & 0xFFFF;
                }
            }
        }
        return crc;
    }
}
