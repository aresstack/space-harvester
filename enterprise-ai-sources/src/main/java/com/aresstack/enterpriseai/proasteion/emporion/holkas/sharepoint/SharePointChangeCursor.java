package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The position of a change listing, carried by its cursor (ADR 0042, point 6): there is no register of open listings
 * in the process, so a fifth listing never invalidates the first. A native listing continues after the change token
 * the server delivered, inside the window fixed by its first batch; the inventory fallback continues after a position
 * secured by the fingerprint of everything delivered before it.
 *
 * <p>The checkpoint (ADR 0042, point 5) is the change token of the last processed change per library. Unlike a cursor
 * it outlives the process, so it is not signed with the key of this instance; it is checked for its form and its
 * partition, and only tokens of libraries in the current scope are used.
 */
final class SharePointChangeCursor {
    private static final Gson GSON = new Gson();
    private static final String CHECKPOINT_PREFIX = "sharepoint-changes-1:";
    private final CursorSignature signature = new CursorSignature();

    String encode(State state) {
        return signature.encode(GSON.toJson(state).getBytes(StandardCharsets.UTF_8));
    }

    State decode(String partition, String cursor) throws KnowledgeSourceException {
        try {
            State state = GSON.fromJson(new String(signature.decode(cursor), StandardCharsets.UTF_8), State.class);
            if (state == null || !partition.equals(state.partition) || !state.valid()) {
                throw new IllegalArgumentException("invalid change cursor");
            }
            return state;
        } catch (RuntimeException invalid) {
            throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNSUPPORTED,
                    "SharePoint: ungültiger Änderungscursor");
        }
    }

    /** The checkpoint after a native listing: the position reached in every library. */
    static String checkpoint(State state) {
        Checkpoint checkpoint = new Checkpoint(state.partition, state.positions);
        return CHECKPOINT_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(GSON.toJson(checkpoint).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The positions of a checkpoint per library; empty for {@code ""}, a checkpoint of another partition or one in an
     * unknown form, so that the listing falls back to its time window instead of failing.
     */
    static Map<String, String> positions(String partition, String checkpoint) {
        if (checkpoint == null || !checkpoint.startsWith(CHECKPOINT_PREFIX)) {
            return Collections.emptyMap();
        }
        try {
            Checkpoint parsed = GSON.fromJson(new String(Base64.getUrlDecoder().decode(
                    checkpoint.substring(CHECKPOINT_PREFIX.length())), StandardCharsets.UTF_8), Checkpoint.class);
            if (parsed == null || !partition.equals(parsed.partition) || parsed.positions == null) {
                return Collections.emptyMap();
            }
            Map<String, String> positions = new LinkedHashMap<String, String>();
            for (Map.Entry<String, String> entry : parsed.positions.entrySet()) {
                String list = SharePointChangeLog.guid(entry.getKey());
                String token = entry.getValue();
                if (token == null || !token.toLowerCase(Locale.ROOT).startsWith(SharePointChangeLog.prefix(list))) {
                    return Collections.emptyMap();
                }
                positions.put(list, token);
            }
            return positions;
        } catch (RuntimeException | KnowledgeSourceException invalid) {
            return Collections.emptyMap();
        }
    }

    private static final class Checkpoint {
        final String partition;
        final Map<String, String> positions;

        Checkpoint(String partition, Map<String, String> positions) {
            this.partition = partition;
            this.positions = positions;
        }
    }

    static final class State {
        final String partition;
        final boolean inventory;
        /** Native listing: the end of the window fixed by the first batch, in server time. */
        final long endMillis;
        /** Native listing: the libraries with their root paths and the configured roots. */
        final List<String> lists;
        final List<String> paths;
        final List<String> roots;
        /** Native listing: the library in progress. */
        final int list;
        /**
         * Native listing: per library the change token after which its change log continues; at first the checkpoint
         * or the start of the window, then the token of the last change processed there.
         */
        final Map<String, String> positions;
        /** Inventory fallback: resources already delivered and the fingerprint of their IDs. */
        final int offset;
        final String fingerprint;

        private State(String partition, boolean inventory, long endMillis, List<String> lists, List<String> paths,
                      List<String> roots, int list, Map<String, String> positions, int offset, String fingerprint) {
            this.partition = partition;
            this.inventory = inventory;
            this.endMillis = endMillis;
            this.lists = lists;
            this.paths = paths;
            this.roots = roots;
            this.list = list;
            this.positions = positions;
            this.offset = offset;
            this.fingerprint = fingerprint;
        }

        static State nativeListing(String partition, long endMillis, List<String> lists, List<String> paths,
                                   List<String> roots, Map<String, String> positions) {
            return new State(partition, false, endMillis, new ArrayList<String>(lists), new ArrayList<String>(paths),
                    new ArrayList<String>(roots), 0, new LinkedHashMap<String, String>(positions), 0, null);
        }

        static State inventory(String partition) {
            return new State(partition, true, 0, Collections.<String>emptyList(), Collections.<String>emptyList(),
                    Collections.<String>emptyList(), 0, Collections.<String, String>emptyMap(), 0, null);
        }

        /** Continue in library {@code nextList}; {@code token} is the last change processed in library {@code id}. */
        State at(int nextList, String id, String token) {
            Map<String, String> next = new LinkedHashMap<String, String>(positions);
            if (token != null) next.put(id, token);
            return new State(partition, false, endMillis, lists, paths, roots, nextList, next, 0, null);
        }

        State inventoryAt(int nextOffset, String nextFingerprint) {
            return new State(partition, true, 0, Collections.<String>emptyList(), Collections.<String>emptyList(),
                    Collections.<String>emptyList(), 0, Collections.<String, String>emptyMap(), nextOffset,
                    nextFingerprint);
        }

        boolean valid() {
            if (inventory) {
                return offset >= 0 && (offset == 0 || fingerprint != null);
            }
            if (lists == null || paths == null || roots == null || positions == null || lists.size() != paths.size()
                    || list < 0 || list > lists.size() || endMillis <= 0) {
                return false;
            }
            for (String id : lists) {
                if (!positions.containsKey(id)) return false;
            }
            return true;
        }
    }
}
