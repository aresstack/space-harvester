package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.Glyph;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Insets;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Der flache Symbolknopf aus Angelos Proxy-Programm ({@code createSmallIconButton}), 1:1. */
class FlatIconButtonTest {

    @Test
    void itIsTheSmallFlatButtonOfTheProxyProgram() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FlatIconButton button = new FlatIconButton(Glyph.EXPORT, "Antwort exportieren");
            assertThat(button.isFocusable()).isFalse();
            assertThat(button.isBorderPainted()).isFalse();
            assertThat(button.isContentAreaFilled()).isFalse();
            assertThat(button.getMargin()).isEqualTo(new Insets(0, 0, 0, 0));
            assertThat(button.getInsets()).isEqualTo(new Insets(0, 0, 0, 0));
            Dimension size = new Dimension(20, 20);
            assertThat(button.getPreferredSize()).isEqualTo(size);
            assertThat(button.getMinimumSize()).isEqualTo(size);
            assertThat(button.getMaximumSize()).isEqualTo(size);
            assertThat(button.getToolTipText()).isEqualTo("Antwort exportieren");
        });
    }

    @Test
    void theCopyButtonTakesTheSameFlatStyleAndStillCopies() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            java.util.List<String> copied = new java.util.ArrayList<>();
            CopyButton copy = new CopyButton(() -> "Text", copied::add).flat(Color.WHITE);
            assertThat(copy.getPreferredSize()).isEqualTo(new Dimension(20, 20));
            assertThat(copy.isFocusable()).isFalse();
            assertThat(copy.isRolloverEnabled()).isFalse();
            assertThat(copy.getForeground()).isEqualTo(Color.WHITE);
            copy.doClick();
            assertThat(copied).containsExactly("Text");
            assertThat(copy.copiedShown()).isTrue();
        });
    }
}
