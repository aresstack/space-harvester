package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Dimension;
import java.awt.Rectangle;
import org.junit.jupiter.api.Test;

/**
 * Dialoge passen auf den nutzbaren Bildschirm: Assistent und Quellen-Dialog waren auf einem Laptop (1080p, 150 %)
 * höher als der Bildschirm, die Knopfleiste lag unter der Taskleiste und der Bildlauf zeigte keine Leiste
 * (Angelo, 10.11.).
 */
class DialogsFitTest {

    private static final Rectangle LAPTOP = new Rectangle(0, 0, 1280, 672);

    @Test
    void aDialogTallerThanTheScreenIsCutToTheScreenSoItsContentScrolls() {
        assertThat(Dialogs.fit(new Dimension(908, 690), new Dimension(860, 600), LAPTOP))
                .isEqualTo(new Dimension(908, 672));
    }

    @Test
    void theScreenWinsOverTheMinimum() {
        assertThat(Dialogs.fit(new Dimension(500, 300), new Dimension(860, 800), LAPTOP))
                .isEqualTo(new Dimension(860, 672));
    }

    @Test
    void aSmallDialogKeepsItsSizeAndWithoutScreenOnlyTheMinimumCounts() {
        assertThat(Dialogs.fit(new Dimension(560, 420), new Dimension(460, 200), LAPTOP))
                .isEqualTo(new Dimension(560, 420));
        assertThat(Dialogs.fit(new Dimension(500, 300), new Dimension(860, 800), null))
                .isEqualTo(new Dimension(860, 800));
    }
}
