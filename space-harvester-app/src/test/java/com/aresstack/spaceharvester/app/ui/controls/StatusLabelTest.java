package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.FontMetrics;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class StatusLabelTest {

    @Test
    void onlyAClickOnTheLinkWordRunsItsAction() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            StatusLabel label = new StatusLabel();
            label.show(new StatusNote("Fortschritt und Abbruch unter Aufträge.", StatusNote.Tone.PLAIN, "Aufträge",
                    opened::incrementAndGet));
            label.setSize(label.getPreferredSize());
            FontMetrics metrics = label.getFontMetrics(label.getFont());
            int start = metrics.stringWidth("Fortschritt und Abbruch unter ");

            click(label, 2);
            assertThat(opened).as("Klick auf den Text davor").hasValue(0);
            click(label, start + metrics.stringWidth("Auf"));
            assertThat(opened).hasValue(1);
            assertThat(label.getText()).contains("<u>").contains("Aufträge");
        });
    }

    @Test
    void theLinkIsReachableByKeyboardAndNamedForScreenReaders() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            StatusLabel label = new StatusLabel();
            assertThat(label.isFocusable()).as("ohne Link kein Tab-Halt").isFalse();
            label.show(new StatusNote("Einzelheiten unter Aufträge.", StatusNote.Tone.PLAIN, "Aufträge",
                    opened::incrementAndGet));
            assertThat(label.isFocusable()).isTrue();
            assertThat(label.getAccessibleContext().getAccessibleName()).isEqualTo("Einzelheiten unter Aufträge.");
            assertThat(label.getAccessibleContext().getAccessibleDescription()).contains("Aufträge");
            label.getActionMap().get(label.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(javax.swing.KeyStroke.getKeyStroke("ENTER"))).actionPerformed(null);
            assertThat(opened).hasValue(1);
            label.show(StatusNote.plain("fertig"));
            assertThat(label.isFocusable()).isFalse();
        });
    }

    @Test
    void toneSetsTheColourAndPlainTextStaysPlain() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            StatusLabel label = new StatusLabel();
            label.show(new StatusNote("3 Seiten bewusst ausgelassen", StatusNote.Tone.WARNING, null, null));
            assertThat(label.getForeground()).isEqualTo(StatusLabel.WARNING);
            assertThat(label.getText()).isEqualTo("3 Seiten bewusst ausgelassen");
            label.show(new StatusNote("gescheitert", StatusNote.Tone.ERROR, null, null));
            assertThat(label.getForeground()).isEqualTo(Comic.RED);
            label.show(StatusNote.plain("50 Knoten <und> 51 Kanten"));
            assertThat(label.getForeground()).isEqualTo(Comic.MUTED);
            assertThat(label.getText()).isEqualTo("50 Knoten <und> 51 Kanten");
            assertThat(label.overLink(10)).isFalse();
        });
    }

    @Test
    void aLinkWhoseWordIsMissingIsDropped() {
        StatusNote note = new StatusNote("fertig", StatusNote.Tone.PLAIN, "Aufträge", () -> { });
        assertThat(note.hasLink()).isFalse();
        StatusNote linked = new StatusNote("unter Aufträge", StatusNote.Tone.PLAIN, "Aufträge", () -> { });
        assertThat(linked.withText("unter Aufträge · Ablage: MaltParser").hasLink()).isTrue();
    }

    private static void click(StatusLabel label, int x) {
        label.dispatchEvent(new MouseEvent(label, MouseEvent.MOUSE_CLICKED, 0L, 0, x, 5, 1, false,
                MouseEvent.BUTTON1));
    }
}
