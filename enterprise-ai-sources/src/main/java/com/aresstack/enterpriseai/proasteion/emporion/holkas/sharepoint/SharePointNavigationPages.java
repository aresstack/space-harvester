package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.InvalidNavigationCursorException;
import com.aresstack.enterpriseai.source.api.NavigationChildren;
import com.aresstack.enterpriseai.source.api.NavigationNode;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint.SharePointNavigationCursor.State;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Resume from the server continuation and a verified offset, without storing open folders. */
final class SharePointNavigationPages {
    static final int BATCH_SIZE = 100;
    private final SharePointNavigationCursor cursors = new SharePointNavigationCursor();

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
                ? start(parent, true, folders) : cursors.decode(parent, cursor);
        List<NavigationNode> result = new ArrayList<NavigationNode>();
        while (state != null && result.size() < BATCH_SIZE) {
            if (Thread.currentThread().isInterrupted()) {
                throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNAVAILABLE, "SharePoint: Abruf abgebrochen");
            }
            URI first = state.folders ? folders : files;
            URI current = URI.create(state.current);
            Page page = loader.load(first, current, state.folders);
            State next = nextPage(state, first, page.next, files);
            String fingerprint = fingerprint(page);
            if (state.offset > page.nodes.size() || (state.offset > 0 && !fingerprint.equals(state.fingerprint))) {
                throw new InvalidNavigationCursorException("SharePoint: REST-Seite wurde seit dem letzten Abruf verändert; Ordner neu laden");
            }
            int end = state.offset + Math.min(BATCH_SIZE - result.size(), page.nodes.size() - state.offset);
            result.addAll(page.nodes.subList(state.offset, end));
            if (end < page.nodes.size()) {
                state = new State(parent, state.folders, state.current, end, fingerprint,
                        state.checkpoint, state.power, state.steps);
                break;
            }
            boolean resumedPage = state.offset > 0;
            state = next;
            // Defer the next server page until the next Explorer request.
            if (page.next != null && !resumedPage && !result.isEmpty()) break;
        }
        return new NavigationChildren(result, state == null ? "" : cursors.encode(state));
    }

    private static State start(String parent, boolean folders, URI first) {
        return new State(parent, folders, first.toString(), 0, null, first.toString(), 1, 0);
    }

    private static State nextPage(State state, URI first, URI next, URI files) throws KnowledgeSourceException {
        if (next == null) return state.folders ? start(state.parent, false, files) : null;
        String target = next.toString();
        // Detect cycles in a deterministic continuation chain with constant-size Brent state.
        if (next.equals(first) || target.equals(state.current) || target.equals(state.checkpoint)) {
            throw invalid("Wiederholte OData-Folgeseite");
        }
        long steps = state.steps + 1;
        if (steps == state.power) {
            long power = state.power <= Long.MAX_VALUE / 2 ? state.power * 2 : Long.MAX_VALUE;
            return new State(state.parent, state.folders, target, 0, null, target, power, 0);
        }
        return new State(state.parent, state.folders, target, 0, null, state.checkpoint, state.power, steps);
    }

    private static String fingerprint(Page page) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (NavigationNode node : page.nodes) update(digest, node.resource().value());
            update(digest, page.next == null ? "" : page.next.toString());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    static KnowledgeSourceException invalid(String reason) {
        return new KnowledgeSourceException(KnowledgeSourceException.Kind.INVALID_RESPONSE, "SharePoint: " + reason);
    }
}
