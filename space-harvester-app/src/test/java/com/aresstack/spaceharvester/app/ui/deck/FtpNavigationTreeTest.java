package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.deck.ftp.RemoteEntry;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FtpNavigationTreeTest {
    @Test
    void keepsAncestorsSiblingsAndPreviouslyLoadedMembers() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var loads = new ArrayList<String>();
            var tree = new FtpNavigationTree(loads::add);
            var jcl = RemoteEntry.directory("JCL", "'KKR.JCL'");
            var src = RemoteEntry.directory("SRC", "'KKR.SRC'");
            var build = RemoteEntry.file("BUILD", "'KKR.JCL(BUILD)'");
            var program = RemoteEntry.file("PROGRAM", "'KKR.SRC(PROGRAM)'");
            tree.showPath("'KKR'", true, List.of(jcl, src), List.of(jcl, src));
            tree.expandPath(path(tree, jcl.path()));
            assertThat(loads).containsExactly(jcl.path());
            tree.showPath(jcl.path(), true, List.of(build), List.of(build));
            tree.expandPath(path(tree, src.path()));
            assertThat(loads).containsExactly(jcl.path(), src.path());
            tree.showPath(src.path(), true, List.of(program), List.of(program));
            assertThat(path(tree, build.path())).isNotNull();
            assertThat(path(tree, program.path())).isNotNull();
            assertThat(path(tree, "'KKR'")).isNotNull();
            assertThat(tree.isExpanded(path(tree, jcl.path()))).isTrue();
            tree.setSelectionPaths(new TreePath[] {path(tree, build.path()), path(tree, program.path())});
            assertThat(tree.selectedEntries()).containsExactlyInAnyOrder(build, program);
            tree.removed(List.of(build));
            assertThat(path(tree, build.path())).isNull();
            assertThat(path(tree, program.path())).isNotNull();
        });
    }

    @Test
    void directPathShowsAncestorsWithoutMakingSyntheticFoldersDeletable() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var tree = new FtpNavigationTree(ignored -> { });
            var file = RemoteEntry.file("HELLO", "'KKR.COBOL.SRC(HELLO)'");
            tree.showPath("'KKR.COBOL.SRC'", true, List.of(file), List.of(file));
            tree.setSelectionPath(path(tree, "'KKR.COBOL'"));
            assertThat(tree.selectedEntry()).isNotNull();
            assertThat(tree.selectedEntries()).isEmpty();
            assertThat(path(tree, "'KKR'")).isNotNull();
            assertThat(path(tree, file.path())).isNotNull();
        });
    }

    @Test
    void filteringAndRestoringParentKeepsLoadedBranches() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var tree = new FtpNavigationTree(ignored -> { });
            var folder = RemoteEntry.directory("src", "/work/src");
            var sibling = RemoteEntry.file("README", "/work/README");
            var member = RemoteEntry.file("Main.java", "/work/src/Main.java");
            tree.showPath("/work", false, List.of(folder, sibling), List.of(folder, sibling));
            tree.showPath(folder.path(), false, List.of(member), List.of(member));
            tree.showPath("/work", false, List.of(folder, sibling), List.of(sibling));
            assertThat(path(tree, folder.path())).isNull();
            tree.showPath("/work", false, List.of(folder, sibling), List.of(folder, sibling));
            assertThat(path(tree, member.path())).isNotNull();
        });
    }

    private static TreePath path(FtpNavigationTree tree, String remotePath) {
        var root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        var nodes = root.preorderEnumeration();
        while (nodes.hasMoreElements()) {
            var node = (DefaultMutableTreeNode) nodes.nextElement();
            if (node.getUserObject() instanceof RemoteEntry entry && remotePath.equals(entry.path())) {
                return new TreePath(node.getPath());
            }
        }
        return null;
    }
}
