package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.InvalidNavigationCursorException;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Authenticate self-contained cursors without retaining individual navigation sessions. */
final class SharePointNavigationCursor {
    private static final Gson GSON = new Gson();
    private static final String ALGORITHM = "HmacSHA256";
    private final byte[] key = new byte[32];

    SharePointNavigationCursor() {
        new SecureRandom().nextBytes(key);
    }

    String encode(State state) {
        byte[] bytes = GSON.toJson(state).getBytes(StandardCharsets.UTF_8);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(bytes) + "." + encoder.encodeToString(sign(bytes));
    }

    State decode(String parent, String token) throws KnowledgeSourceException {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] bytes = decoder.decode(parts[0]);
            if (!MessageDigest.isEqual(sign(bytes), decoder.decode(parts[1]))) throw new IllegalArgumentException();
            State state = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), State.class);
            if (state == null || !parent.equals(state.parent) || state.current == null
                    || state.checkpoint == null || state.offset < 0 || state.power < 1
                    || state.steps < 0 || state.steps >= state.power
                    || (state.offset > 0 && state.fingerprint == null)) throw new IllegalArgumentException();
            return state;
        } catch (RuntimeException invalid) {
            throw new InvalidNavigationCursorException("SharePoint: Ungültiger Navigationscursor; Ordner neu laden");
        }
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

    static final class State {
        final String parent;
        final boolean folders;
        final String current;
        final int offset;
        final String fingerprint;
        final String checkpoint;
        final long power;
        final long steps;

        State(String parent, boolean folders, String current, int offset, String fingerprint,
              String checkpoint, long power, long steps) {
            this.parent = parent;
            this.folders = folders;
            this.current = current;
            this.offset = offset;
            this.fingerprint = fingerprint;
            this.checkpoint = checkpoint;
            this.power = power;
            this.steps = steps;
        }
    }
}
