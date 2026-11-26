package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.InvalidNavigationCursorException;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;

/** Authenticate self-contained cursors without retaining individual navigation sessions. */
final class SharePointNavigationCursor {
    private static final Gson GSON = new Gson();
    private final CursorSignature signature = new CursorSignature();

    String encode(State state) {
        return signature.encode(GSON.toJson(state).getBytes(StandardCharsets.UTF_8));
    }

    State decode(String parent, String token) throws KnowledgeSourceException {
        try {
            byte[] bytes = signature.decode(token);
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
