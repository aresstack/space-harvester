package com.aresstack.spaceharvester.app.ui.deck.editor;

import com.aresstack.spaceharvester.app.ui.controls.NavigationTreeIcons;
import com.aresstack.spaceharvester.mainframe.outline.Outline;
import com.aresstack.spaceharvester.mainframe.outline.SourceLanguage;
import java.util.Optional;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OutlinePanelIconsTest {
    @Test
    void rendersActualIconsInsteadOfEmojiPrefixes() throws Exception {
        var outline = Outline.parse(SourceLanguage.COBOL, """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. TESTPGM.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                       01 WS-COUNT PIC 9.
                       PROCEDURE DIVISION.
                           CALL 'OTHERPGM'.
                           STOP RUN.
                """, "TEST.cbl");
        SwingUtilities.invokeAndWait(() -> {
            var panel = new OutlinePanel(ignored -> { });
            panel.show(Optional.of(outline));
            var tree = panel.tree();
            boolean divisionFound = false;
            for (int row = 0; row < tree.getRowCount(); row++) {
                var node = tree.getPathForRow(row).getLastPathComponent();
                var label = (JLabel) tree.getCellRenderer().getTreeCellRendererComponent(
                        tree, node, false, true, tree.getModel().isLeaf(node), row, false);
                assertThat(label.getIcon()).isNotNull();
                assertThat(label.getText()).doesNotContain("📂", "📁", "📞", "🔢", "🏷", "▶");
                if (label.getText().equals("IDENTIFICATION DIVISION")) {
                    assertThat(label.getIcon()).isSameAs(NavigationTreeIcons.FOLDER);
                    divisionFound = true;
                }
            }
            assertThat(divisionFound).isTrue();
        });
    }
}
