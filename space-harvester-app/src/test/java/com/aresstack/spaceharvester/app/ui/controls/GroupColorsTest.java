package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.group.GroupColor;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Jede Gruppenfarbe hat Swing-Werte, kein Rot, und die Auswahl geht reihum. */
class GroupColorsTest {

    @Test
    void everyKeyHasValuesAndUnknownKeysAreGrey() {
        for (GroupColor key : GroupColor.values()) {
            assertThat(GroupColors.of(key).name()).isEqualTo(key.name());
        }
        assertThat(GroupColors.values()).hasSameSizeAs(GroupColor.values());
        assertThat(GroupColor.of("rot")).isEqualTo(GroupColor.GRAU);
        assertThat(GroupColor.of(" Blau ")).isEqualTo(GroupColor.BLAU);
        assertThat(GroupColors.of(null)).isEqualTo(GroupColors.GRAU);
    }

    @Test
    void theValueFollowsTheBackground() {
        assertThat(GroupColors.BLAU.on(Color.WHITE)).isEqualTo(GroupColors.BLAU.light());
        assertThat(GroupColors.BLAU.on(Color.BLACK)).isEqualTo(GroupColors.BLAU.dark());
    }

    @Test
    void nextTakesTheFirstUnusedThenGoesRound() {
        assertThat(GroupColor.next(List.of())).isEqualTo(GroupColor.BLAU);
        assertThat(GroupColor.next(List.of(GroupColor.BLAU, GroupColor.GRUEN))).isEqualTo(GroupColor.TUERKIS);
        assertThat(GroupColor.next(List.of(GroupColor.values()))).isEqualTo(GroupColor.BLAU);
    }

    @Test
    void swatchesSelectWithArrowKeys() {
        ColorSwatches swatches = new ColorSwatches(GroupColors.GRAU, GroupColors::name);
        List<GroupColors> seen = new java.util.ArrayList<>();
        swatches.onChange(seen::add);
        // Ohne Fenster lenkt Swing Tastendrücke an den Fokusinhaber um; der Test spricht den Zuhörer direkt an.
        java.awt.event.KeyEvent right = new java.awt.event.KeyEvent(swatches, java.awt.event.KeyEvent.KEY_PRESSED, 0,
                0, java.awt.event.KeyEvent.VK_RIGHT, java.awt.event.KeyEvent.CHAR_UNDEFINED);
        for (java.awt.event.KeyListener listener : swatches.getKeyListeners()) {
            listener.keyPressed(right);
        }
        assertThat(swatches.selected()).isEqualTo(GroupColors.BLAU);
        assertThat(seen).containsExactly(GroupColors.BLAU);
        assertThat(swatches.getAccessibleContext().getAccessibleName()).isEqualTo("BLAU");
    }
}
