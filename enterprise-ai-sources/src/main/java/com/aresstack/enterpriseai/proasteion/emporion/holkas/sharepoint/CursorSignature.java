package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Sign self-contained cursors so that a cursor carries its state without a register in the process (ADR 0040 for
 * navigation, ADR 0042 point 6 for change listings). The key lives only in this instance: after a restart old cursors
 * are rejected and the caller starts again.
 */
final class CursorSignature {
    private static final String ALGORITHM = "HmacSHA256";
    private static final int KEY_BYTES = 32;
    private final byte[] key = new byte[KEY_BYTES];

    CursorSignature() {
        new SecureRandom().nextBytes(key);
    }

    String encode(byte[] payload) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(payload) + "." + encoder.encodeToString(sign(payload));
    }

    /** The signed payload of {@code token}; {@link IllegalArgumentException} if it is malformed or not signed here. */
    byte[] decode(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) throw new IllegalArgumentException("malformed cursor");
        Base64.Decoder decoder = Base64.getUrlDecoder();
        byte[] payload = decoder.decode(parts[0]);
        if (!MessageDigest.isEqual(sign(payload), decoder.decode(parts[1]))) {
            throw new IllegalArgumentException("foreign cursor");
        }
        return payload;
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC unavailable", failure);
        }
    }
}
