package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.speech.FakeReadAloud;
import com.aresstack.spaceharvester.app.speech.ReadAloudBinding;
import com.aresstack.spaceharvester.app.ui.markdown.AssistantMarkdownMessage;
import com.aresstack.spaceharvester.app.ui.markdown.MarkdownMessageView;
import com.aresstack.spaceharvester.app.ui.markdown.MarkdownTheme;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Vorlesen im Chat wie im Verlauf des Enterprise AI Client, mit erfundenen Antworten. */
class ChatReadAloudTest {

    private static final String FIRST = "Der Nachtlauf **Nordlicht** prüft den Spool. Danach startet er z. B. um "
            + "22 Uhr.\n\n- Ablauf und Start\n- Störungen und Bereitschaft\n\nDie Bereitschaft ist erreichbar.";
    private static final String SECOND = "Die Kennzahlen der Woche sind unauffällig.";

    @Test
    void playReadsTheLatestAnswerCleanedLikeThePodcastAndThenEveryNewAnswer() throws Exception {
        FakeReadAloud fake = new FakeReadAloud();
        ReadAloudBinding binding = new ReadAloudBinding(() -> fake, message -> { });
        JPanel transcript = new JPanel();
        ChatReadAloud reading = onEdt(() -> new ChatReadAloud(transcript));
        try {
            AssistantMarkdownMessage first = onEdt(() -> bubble(FIRST));
            SwingUtilities.invokeAndWait(() -> {
                reading.bind(binding);
                reading.answer(first);
            });
            assertThat(reading.orb().isVisible()).isTrue();
            assertThat(reading.orb().getToolTipText()).isEqualTo(ChatReadAloud.PLAY_TIP);

            SwingUtilities.invokeAndWait(reading::toggle);
            assertThat(fake.await(1)).isTrue();
            // gesprochen wird nur der sichtbare Text, bereinigt wie beim Podcast („z. B.“ ausgeschrieben)
            assertThat(fake.spoken.get(0)).startsWith("Der Nachtlauf Nordlicht prüft den Spool.")
                    .contains("zum Beispiel").contains("Störungen und Bereitschaft.").doesNotContain("**");
            assertThat(reading.orb().getToolTipText()).isEqualTo(ChatReadAloud.PAUSE_TIP);

            // der gerade erklingende Abschnitt wird grob hervorgehoben, ein Aufzählungspunkt als Ganzes
            String cleaned = fake.spoken.get(0);
            int bullet = cleaned.indexOf("Störungen");
            SwingUtilities.invokeAndWait(() -> reading.playing(bullet, bullet + "Störungen und Bereitschaft.".length(),
                    0));
            assertThat(onEdt(reading::marked)).isEqualTo("Störungen und Bereitschaft");

            SwingUtilities.invokeAndWait(reading::toggle);
            assertThat(reading.paused()).isTrue();
            assertThat(fake.calls).contains("pause");
            SwingUtilities.invokeAndWait(reading::toggle);
            assertThat(fake.calls).contains("resume");

            // Doppelklick auf „Bereitschaft“ im letzten Absatz liest ab dort weiter
            boolean[] restarted = new boolean[1];
            SwingUtilities.invokeAndWait(() -> {
                JTextComponent last = lastText(first, "erreichbar");
                int word = text(last).indexOf("Bereitschaft") + 3;
                try {
                    Rectangle at = last.modelToView2D(word).getBounds();
                    restarted[0] = reading.readFrom(last, new Point(at.x, at.y + at.height / 2));
                } catch (javax.swing.text.BadLocationException e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThat(restarted[0]).isTrue();
            assertThat(fake.await(2)).isTrue();
            assertThat(fake.spoken.get(1)).isEqualTo("Bereitschaft ist erreichbar.");

            // eine neue Antwort wird bei aktivem Vorlesen gleich vorgelesen (wie im EAC)
            AssistantMarkdownMessage second = onEdt(() -> bubble(SECOND));
            SwingUtilities.invokeAndWait(() -> reading.answered(second));
            assertThat(fake.await(3)).isTrue();
            assertThat(fake.spoken.get(2)).isEqualTo(SECOND);

            // ein anderer Chat beendet das Vorlesen und vergisst die Antworten
            SwingUtilities.invokeAndWait(reading::clear);
            assertThat(reading.speaking()).isFalse();
            assertThat(reading.active()).isFalse();
            assertThat(reading.orb().getToolTipText()).isEqualTo(ChatReadAloud.NO_TEXT_TIP);
        } finally {
            binding.close();
        }
    }

    @Test
    void anAnswerFinishedWhilePausedIsReadAfterTheCurrentOne() throws Exception {
        FakeReadAloud fake = new FakeReadAloud();
        ReadAloudBinding binding = new ReadAloudBinding(() -> fake, message -> { });
        ChatReadAloud reading = onEdt(() -> new ChatReadAloud(new JPanel()));
        try {
            AssistantMarkdownMessage first = onEdt(() -> bubble(FIRST));
            AssistantMarkdownMessage second = onEdt(() -> bubble(SECOND));
            SwingUtilities.invokeAndWait(() -> {
                reading.bind(binding);
                reading.answer(first);
                reading.toggle();
            });
            assertThat(fake.await(1)).isTrue();
            SwingUtilities.invokeAndWait(reading::toggle); // anhalten
            SwingUtilities.invokeAndWait(() -> reading.answered(second));
            assertThat(fake.spoken).hasSize(1);
            SwingUtilities.invokeAndWait(reading::toggle); // fortsetzen
            fake.stop(); // die laufende Antwort ist zu Ende gesprochen
            assertThat(fake.await(2)).isTrue();
            assertThat(fake.spoken.get(1)).isEqualTo(SECOND);
        } finally {
            binding.close();
        }
    }

    @Test
    void anAnswerRowMeasuresItsHeightAtTheNewWidthAfterShrinking() throws Exception {
        AtomicReference<int[]> heights = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            int shrunk = measured(1000, 320);
            int fresh = measured(320, 320);
            heights.set(new int[] {shrunk, fresh});
        });
        // die Hülle misst schon an der neuen Breite des Verlaufs, bevor er ihr diese Breite gibt
        assertThat(heights.get()[0]).isEqualTo(heights.get()[1]);
    }

    /** Vorzugshöhe einer Antwortzeile, gelegt bei {@code before} und gefragt, nachdem der Verlauf {@code after} breit ist. */
    private static int measured(int before, int after) {
        JPanel transcript = new JPanel();
        transcript.setLayout(new javax.swing.BoxLayout(transcript, javax.swing.BoxLayout.Y_AXIS));
        AssistantMarkdownMessage clearance = bubble(FIRST + "\n\n" + FIRST);
        transcript.add(clearance);
        transcript.setSize(before, 2000);
        layout(transcript);
        layout(transcript);
        transcript.setSize(after, 2000);
        return clearance.getPreferredSize().height;
    }

    @Test
    void withoutSpeechTheButtonIsShownLockedWithItsReason() throws Exception {
        // „Ich sehe das immer noch nicht“ (Angelo 24.11.2026): wie im EAC gesperrt mit Grund statt unsichtbar
        ChatReadAloud reading = onEdt(() -> new ChatReadAloud(new JPanel()));
        SwingUtilities.invokeAndWait(() -> {
            reading.answer(bubble(SECOND));
            reading.bind(null);
        });
        assertThat(reading.orb().isVisible()).isTrue();
        assertThat(reading.orb().isEnabled()).isFalse();
        assertThat(reading.orb().getToolTipText()).isEqualTo(ChatReadAloud.NO_SPEECH_TIP);
        SwingUtilities.invokeAndWait(reading::toggle);
        assertThat(reading.speaking()).isFalse();
    }

    @Test
    void sentencesSnapToTheirBoundaries() {
        String text = "Erster Satz. Zweiter Satz!\nPunkt eins.\nPunkt zwei.";
        List<int[]> sentences = ReadAloudMarker.sentences(text, 2, text.indexOf("Punkt zwei") - 1);
        assertThat(sentences).extracting(range -> text.substring(range[0], range[1]).strip())
                .containsExactly("Erster Satz.", "Zweiter Satz!", "Punkt eins.");
    }

    @Test
    void neitherUserNorAssistantCanReachThePlaybackControl() {
        for (int width = 200; width <= 1200; width += 7) {
            int orbX = width - 60;
            for (int scrollbarWidth : new int[] {0, 15}) {
                int inset = ChatPanel.transcriptInset(width, orbX, scrollbarWidth, true);
                assertThat(width - scrollbarWidth - inset).isLessThan(orbX);
                assertThat(ChatPanel.transcriptInset(width, orbX, scrollbarWidth, false)).isZero();
            }
        }
    }

    private static AssistantMarkdownMessage bubble(String markdown) {
        MarkdownTheme theme = MarkdownTheme.forSurface(java.awt.Color.WHITE);
        MarkdownMessageView view = new MarkdownMessageView(theme, url -> { }, (code, width) -> null);
        view.setMarkdown(markdown);
        AssistantMarkdownMessage bubble = new AssistantMarkdownMessage(view, theme.getMutedForeground());
        bubble.setSize(600, bubble.preferredHeightForWidth(600));
        layout(bubble);
        return bubble;
    }

    private static void layout(java.awt.Component component) {
        component.doLayout();
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                layout(child);
            }
        }
    }

    private static JTextComponent lastText(AssistantMarkdownMessage bubble, String containing) {
        JTextComponent found = null;
        for (JTextComponent text : ChatReadAloud.texts(bubble)) {
            if (text(text).contains(containing)) {
                found = text;
            }
        }
        assertThat(found).isNotNull();
        return found;
    }

    private static String text(JTextComponent component) {
        try {
            return component.getDocument().getText(0, component.getDocument().getLength());
        } catch (javax.swing.text.BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }
}
