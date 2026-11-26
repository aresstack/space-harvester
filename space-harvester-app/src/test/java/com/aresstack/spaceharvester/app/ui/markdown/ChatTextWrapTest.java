package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.spaceharvester.app.ui.controls.ScrollColumn;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Angelo 26.11.2026: Antworten im Chat brechen immer auf die Breite der Seitenleiste um; nichts wird rechts
 * abgeschnitten, waagerecht scrollen nur Tabellen und Codeblöcke.
 */
class ChatTextWrapTest {

    /** Schmal wie eine zusammengeschobene Seitenleiste. */
    private static final int SIDEBAR_WIDTH = 280;
    /** Breit wie zuvor aufgezogen: danach wird auf {@link #SIDEBAR_WIDTH} verkleinert. */
    private static final int WIDE_WIDTH = 900;
    private static final int HEIGHT = 2000;

    @ParameterizedTest
    @ValueSource(strings = {
            "Siehe https://example.org/wiki/display/SPACE/Eine+sehr+lange+Seite+mit+vielen+Woertern?pageId=123456789 dort.",
            "Die Klasse `com.example.sidebar.markdown.EineSehrLangeKlasseMitNochLaengeremNamenFuerDenTest` ist gemeint.",
            "Pfad: C:\\Users\\Beispiel\\AppData\\Local\\beispiel-anwendung\\logs\\beispiel-anwendung-2026-11-26.log ist da.",
            "Das\u00a0ist\u00a0ein\u00a0Satz\u00a0mit\u00a0geschuetzten\u00a0Leerzeichen\u00a0zwischen\u00a0allen\u00a0Woertern\u00a0bis\u00a0zum\u00a0Ende.",
            "Z.\u202fB. das\u202fist\u202fein\u202fSatz\u202fmit\u202fschmalen\u202fgeschuetzten\u202fLeerzeichen\u202fzwischen\u202fden\u202fWoertern.",
            "Eine Bindestrich-Wort-Kette-ohne-Leerzeichen-die-sehr-lang-ist-und-weitergeht-bis-zum-Ende-hin.",
            "<div>Ein HTML-Block mit einem langen Satz, der in der schmalen Seitenleiste ebenfalls umbrechen muss.</div>",
            "* Ein Listenpunkt mit https://example.org/eine/sehr/lange/adresse/ohne/leerzeichen/im/listenpunkt/drin",
            "> Ein Zitat mit https://example.org/eine/sehr/lange/adresse/ohne/leerzeichen/im/zitat/drin/und/weiter"
    })
    void everyTextStaysInsideTheSidebar(String markdown) throws Exception {
        AtomicReference<List<String>> overflow = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JPanel transcript = transcriptWith(markdown);
            List<String> found = new ArrayList<>();
            for (JTextComponent text : texts(transcript)) {
                int right = rightEdge(text);
                if (right > text.getWidth()) {
                    found.add(text.getText() + " reicht bis " + right + " statt " + text.getWidth());
                }
                if (SwingUtilities.convertRectangle(text.getParent(), text.getBounds(), transcript).getMaxX()
                        > transcript.getWidth()) {
                    found.add(text.getText() + " liegt rechts außerhalb des Verlaufs");
                }
            }
            overflow.set(found);
        });
        assertThat(overflow.get()).isEmpty();
    }

    @Test
    void anOrdinarySentenceStillBreaksBetweenWords() throws Exception {
        String sentence = "Das ist ein ganz normaler langer Satz, der in der schmalen Seitenleiste zwischen den Woertern "
                + "umbrechen soll und nie mitten in einem Wort getrennt wird.";
        AtomicReference<List<Character>> beforeLineStarts = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JTextComponent text = texts(transcriptWith(sentence)).get(0);
            List<Character> before = new ArrayList<>();
            double lastY = Double.NaN;
            for (int offset = 0; offset < text.getDocument().getLength(); offset++) {
                double y = view(text, offset).getY();
                if (!Double.isNaN(lastY) && y > lastY) {
                    before.add(text.getText().charAt(offset - 1));
                }
                lastY = y;
            }
            beforeLineStarts.set(before);
        });
        assertThat(beforeLineStarts.get()).isNotEmpty().containsOnly(' ');
    }

    @Test
    void codeBlocksKeepTheirLinesAndScrollSideways() throws Exception {
        AtomicReference<Boolean> scrolls = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JPanel transcript = transcriptWith(
                    "```java\nString s = \"eine sehr lange Codezeile, die nicht umbrechen soll, sondern scrollen darf\";\n```");
            JScrollPane code = find(transcript, MarkdownScrollPane.class);
            scrolls.set(code.getViewport().getView().getPreferredSize().width > code.getWidth());
        });
        assertThat(scrolls.get()).isTrue();
    }

    /** Baut den Verlauf wie {@code ChatPanel}: Spalte, die der Breite des Sichtfensters folgt, ohne waagerechten Balken. */
    private static JPanel transcriptWith(String markdown) {
        MarkdownTheme theme = MarkdownTheme.forSurface(Color.WHITE);
        MarkdownMessageView view = new MarkdownMessageView(theme, url -> { }, (code, width) -> null);
        view.setMarkdown(markdown);
        JPanel transcript = new JPanel();
        transcript.setLayout(new BoxLayout(transcript, BoxLayout.Y_AXIS));
        transcript.add(new AssistantMarkdownMessage(view, theme.getMutedForeground()));
        ScrollColumn column = new ScrollColumn(new BorderLayout());
        column.add(transcript, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(column, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setSize(WIDE_WIDTH, HEIGHT);
        layoutAll(scroll);
        scroll.setSize(SIDEBAR_WIDTH, HEIGHT);
        layoutAll(scroll);
        return transcript;
    }

    private static int rightEdge(JTextComponent text) {
        int right = 0;
        for (int offset = 0; offset <= text.getDocument().getLength(); offset++) {
            right = Math.max(right, (int) Math.ceil(view(text, offset).getMaxX()));
        }
        return right;
    }

    private static Rectangle2D view(JTextComponent text, int offset) {
        try {
            return text.modelToView2D(offset);
        } catch (BadLocationException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void layoutAll(Component component) {
        component.doLayout();
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                layoutAll(child);
            }
        }
    }

    private static List<JTextComponent> texts(Container container) {
        List<JTextComponent> found = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (child instanceof JScrollPane) {
                continue; // Codeblöcke und Tabellen scrollen selbst
            }
            if (child instanceof JTextComponent text) {
                found.add(text);
            } else if (child instanceof Container inner) {
                found.addAll(texts(inner));
            }
        }
        return found;
    }

    private static <T> T find(Container container, Class<T> type) {
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container inner) {
                T found = find(inner, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
