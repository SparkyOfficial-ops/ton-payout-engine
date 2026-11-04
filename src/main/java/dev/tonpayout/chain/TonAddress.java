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
        return isValid(address, "mainnet");
    }

    public static boolean isValid(String address, String network) {
        return canonical(address, network) != null;
    }

    /**
     * {@code UQ} and {@code 0Q} are non-bounceable. Every other form, including raw
     * {@code workchain:hex}, stays bounceable.
     */
    public static boolean bounceable(String address) {
        if (address == null) {
            return true;
        }
        String value = address.trim();
        return !value.startsWith("UQ") && !value.startsWith("0Q");
    }

    /**
     * @return raw {@code workchain:64hex} on mainnet, or null when the text is not a TON address
     */
    public static String canonical(String address) {
        return canonical(address, "mainnet");
    }

    /**
     * Bounceable and non-bounceable forms of one account become the same raw address.
     * The raw form has no bounce bit. {@link #bounceable} records whether the original text was {@code UQ} or {@code 0Q}.
     * Friendly addresses with the testnet bit ({@code kQ} / {@code 0Q}) are rejected
     * unless {@code network} is {@code testnet}. Raw addresses have no network bit.
     */
    public static String canonical(String address, String network) {
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
        return canonicalFriendly(value, network);
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

    private static String canonicalFriendly(String value, String network) {
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
        boolean testnetAddress = (tag & 0x80) != 0;
        if (testnetAddress && (network == null || !network.equalsIgnoreCase("testnet"))) {
            return null;
        }
        int workchain = decoded[1];
        StringBuilder hash = new StringBuilder(64);
        for (int i = 2; i < 34; i++) {
            int piece = decoded[i] & 0xFF;
            hash.append(Character.forDigit(piece >>> 4, 16));
            hash.append(Character.forDigit(piece & 0xF, 16));
        }
        return workchain + ":" + hash;
    }

    /**
     * User-friendly form with bounce disabled ({@code UQ} on mainnet, {@code 0Q} on testnet).
     * cton-sdk treats a raw {@code workchain:hex} address as bounceable, so a payout to an
     * address that was only stored in that form would bounce off a wallet that is not deployed yet.
     *
     * @return null when {@code address} is not a TON address on {@code network}
     */
    public static String nonBounceable(String address, String network) {
        String raw = canonical(address, network);
        if (raw == null) {
            return null;
        }
        boolean testnet = network != null && network.equalsIgnoreCase("testnet");
        int colon = raw.indexOf(':');
        int workchain = Integer.parseInt(raw.substring(0, colon));
        String hash = raw.substring(colon + 1);
        byte[] body = new byte[34];
        body[0] = (byte) (testnet ? 0xD1 : 0x51);
        body[1] = (byte) workchain;
        for (int i = 0; i < 32; i++) {
            body[2 + i] = (byte) Integer.parseInt(hash.substring(i * 2, i * 2 + 2), 16);
        }
        int crc = crc16(body, body.length);
        byte[] full = new byte[36];
        System.arraycopy(body, 0, full, 0, body.length);
        full[34] = (byte) (crc >> 8);
        full[35] = (byte) crc;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(full);
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
