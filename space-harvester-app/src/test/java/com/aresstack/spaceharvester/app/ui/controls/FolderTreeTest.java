package com.aresstack.spaceharvester.app.ui.controls;

import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class FolderTreeTest {

    /** Testeintrag: Ordner mit Kindern oder Blatt. */
    record Entry(String id, String name, boolean folder, List<Entry> children) {
        static Entry leaf(String id) {
            return new Entry(id, "Seite " + id, false, List.of());
        }

        static Entry folder(String id, Entry... children) {
            return new Entry(id, "Ordner " + id, true, List.of(children));
        }
    }

    static final FolderTree.Shape<Entry> SHAPE = new FolderTree.Shape<>() {
        @Override
        public String id(Entry entry) {
            return entry.id();
        }

        @Override
        public boolean isFolder(Entry entry) {
            return entry.folder();
        }

        @Override
        public List<Entry> children(Entry entry) {
            return entry.children();
        }

        @Override
        public String name(Entry entry) {
            return entry.name();
        }
    };

    /** Merkt sich die Wünsche an die Ablage. */
    static final class Recorder implements FolderTree.Actions {
        final List<String> calls = new ArrayList<>();

        @Override
        public String createFolder(String parentId, String name) {
            calls.add("create " + parentId + " " + name);
            return null;
        }

        @Override
        public void renameFolder(String id, String name) {
            calls.add("rename " + id + " " + name);
        }

        @Override
        public void remove(String id) {
            calls.add("remove " + id);
        }

        @Override
        public boolean move(List<String> ids, String targetFolderId, int index) {
            calls.add("move " + ids + " " + targetFolderId + " " + index);
            return true;
        }
    }

    private static <V> V onEdt(Supplier<V> work) throws Exception {
        Object[] result = new Object[1];
        SwingUtilities.invokeAndWait(() -> result[0] = work.get());
        @SuppressWarnings("unchecked")
        V value = (V) result[0];
        return value;
    }

    private static final Entry B = Entry.folder("b", Entry.leaf("b1"));
    private static final Entry A = Entry.folder("a", Entry.leaf("a1"), B);
    private static final Entry X = Entry.leaf("x");

    private static FolderTree<Entry> tree(Recorder recorder) {
        FolderTree<Entry> tree = new FolderTree<>(SHAPE, recorder);
        tree.setRoots(List.of(A, X));
        return tree;
    }

    @Test
    void dropTargetsFollowTheInsertMarkAndNeverPutAFolderIntoItself() throws Exception {
        Recorder recorder = new Recorder();
        FolderTree<Entry> tree = onEdt(() -> tree(recorder));
        // Auf einen Ordner: hinein an die Stelle der Marke.
        assertThat(tree.target(List.of(X), A, null, -1, 1)).isEqualTo(new FolderTree.Target("a", 1));
        // Auf einen Eintrag: hinter ihn in seinen Ordner.
        assertThat(tree.target(List.of(X), Entry.leaf("a1"), A, 0, -1)).isEqualTo(new FolderTree.Target("a", 1));
        // Freie Fläche bzw. oberste Ebene.
        assertThat(tree.target(List.of(B), null, null, -1, 0)).isEqualTo(new FolderTree.Target(null, 0));
        // Ordner in sich selbst oder seinen Unterbaum: abgelehnt.
        assertThat(tree.target(List.of(A), A, null, -1, 0)).isNull();
        assertThat(tree.target(List.of(A), B, null, -1, 0)).isNull();
    }

    @Test
    void selectionMovesAsBlockWithoutMouseAndOffersOnlyAllowedFolders() throws Exception {
        Recorder recorder = new Recorder();
        FolderTree<Entry> tree = onEdt(() -> tree(recorder));
        JPopupMenu menu = onEdt(() -> {
            tree.select(List.of("x", "a"));
            return tree.menu();
        });
        assertThat(onEdt(() -> tree.selection().stream().map(Entry::id).toList())).containsExactly("a", "x");
        JMenu moveTo = (JMenu) java.util.Arrays.stream(menu.getComponents())
                .filter(c -> c instanceof JMenu m && m.getText().startsWith(FolderTree.MOVE_TO)).findFirst()
                .orElseThrow();
        // Ordner a ist gewählt: weder a noch sein Unterordner b dürfen Ziel sein.
        assertThat(java.util.Arrays.stream(moveTo.getMenuComponents()).map(c -> ((JMenuItem) c).getText()).toList())
                .containsExactly(FolderTree.TOP_LEVEL);

        onEdt(() -> {
            tree.select(List.of("x"));
            return tree.moveSelectedTo("b");
        });
        assertThat(recorder.calls).containsExactly("move [x] b -1");
    }

    @Test
    void aSingleFolderGetsTheItemsTheOwnerContributes() throws Exception {
        Recorder recorder = new Recorder();
        FolderTree<Entry> tree = onEdt(() -> tree(recorder));
        List<String> asked = new ArrayList<>();
        JPopupMenu menu = onEdt(() -> {
            tree.folderItems((id, popup) -> {
                asked.add(id);
                popup.add(new JMenuItem("Korb füllen …"));
            });
            tree.select(List.of("a"));
            return tree.menu();
        });
        assertThat(asked).containsExactly("a");
        assertThat(java.util.Arrays.stream(menu.getComponents()).filter(c -> c instanceof JMenuItem)
                .map(c -> ((JMenuItem) c).getText()).toList()).contains("Korb füllen …");

        JPopupMenu leaf = onEdt(() -> {
            tree.select(List.of("x"));
            return tree.menu();
        });
        assertThat(asked).as("nur für einen einzelnen Ordner").containsExactly("a");
        assertThat(leaf.getComponents()).noneMatch(c -> c instanceof JMenuItem m && m.getText().startsWith("Korb"));
    }

    @Test
    void removeAsksOnlyForFoldersWithContent() throws Exception {
        Recorder recorder = new Recorder();
        List<String> questions = new ArrayList<>();
        FolderTree<Entry> tree = onEdt(() -> tree(recorder));
        onEdt(() -> {
            tree.setConfirmation((parent, question) -> {
                questions.add(question);
                return false;
            });
            tree.select(List.of("x"));
            tree.removeSelected();
            tree.select(List.of("a"));
            tree.removeSelected();
            return null;
        });
        assertThat(recorder.calls).containsExactly("remove x");
        assertThat(questions).containsExactly("Ordner „Ordner a“ samt Inhalt wirklich entfernen?");
    }

    @Test
    void newStarGoesIntoSelectedFolderOrTopLevel() throws Exception {
        FolderTree<Entry> tree = onEdt(() -> tree(new Recorder()));
        assertThat(onEdt(tree::targetFolderId)).isNull();
        onEdt(() -> {
            tree.select(List.of("b1"));
            return null;
        });
        assertThat(onEdt(tree::targetFolderId)).isEqualTo("b");
        onEdt(() -> {
            tree.select(List.of("a"));
            return null;
        });
        assertThat(onEdt(tree::targetFolderId)).isEqualTo("a");
    }

    @Test
    void filterKeepsMatchingEntriesWithTheirFoldersAndSelectionSurvivesRebuild() throws Exception {
        FolderTree<Entry> tree = onEdt(() -> tree(new Recorder()));
        int rows = onEdt(() -> {
            tree.select(List.of("b1"));
            tree.setFilter(entry -> entry.id().equals("b1"));
            return tree.component().getRowCount();
        });
        assertThat(rows).isEqualTo(3); // a › b › b1
        assertThat(onEdt(() -> tree.selection().stream().map(Entry::id).toList())).containsExactly("b1");
        int all = onEdt(() -> {
            tree.setFilter(null);
            return tree.component().getRowCount();
        });
        assertThat(all).isEqualTo(5);
    }

    @Test
    void renameInTheTreeGoesToTheStoreOnlyForFolders() throws Exception {
        Recorder recorder = new Recorder();
        FolderTree<Entry> tree = onEdt(() -> tree(recorder));
        onEdt(() -> {
            javax.swing.tree.TreePath a = tree.component().getPathForRow(0);
            javax.swing.tree.TreePath x = tree.component().getPathForRow(4);
            tree.component().getModel().valueForPathChanged(a, "Projekt");
            tree.component().getModel().valueForPathChanged(x, "egal");
            return null;
        });
        assertThat(recorder.calls).containsExactly("rename a Projekt");
    }
}
