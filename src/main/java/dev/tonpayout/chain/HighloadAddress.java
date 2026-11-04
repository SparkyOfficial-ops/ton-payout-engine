package dev.tonpayout.chain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Address of a Highload Wallet v2: hash of StateInit(code, data).
 * Data is {@code subwallet_id:uint32 last_cleaned:uint64 public_key:bits256 queries:empty}.
 * The code bag is the standard contract whose hash is
 * {@code 9494d1cc8edf12f05671a1a9ba09921096eb50811e1924ec65c3c629fbb80812}.
 */
final class HighloadAddress {
    private static final String CODE_BOC =
            "B5EE9C724101090100E5000114FF00F4A413F4BCF2C80B010201200203020148040501EAF28308D71820D31FD33FF823AA1F5320B9F263ED44D0D31FD33FD3FFF404D153608040F40E6FA131F2605173BAF2A207F901541087F910F2A302F404D1F8007F8E16218010F4786FA5209802D307D43001FB009132E201B3E65B8325A1C840348040F4438AE63101C8CB1F13CB3FCBFFF400C9ED54080004D03002012006070017BD9CE76A26869AF98EB85FFC0041BE5F976A268698F98E99FE9FF98FA0268A91040207A0737D098C92DBFC95DD1F140034208040F4966FA56C122094305303B9DE2093333601926C21E2B39F9E545A";
    private static final Node CODE = root(hex(CODE_BOC));

    private HighloadAddress() {
    }

    static String raw(byte[] publicKey, int subwalletId, int workchain) {
        if (publicKey == null || publicKey.length != 32) {
            throw new IllegalArgumentException("public key must be 32 bytes");
        }
        Node data = built(bits(publicKey, subwalletId), new Node[0]);
        Node init = built(stateBits(), new Node[] { CODE, data });
        return workchain + ":" + hex(hash(init));
    }

    private static boolean[] bits(byte[] publicKey, int subwalletId) {
        boolean[] bits = new boolean[32 + 64 + 256 + 1];
        write(bits, 0, subwalletId & 0xFFFFFFFFL, 32);
        write(bits, 32 + 64, publicKey);
        return bits;
    }

    private static boolean[] stateBits() {
        return new boolean[] { false, false, true, true, false };
    }

    private static void write(boolean[] bits, int at, long value, int width) {
        for (int i = 0; i < width; i++) {
            bits[at + i] = ((value >> (width - 1 - i)) & 1L) == 1L;
        }
    }

    private static void write(boolean[] bits, int at, byte[] value) {
        for (int i = 0; i < value.length; i++) {
            write(bits, at + i * 8, value[i] & 0xFF, 8);
        }
    }

    private static Node built(boolean[] bits, Node[] refs) {
        int bytes = (bits.length + 7) / 8;
        byte[] data = new byte[bytes];
        for (int i = 0; i < bits.length; i++) {
            if (bits[i]) {
                data[i / 8] |= (byte) (1 << (7 - (i % 8)));
            }
        }
        if (bits.length % 8 != 0) {
            data[bits.length / 8] |= (byte) (1 << (7 - (bits.length % 8)));
        }
        int d1 = refs.length;
        int d2 = bytes + bits.length / 8;
        return new Node(d1, d2, data, refs);
    }

    private static byte[] hash(Node node) {
        if (node.hash != null) {
            return node.hash;
        }
        int childDepth = 0;
        for (Node ref : node.refs) {
            hash(ref);
            childDepth = Math.max(childDepth, ref.depth + 1);
        }
        node.depth = node.refs.length == 0 ? 0 : childDepth;
        byte[] repr = new byte[2 + node.data.length + node.refs.length * (2 + 32)];
        repr[0] = (byte) node.d1;
        repr[1] = (byte) node.d2;
        System.arraycopy(node.data, 0, repr, 2, node.data.length);
        int cursor = 2 + node.data.length;
        for (Node ref : node.refs) {
            repr[cursor++] = (byte) (ref.depth >> 8);
            repr[cursor++] = (byte) ref.depth;
        }
        for (Node ref : node.refs) {
            System.arraycopy(ref.hash, 0, repr, cursor, 32);
            cursor += 32;
        }
        node.hash = sha256(repr);
        return node.hash;
    }

    private static Node root(byte[] boc) {
        if (boc.length < 6 || read(boc, 0, 4) != 0xb5ee9c72L) {
            throw new IllegalArgumentException("highload code bag is not a BoC");
        }
        int flags = boc[4] & 0xFF;
        boolean hasIdx = (flags & 0x80) != 0;
        int size = flags & 0x07;
        int offBytes = boc[5] & 0xFF;
        if (size == 0 || offBytes == 0) {
            throw new IllegalArgumentException("highload code bag has an empty size");
        }
        int cursor = 6;
        int cells = (int) read(boc, cursor, size);
        cursor += size;
        int roots = (int) read(boc, cursor, size);
        cursor += size;
        cursor += size;
        int total = (int) read(boc, cursor, offBytes);
        cursor += offBytes;
        if (roots < 1) {
            throw new IllegalArgumentException("highload code bag has no root");
        }
        int root = (int) read(boc, cursor, size);
        cursor += size * roots;
        if (hasIdx) {
            cursor += cells * offBytes;
        }
        Node[] parsed = new Node[cells];
        int[][] refIndex = new int[cells][];
        int at = cursor;
        for (int i = 0; i < cells; i++) {
            int d1 = boc[at++] & 0xFF;
            int d2 = boc[at++] & 0xFF;
            if ((d1 & 0x08) != 0 || (d1 & 0x10) != 0 || (d1 >> 5) != 0) {
                throw new IllegalArgumentException("highload code bag has a special cell");
            }
            int refs = d1 & 0x07;
            int dataBytes = (d2 + 1) / 2;
            byte[] data = Arrays.copyOfRange(boc, at, at + dataBytes);
            at += dataBytes;
            int[] refsAt = new int[refs];
            for (int r = 0; r < refs; r++) {
                refsAt[r] = (int) read(boc, at, size);
                at += size;
            }
            parsed[i] = new Node(d1, d2, data, null);
            refIndex[i] = refsAt;
        }
        if (at - cursor != total) {
            throw new IllegalArgumentException("highload code bag size does not match its cells");
        }
        for (int i = 0; i < cells; i++) {
            Node[] refs = new Node[refIndex[i].length];
            for (int r = 0; r < refs.length; r++) {
                refs[r] = parsed[refIndex[i][r]];
            }
            parsed[i].refs = refs;
        }
        return parsed[root];
    }

    private static long read(byte[] data, int offset, int length) {
        long value = 0;
        for (int i = 0; i < length; i++) {
            value = (value << 8) | (data[offset + i] & 0xFFL);
        }
        return value;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static byte[] hex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] data) {
        StringBuilder text = new StringBuilder(data.length * 2);
        for (byte piece : data) {
            text.append(Character.forDigit((piece >> 4) & 0xf, 16));
            text.append(Character.forDigit(piece & 0xf, 16));
        }
        return text.toString();
    }

    private static final class Node {
        final int d1;
        final int d2;
        final byte[] data;
        Node[] refs;
        byte[] hash;
        int depth;

        Node(int d1, int d2, byte[] data, Node[] refs) {
            this.d1 = d1;
            this.d2 = d2;
            this.data = data;
            this.refs = refs;
        }
    }
}
