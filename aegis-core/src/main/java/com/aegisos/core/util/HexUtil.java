package com.aegisos.core.util;

/** Hex encode/decode helpers. */
public final class HexUtil {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private HexUtil() {
    }

    public static String encode(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    public static byte[] decode(String hex) {
        int len = hex.length();
        if ((len & 1) != 0) {
            throw new IllegalArgumentException("hex string must have even length");
        }
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("invalid hex char at " + i);
            }
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public static String shortId(byte[] bytes) {
        String full = encode(bytes);
        return full.length() <= 12 ? full : full.substring(0, 12);
    }
}
