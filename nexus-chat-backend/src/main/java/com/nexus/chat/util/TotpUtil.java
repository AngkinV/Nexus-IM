package com.nexus.chat.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * RFC 6238 TOTP (time-based one-time password) with RFC 4648 Base32, implemented
 * with the JDK only (HmacSHA1) so no external dependency is required. Compatible
 * with Google / Microsoft Authenticator defaults (SHA1, 6 digits, 30s period).
 */
public final class TotpUtil {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int TIME_STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpUtil() {
    }

    /** Random 160-bit secret, Base32-encoded for authenticator apps. */
    public static String generateSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /** Verify a code against the secret, tolerating +/- {@code window} steps of clock drift. */
    public static boolean verify(String base32Secret, String code, int window) {
        if (base32Secret == null || code == null) return false;
        String trimmed = code.trim();
        if (!trimmed.matches("\\d{" + DIGITS + "}")) return false;
        byte[] key = base32Decode(base32Secret);
        long counter = System.currentTimeMillis() / 1000L / TIME_STEP_SECONDS;
        for (int i = -window; i <= window; i++) {
            if (constantTimeEquals(generateCode(key, counter + i), trimmed)) {
                return true;
            }
        }
        return false;
    }

    /** otpauth:// URI for QR codes / manual entry. */
    public static String otpauthUri(String issuer, String account, String base32Secret) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label
                + "?secret=" + base32Secret
                + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + TIME_STEP_SECONDS;
    }

    private static String generateCode(byte[] key, long counter) {
        byte[] data = new byte[8];
        long value = counter;
        for (int i = 7; i >= 0; i--) {
            data[i] = (byte) (value & 0xff);
            value >>= 8;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP generation failed", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                int index = (buffer >> (bitsLeft - 5)) & 0x1f;
                bitsLeft -= 5;
                sb.append(BASE32_ALPHABET.charAt(index));
            }
        }
        if (bitsLeft > 0) {
            int index = (buffer << (5 - bitsLeft)) & 0x1f;
            sb.append(BASE32_ALPHABET.charAt(index));
        }
        return sb.toString();
    }

    static byte[] base32Decode(String s) {
        String clean = s.trim().replace("=", "").replace(" ", "").toUpperCase();
        byte[] result = new byte[clean.length() * 5 / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int count = 0;
        for (char c : clean.toCharArray()) {
            int val = BASE32_ALPHABET.indexOf(c);
            if (val < 0) continue;
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                result[count++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return result;
    }
}
