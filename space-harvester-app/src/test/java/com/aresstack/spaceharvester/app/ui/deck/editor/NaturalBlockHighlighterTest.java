package com.aresstack.spaceharvester.app.ui.deck.editor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import javax.swing.SwingUtilities;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;

/** Blockfarben wie {@code NaturalSubroutineHighlighter} in MainframeMate, einstellbar unter Einstellungen › NDV. */
class NaturalBlockHighlighterTest {

    @Test
    void subroutinesDataAndErrorBlocksAreColouredAndFollowTheSettings() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NaturalBlockHighlighter.store(NaturalBlockHighlighter.SUBROUTINE_KEY, null);
            NaturalBlockHighlighter.store(NaturalBlockHighlighter.DEFINE_DATA_KEY, null);
            NaturalBlockHighlighter.store(NaturalBlockHighlighter.ON_ERROR_KEY, null);
            RSyntaxTextArea area = new RSyntaxTextArea();
            area.setSyntaxEditingStyle(SyntaxStyles.NATURAL);
            area.setText("""
                    DEFINE DATA LOCAL
                    1 #A (A10)
                    END-DEFINE
                    PERFORM PRUEFEN
                    DEFINE SUBROUTINE PRUEFEN
                      IGNORE
                    END-SUBROUTINE
                    ON ERROR
                      ESCAPE ROUTINE
                    END-ERROR
                    END""");
            NaturalBlockHighlighter.apply(area);
            assertThat(NaturalBlockHighlighter.blocks(area)).containsExactlyInAnyOrder("0-2 #E6F0FF", "4-6 #FFF8DC",
                    "7-9 #FFE6E6");

            NaturalBlockHighlighter.store(NaturalBlockHighlighter.SUBROUTINE_KEY, new Color(0x123456));
            NaturalBlockHighlighter.apply(area);
            assertThat(NaturalBlockHighlighter.blocks(area)).contains("4-6 #123456").hasSize(3);
            NaturalBlockHighlighter.store(NaturalBlockHighlighter.SUBROUTINE_KEY, null);
            assertThat(SyntaxStyles.forExtension("nsp")).isEqualTo(SyntaxStyles.NATURAL);
        });
    }
}
