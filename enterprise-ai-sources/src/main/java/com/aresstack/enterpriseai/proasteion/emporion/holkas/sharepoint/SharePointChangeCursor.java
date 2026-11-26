package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The position of a change listing, carried by its cursor (ADR 0042, point 6): there is no register of open listings
 * in the process, so a fifth listing never invalidates the first. A native listing continues after the change token
 * the server delivered, inside the window fixed by its first batch; the inventory fallback continues after a position
 * secured by the fingerprint of everything delivered before it.
 */
final class SharePointChangeCursor {
    private static final Gson GSON = new Gson();
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

    static final class State {
        final String partition;
        final boolean inventory;
        /** Native listing: the window fixed by the first batch, in server time. */
        final long startMillis;
        final long endMillis;
        /** Native listing: the libraries with their root paths and the configured roots. */
        final List<String> lists;
        final List<String> paths;
        final List<String> roots;
        /** Native listing: the library in progress and the last change token processed there, or none. */
        final int list;
        final String token;
        /** Inventory fallback: resources already delivered and the fingerprint of their IDs. */
        final int offset;
        final String fingerprint;

        private State(String partition, boolean inventory, long startMillis, long endMillis, List<String> lists,
                      List<String> paths, List<String> roots, int list, String token, int offset, String fingerprint) {
            this.partition = partition;
            this.inventory = inventory;
            this.startMillis = startMillis;
            this.endMillis = endMillis;
            this.lists = lists;
            this.paths = paths;
            this.roots = roots;
            this.list = list;
            this.token = token;
            this.offset = offset;
            this.fingerprint = fingerprint;
        }

        static State nativeListing(String partition, long startMillis, long endMillis, List<String> lists,
                                   List<String> paths, List<String> roots) {
            return new State(partition, false, startMillis, endMillis, new ArrayList<String>(lists),
                    new ArrayList<String>(paths), new ArrayList<String>(roots), 0, null, 0, null);
        }

        static State inventory(String partition) {
            return new State(partition, true, 0, 0, Collections.<String>emptyList(), Collections.<String>emptyList(),
                    Collections.<String>emptyList(), 0, null, 0, null);
        }

        State at(int nextList, String nextToken) {
            return new State(partition, false, startMillis, endMillis, lists, paths, roots, nextList, nextToken, 0,
                    null);
        }

        State inventoryAt(int nextOffset, String nextFingerprint) {
            return new State(partition, true, 0, 0, Collections.<String>emptyList(), Collections.<String>emptyList(),
                    Collections.<String>emptyList(), 0, null, nextOffset, nextFingerprint);
        }

        boolean valid() {
            if (inventory) {
                return offset >= 0 && (offset == 0 || fingerprint != null);
            }
            return lists != null && paths != null && roots != null && lists.size() == paths.size()
                    && list >= 0 && list <= lists.size() && startMillis < endMillis;
        }
    }
}
