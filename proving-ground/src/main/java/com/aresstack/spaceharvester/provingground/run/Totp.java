package com.aresstack.spaceharvester.provingground.run;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Locale;

/**
 * Time-based one-time codes (RFC 6238), as WD4J's {@code TotpService} computed them with the googleauth library.
 * The secret is Base32, or an {@code otpauth://totp/...} URI as authenticator apps and KeePass store it; its
 * {@code digits}, {@code period} and {@code algorithm} parameters override the RFC's defaults.
 */
public final class Totp {

    /** RFC 6238 defaults (also googleauth's and every common authenticator's). */
    public static final int DEFAULT_DIGITS = 6;
    public static final int DEFAULT_PERIOD_SECONDS = 30;
    public static final String DEFAULT_ALGORITHM = "SHA1";

    private Totp() {
    }

    /** The code valid at {@code now}. */
    public static String code(String secretOrUri, Instant now) {
        String secret = secretOrUri.trim();
        int digits = DEFAULT_DIGITS;
        int period = DEFAULT_PERIOD_SECONDS;
        String algorithm = DEFAULT_ALGORITHM;
        if (secret.toLowerCase(Locale.ROOT).startsWith("otpauth://")) {
            String query = URI.create(secret).getRawQuery();
            secret = "";
            for (String pair : query == null ? new String[0] : query.split("&")) {
                int equals = pair.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                String key = pair.substring(0, equals).toLowerCase(Locale.ROOT);
                String value = URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
                switch (key) {
                    case "secret" -> secret = value;
                    case "digits" -> digits = Integer.parseInt(value);
                    case "period" -> period = Integer.parseInt(value);
                    case "algorithm" -> algorithm = value.toUpperCase(Locale.ROOT);
                    default -> { }
                }
            }
        }
        return code(base32(secret), now.getEpochSecond() / period, digits, algorithm);
    }

    static String code(byte[] key, long counter, int digits, String algorithm) {
        try {
            String mac = "Hmac" + algorithm;
            Mac hmac = Mac.getInstance(mac);
            hmac.init(new SecretKeySpec(key, mac));
            byte[] hash = hmac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            long modulus = (long) Math.pow(10, digits);
            return String.format("%0" + digits + "d", binary % modulus);
        } catch (GeneralSecurityException unsupported) {
            throw new IllegalArgumentException("OTP-Verfahren nicht unterstützt: " + algorithm, unsupported);
        }
    }

    /** RFC 4648 Base32, case and spaces ignored, padding optional. */
    static byte[] base32(String text) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        String clean = text.replace(" ", "").replace("-", "").replace("=", "").toUpperCase(Locale.ROOT);
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int value = alphabet.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("OTP-Geheimnis ist kein Base32");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) (buffer >> (bits - 8)));
                bits -= 8;
            }
        }
        return out.array();
    }
}
