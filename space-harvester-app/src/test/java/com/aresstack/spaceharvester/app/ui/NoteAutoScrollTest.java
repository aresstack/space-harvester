package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Dimension;
import java.awt.Point;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Hinweistexte, die beim Aktualisieren neu gesetzt werden, ziehen die Bildlaufansicht nicht zu sich: Unter
 * „Aufträge“ sprang der Detailbereich sonst alle 1,5 s nach unten, und das Codefeld oben war nicht erreichbar.
 */
class NoteAutoScrollTest {

    @Test
    void settingTheTextOfANoteLeavesTheScrollPositionAlone() throws Exception {
        JScrollPane[] scroll = new JScrollPane[1];
        JTextArea[] note = new JTextArea[1];
        SwingUtilities.invokeAndWait(() -> {
            JPanel column = new JPanel();
            column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
            JPanel spacer = new JPanel();
            spacer.setPreferredSize(new Dimension(200, 2000));
            column.add(spacer);
            note[0] = Comic.note("");
            column.add(note[0]);
            scroll[0] = new JScrollPane(column);
            scroll[0].setSize(240, 300);
            scroll[0].doLayout();
            scroll[0].getViewport().doLayout();
            column.setSize(column.getPreferredSize());
            column.doLayout();
            scroll[0].getViewport().setViewPosition(new Point(0, 0));
            note[0].setText("neuer Stand\nmit zwei Zeilen");
        });
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() ->
                assertThat(scroll[0].getViewport().getViewPosition().y).as("oben geblieben").isZero());
    }
}
