package com.aresstack.spaceharvester.app.ui.controls;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Point;
import java.awt.Rectangle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ViewerPopupTest {

    private int millis;

    @BeforeEach
    void still() {
        millis = ViewerPopup.animationMillis;
        ViewerPopup.animationMillis = 0;
    }

    @AfterEach
    void restore() {
        ViewerPopup.animationMillis = millis;
    }

    private static JPanel ownerIn(JRootPane root) {
        JPanel owner = new JPanel();
        root.setSize(960, 640);
        root.getContentPane().setLayout(new BorderLayout());
        root.getContentPane().add(owner, BorderLayout.CENTER);
        root.validate();
        root.getLayeredPane().setSize(960, 640);
        return owner;
    }

    @Test
    void opensOverTheWindowWithRoomToClickBeside() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JRootPane root = new JRootPane();
            JPanel owner = ownerIn(root);
            ViewerPopup popup = ViewerPopup.open(owner, new JLabel("Bild"), () -> new Rectangle(40, 60, 120, 80));
            assertThat(popup.isOpen()).isTrue();
            assertThat(ViewerPopup.current(owner)).containsSame(popup);
            Rectangle frame = popup.frameBounds();
            assertThat(frame).isEqualTo(ViewerPopup.target(960, 640));
            assertThat(frame.x).isGreaterThanOrEqualTo(ViewerPopup.MARGIN);
            assertThat(frame.y).isGreaterThanOrEqualTo(ViewerPopup.MARGIN);
        });
    }

    @Test
    void aClickInsideKeepsItAndAClickBesideClosesIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JRootPane root = new JRootPane();
            JPanel owner = ownerIn(root);
            int[] closed = {0};
            ViewerPopup popup = ViewerPopup.open(owner, new JLabel("Bild"), null).onClosed(() -> closed[0]++);
            popup.clickAt(new Point(480, 320));
            assertThat(popup.isOpen()).isTrue();
            popup.clickAt(new Point(5, 5));
            assertThat(popup.isOpen()).isFalse();
            assertThat(closed[0]).isEqualTo(1);
            assertThat(ViewerPopup.current(owner)).isEmpty();
            assertThat(root.getLayeredPane().getComponentCountInLayer(javax.swing.JLayeredPane.MODAL_LAYER)).isZero();
            // Noch einmal schließen schadet nicht.
            popup.close();
            assertThat(closed[0]).isEqualTo(1);
        });
    }

    @Test
    void theProgramClosesItAndANewOneReplacesTheOld() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JRootPane root = new JRootPane();
            JPanel owner = ownerIn(root);
            ViewerPopup first = ViewerPopup.open(owner, new JLabel("eins"), null);
            ViewerPopup second = ViewerPopup.open(owner, new JLabel("zwei"), null);
            assertThat(first.isOpen()).isFalse();
            assertThat(ViewerPopup.current(owner)).containsSame(second);
            assertThat(root.getLayeredPane().getComponentCountInLayer(javax.swing.JLayeredPane.MODAL_LAYER))
                    .isEqualTo(1);
            second.close();
            assertThat(ViewerPopup.current(owner)).isEmpty();
        });
    }

    @Test
    void itGrowsFromThePlaceOfThePictureAndShrinksBackThere() throws Exception {
        ViewerPopup.animationMillis = 120;
        JRootPane root = new JRootPane();
        ViewerPopup[] popup = new ViewerPopup[1];
        Rectangle[] first = new Rectangle[1];
        boolean[] closed = {false};
        SwingUtilities.invokeAndWait(() -> {
            JPanel owner = ownerIn(root);
            popup[0] = ViewerPopup.open(owner, new JLabel("Bild"), () -> new Rectangle(40, 60, 120, 80))
                    .onClosed(() -> closed[0] = true);
            first[0] = popup[0].frameBounds();
        });
        // Am Anfang steht der Rahmen an der Bildstelle, nicht schon groß.
        assertThat(first[0]).isEqualTo(new Rectangle(40, 60, 120, 80));
        Thread.sleep(400);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(popup[0].frameBounds()).isEqualTo(ViewerPopup.target(960, 640));
            popup[0].close();
            assertThat(popup[0].isOpen()).isFalse();
            assertThat(closed[0]).as("verschwindet erst nach dem Schrumpfen").isFalse();
        });
        Thread.sleep(400);
        SwingUtilities.invokeAndWait(() -> assertThat(closed[0]).isTrue());
    }

    @Test
    void withoutAWindowItRefuses() {
        assertThatThrownBy(() -> ViewerPopup.open(new JPanel(), new JLabel("Bild"), null))
                .isInstanceOf(IllegalStateException.class);
    }
}
