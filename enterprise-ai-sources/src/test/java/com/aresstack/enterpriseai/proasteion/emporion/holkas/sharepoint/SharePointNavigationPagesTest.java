package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.NavigationChildren;
import com.aresstack.enterpriseai.source.api.NavigationNode;
import org.junit.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class SharePointNavigationPagesTest {
    private final SharePointNavigationPages pages = new SharePointNavigationPages();

    @Test
    public void hundredsOfOpenFoldersNeverInvalidateEachOther() throws Exception {
        List<String> cursors = new ArrayList<String>();
        for (int i = 0; i < 300; i++) {
            NavigationChildren first = children(pages, i, "", false);
            assertEquals(100, first.nodes().size());
            assertFalse(first.complete());
            cursors.add(first.nextCursor());
        }
        for (int i = 0; i < cursors.size(); i++) {
            NavigationChildren rest = children(pages, i, cursors.get(i), false);
            assertTrue(rest.complete());
            assertEquals(1, rest.nodes().size());
            assertEquals("test:folder-" + i + "-100", rest.nodes().get(0).key());
        }
        assertEquals("test:folder-0-100", children(pages, 0, cursors.get(0), false).nodes().get(0).key());
    }

    @Test
    public void rejectsForeignOrManipulatedCursorBeforeLoading() throws Exception {
        String cursor = children(pages, 1, "", false).nextCursor();
        reject(() -> children(pages, 2, cursor, false));
        reject(() -> children(new SharePointNavigationPages(), 1, cursor, false));
        String tampered = (cursor.startsWith("A") ? "B" : "A") + cursor.substring(1);
        reject(() -> children(pages, 1, tampered, false));
        assertTrue(children(pages, 1, cursor, false).complete());
    }

    @Test
    public void changedOversizedPageIsNotSilentlySkipped() throws Exception {
        String cursor = children(pages, 1, "", false).nextCursor();
        reject(() -> children(pages, 1, cursor, true));
        assertTrue(children(pages, 1, cursor, false).complete());
    }

    @Test
    public void rejectsCyclesBeyondTheInitialPage() throws Exception {
        URI folders = URI.create("https://example.test/folders");
        URI files = URI.create("https://example.test/files");
        SharePointNavigationPages.Loader loader = (first, current, isFolder) -> {
            if (isFolder) return new SharePointNavigationPages.Page(Collections.<NavigationNode>emptyList(), null);
            String query = current.getQuery();
            String next = query == null ? "1" : "1".equals(query) ? "2" : "2".equals(query) ? "3" : "2";
            return new SharePointNavigationPages.Page(Collections.singletonList(node(current.toString())),
                    URI.create(files + "?" + next));
        };
        String cursor = "";
        for (int i = 0; i < 12; i++) {
            try {
                cursor = pages.folder("parent", cursor, folders, files, loader).nextCursor();
                assertFalse(cursor.isEmpty());
            } catch (KnowledgeSourceException expected) {
                assertEquals(KnowledgeSourceException.Kind.INVALID_RESPONSE, expected.kind());
                return;
            }
        }
        fail("Cyclic continuation did not terminate");
    }

    @Test
    public void skipsEmptyContinuationPagesWithoutReturningAnEmptyBatch() throws Exception {
        URI folders = URI.create("https://example.test/folders");
        URI files = URI.create("https://example.test/files");
        NavigationChildren result = pages.folder("parent", "", folders, files, (first, current, isFolder) -> {
            if (isFolder && current.equals(folders)) {
                return new SharePointNavigationPages.Page(Collections.<NavigationNode>emptyList(), URI.create(folders + "?2"));
            }
            if (isFolder) {
                return new SharePointNavigationPages.Page(Collections.<NavigationNode>emptyList(), null);
            }
            return new SharePointNavigationPages.Page(Collections.singletonList(node("file")), null);
        });
        assertTrue(result.complete());
        assertEquals(1, result.nodes().size());
    }

    private static NavigationChildren children(SharePointNavigationPages pages, int folder, String cursor,
                                               boolean changed) throws KnowledgeSourceException {
        String parent = "folder-" + folder;
        URI folders = URI.create("https://example.test/" + parent + "/folders");
        URI files = URI.create("https://example.test/" + parent + "/files");
        return pages.folder(parent, cursor, folders, files, (first, current, isFolder) -> {
            List<NavigationNode> nodes = new ArrayList<NavigationNode>();
            if (!isFolder) {
                for (int i = 0; i < 101; i++) nodes.add(node(parent + "-" + i));
                if (changed) Collections.swap(nodes, 0, 1);
            }
            return new SharePointNavigationPages.Page(nodes, null);
        });
    }

    private static NavigationNode node(String key) {
        return NavigationNode.page(KnowledgeResourceId.of("test:" + key), key, NavigationNode.Children.NO);
    }

    private interface Attempt {
        void run() throws KnowledgeSourceException;
    }

    private static void reject(Attempt attempt) throws Exception {
        try {
            attempt.run();
            fail("Invalid cursor accepted");
        } catch (com.aresstack.enterpriseai.source.api.InvalidNavigationCursorException expected) {
            assertEquals(KnowledgeSourceException.Kind.INVALID_RESPONSE, expected.kind());
        }
    }
}
