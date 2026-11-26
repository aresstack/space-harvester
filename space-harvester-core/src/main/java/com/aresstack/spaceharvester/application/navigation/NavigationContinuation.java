package com.aresstack.spaceharvester.application.navigation;

import com.aresstack.spaceharvester.application.sources.InvalidNavigationCursorException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Carry source continuation and optional order progress without a server-side listing session. */
final class NavigationContinuation {
    private static final String PREFIX = "nav1";
    private static final String ALGORITHM = "HmacSHA256";
    private final byte[] key = new byte[32];

    NavigationContinuation() {
        new SecureRandom().nextBytes(key);
    }

    String encode(String sourceId, String node, State state) {
        String payload = String.join(".", PREFIX, encodeText(sourceId), encodeText(node),
                Integer.toString(state.confirmedCount), encodeText(state.revision == null ? "" : state.revision),
                encodeText(state.sourceCursor));
        return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
    }

    State decode(String sourceId, String node, String cursor) {
        try {
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 7 || !PREFIX.equals(parts[0])) {
                throw new IllegalArgumentException();
            }
            String payload = cursor.substring(0, cursor.lastIndexOf('.'));
            if (!MessageDigest.isEqual(sign(payload), Base64.getUrlDecoder().decode(parts[6]))
                    || !Objects.equals(sourceId, decodeText(parts[1]))
                    || !Objects.equals(node, decodeText(parts[2]))) {
                throw new IllegalArgumentException();
            }
            int count = Integer.parseInt(parts[3]);
            String revision = decodeText(parts[4]);
            String sourceCursor = decodeText(parts[5]);
            if (count < 0 || sourceCursor.isEmpty() || (revision.isEmpty() && count != 0)) {
                throw new IllegalArgumentException();
            }
            return new State(sourceCursor, count, revision.isEmpty() ? null : revision);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidNavigationCursorException();
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC unavailable", failure);
        }
    }

    private static String encodeText(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeText(String text) {
        return new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
    }

    static final class State {
        final String sourceCursor;
        final int confirmedCount;
        final String revision;

        State(String sourceCursor, int confirmedCount, String revision) {
            this.sourceCursor = sourceCursor;
            this.confirmedCount = confirmedCount;
            this.revision = revision;
        }
    }
}