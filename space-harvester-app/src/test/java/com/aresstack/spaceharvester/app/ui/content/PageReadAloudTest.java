package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.speech.FakeReadAloud;
import com.aresstack.spaceharvester.app.speech.ReadAloudBinding;
import org.junit.jupiter.api.Test;

import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Vorlesen in der Leseansicht wie im Lesebereich des Enterprise AI Client, mit einer erfundenen Seite. */
class PageReadAloudTest {

    private static final String TEXT = "Inhalt\nAblauf\nStörungen\nAblauf\nErst prüft der Nachtlauf Nordlicht den "
            + "Spool, dann startet er.\nStörungen\nBei Störungen die Bereitschaft anrufen.";

    @Test
    void playsPausesAndAfterADoubleClickReadsOnFromTheClickedWord() throws Exception {
        FakeReadAloud fake = new FakeReadAloud();
        List<String> failures = new ArrayList<>();
        ReadAloudBinding binding = new ReadAloudBinding(() -> fake, failures::add);
        JTextPane pane = new JTextPane();
        pane.setText(TEXT);
        pane.setSize(600, 400);
        PageReadAloud reading = new PageReadAloud(pane);
        try {
            SwingUtilities.invokeAndWait(() -> {
                reading.bind(binding);
                reading.speechText(TEXT);
            });
            assertThat(reading.orb().isVisible()).isTrue();
            assertThat(reading.orb().getToolTipText()).isEqualTo(PageReadAloud.PLAY_TIP);

            SwingUtilities.invokeAndWait(reading::toggle);
            assertThat(fake.await(1)).isTrue();
            assertThat(fake.spoken.get(0)).isEqualTo(TEXT);
            assertThat(reading.orb().getToolTipText()).isEqualTo(PageReadAloud.PAUSE_TIP);

            // der gerade erklingende Satz wird hervorgehoben wie im Chat (gemeinsamer ReadAloudMarker)
            int sentence = TEXT.indexOf("Erst prüft");
            SwingUtilities.invokeAndWait(() -> reading.playing(sentence,
                    sentence + "Erst prüft der Nachtlauf Nordlicht den Spool, dann startet er.".length(), 0));
            String[] marked = new String[1];
            SwingUtilities.invokeAndWait(() -> marked[0] = reading.marked());
            assertThat(marked[0]).isEqualTo("Erst prüft der Nachtlauf Nordlicht den Spool, dann startet er");

            SwingUtilities.invokeAndWait(reading::toggle);
            assertThat(reading.paused()).isTrue();
            assertThat(fake.calls).contains("pause");
            SwingUtilities.invokeAndWait(reading::toggle);
            assertThat(fake.calls).contains("resume");

            // „sonst liest er nachher das ganze Inhaltsverzeichnis“: Doppelklick auf „Bereitschaft“ liest ab dort
            int word = TEXT.lastIndexOf("Bereitschaft") + 3;
            boolean[] restarted = new boolean[1];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Rectangle at = pane.modelToView2D(word).getBounds();
                    restarted[0] = reading.readFrom(new Point(at.x, at.y + at.height / 2));
                } catch (javax.swing.text.BadLocationException e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThat(restarted[0]).isTrue();
            assertThat(fake.await(2)).isTrue();
            assertThat(fake.spoken.get(1)).isEqualTo("Bereitschaft anrufen.");
            assertThat(reading.speaking()).isTrue();

            // die Überschrift „Störungen“ im Text, nicht die im Inhaltsverzeichnis
            int heading = TEXT.indexOf("\nStörungen\nBei") + 3;
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Rectangle at = pane.modelToView2D(heading).getBounds();
                    restarted[0] = reading.readFrom(new Point(at.x, at.y + at.height / 2));
                } catch (javax.swing.text.BadLocationException e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThat(fake.await(3)).isTrue();
            assertThat(fake.spoken.get(2)).isEqualTo("Störungen\nBei Störungen die Bereitschaft anrufen.");
            // die Hervorhebung bleibt an der angeklickten Überschrift, nicht im Inhaltsverzeichnis
            int[] markAt = new int[] {-1};
            SwingUtilities.invokeAndWait(() -> {
                reading.playing(0, "Störungen".length(), 0);
                for (javax.swing.text.Highlighter.Highlight highlight : pane.getHighlighter().getHighlights()) {
                    markAt[0] = highlight.getStartOffset();
                }
            });
            assertThat(markAt[0]).isEqualTo(TEXT.indexOf("\nStörungen\nBei") + 1);

            // eine andere Seite beendet das Vorlesen
            SwingUtilities.invokeAndWait(() -> reading.speechText("Eine andere Seite."));
            assertThat(reading.speaking()).isFalse();
            assertThat(failures).isEmpty();
        } finally {
            binding.close();
        }
    }

    @Test
    void aDoubleClickWithoutReadingChangesNothingAndWithoutSpeechTheButtonIsGone() throws Exception {
        JTextPane pane = new JTextPane();
        pane.setText(TEXT);
        pane.setSize(600, 400);
        PageReadAloud reading = new PageReadAloud(pane);

        assertThat(reading.orb().isVisible()).as("ohne Sprachausgabe kein Knopf").isFalse();
        SwingUtilities.invokeAndWait(() -> assertThat(reading.readFrom(new Point(5, 5))).isFalse());

        SwingUtilities.invokeAndWait(() -> {
            reading.bind(new ReadAloudBinding(FakeReadAloud::new, message -> { }));
            reading.speechText("");
        });
        assertThat(reading.orb().isEnabled()).as("ohne KI-Freigabe nichts vorzulesen").isFalse();
        assertThat(reading.orb().getToolTipText()).isEqualTo(PageReadAloud.NO_TEXT_TIP);
    }
}
