package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.NavigationChildren;
import com.aresstack.enterpriseai.source.api.NavigationNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Deliver bounded Explorer batches without reading the next server page prematurely. */
final class SharePointNavigationPages {
    static final int BATCH_SIZE = 100;
    private static final int MAX_CURSORS = 128;
    private final Map<String, State> cursors = new LinkedHashMap<String, State>(16, .75f, true);

    interface Loader {
        Page load(URI first, URI current, boolean folders) throws KnowledgeSourceException;
    }

    static final class Page {
        final List<NavigationNode> nodes;
        final URI next;

        Page(List<NavigationNode> nodes, URI next) {
            this.nodes = nodes;
            this.next = next;
        }
    }

    NavigationChildren folder(String parent, String cursor, URI folders, URI files, Loader loader)
            throws KnowledgeSourceException {
        State state = cursor == null || cursor.isEmpty()
                ? new State(parent, Collections.<NavigationNode>emptyList(), folders, folders, files,
                        Collections.<URI>emptySet())
                : state(parent, cursor);
        return batch(cursor, state, loader);
    }

    private NavigationChildren batch(String cursor, State state, Loader loader) throws KnowledgeSourceException {
        List<NavigationNode> result = new ArrayList<NavigationNode>();
        List<NavigationNode> pending = state.pending;
        URI first = state.first;
        URI next = state.next;
        URI files = state.files;
        Set<URI> visited = new LinkedHashSet<URI>(state.visited);
        while (true) {
            int count = Math.min(BATCH_SIZE - result.size(), pending.size());
            result.addAll(pending.subList(0, count));
            pending = pending.subList(count, pending.size());
            if (!pending.isEmpty() || result.size() == BATCH_SIZE) break;
            if (next == null && files != null) {
                first = files;
                next = files;
                files = null;
            }
            if (next == null) break;
            if (Thread.currentThread().isInterrupted()) {
                throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNAVAILABLE, "SharePoint: Abruf abgebrochen");
            }
            if (!visited.add(next)) throw invalid("Wiederholte OData-Folgeseite");
            Page page = loader.load(first, next, files != null);
            if (page.next != null && visited.contains(page.next)) throw invalid("Wiederholte OData-Folgeseite");
            pending = page.nodes;
            next = page.next;
            if (next != null) {
                count = Math.min(BATCH_SIZE - result.size(), pending.size());
                result.addAll(pending.subList(0, count));
                pending = pending.subList(count, pending.size());
                break;
            }
        }
        String continuation = "";
        if (!pending.isEmpty() || next != null || files != null) {
            continuation = remember(new State(state.parent, new ArrayList<NavigationNode>(pending),
                    first, next, files, visited));
        }
        if (cursor != null && !cursor.isEmpty()) forget(cursor);
        return new NavigationChildren(result, continuation);
    }

    private synchronized State state(String parent, String cursor) throws KnowledgeSourceException {
        State state = cursors.get(cursor);
        if (state == null || !state.parent.equals(parent)) {
            throw invalid("Ungültiger oder abgelaufener Navigationscursor; Ordner neu laden");
        }
        return state;
    }

    private synchronized String remember(State state) {
        while (cursors.size() >= MAX_CURSORS) cursors.remove(cursors.keySet().iterator().next());
        String cursor = UUID.randomUUID().toString();
        cursors.put(cursor, state);
        return cursor;
    }

    private synchronized void forget(String cursor) {
        cursors.remove(cursor);
    }

    private static KnowledgeSourceException invalid(String reason) {
        return new KnowledgeSourceException(KnowledgeSourceException.Kind.INVALID_RESPONSE, "SharePoint: " + reason);
    }

    private static final class State {
        final String parent;
        final List<NavigationNode> pending;
        final URI first;
        final URI next;
        final URI files;
        final Set<URI> visited;

        State(String parent, List<NavigationNode> pending, URI first, URI next, URI files, Set<URI> visited) {
            this.parent = parent;
            this.pending = pending;
            this.first = first;
            this.next = next;
            this.files = files;
            this.visited = visited;
        }
    }
}
