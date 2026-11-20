package com.aresstack.spaceharvester.app.ui.markdown;

import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Angelos Fehlerbild: In einer Antwort blieben „## Überschrift“ als Text stehen, Fett und Kursiv erschienen. Das
 * Modell hatte hinter der Marke ein geschütztes bzw. schmales Leerzeichen gesetzt, das CommonMark nicht als
 * Trennzeichen kennt.
 */
public class BlockMarkerSpacingTest {

    private static final String NARROW_NBSP = " ";
    private static final String NBSP = " ";

    private final MarkdownTheme theme = MarkdownTheme.forBubble(new Color(20, 120, 110), Color.WHITE);

    @Test
    public void headingWithTypographicSpaceAfterMarkerRendersAsHeading() {
        String markdown = "##" + NARROW_NBSP + "Kurz-Leitfaden – welche Stellen im **„Anbindungsdokument“** du "
                + "ausfüllen musst\n*(für die Dienstleistung)*\n\n"
                + "| Kapitel | Was |\n|---|---|\n| 2 Zweck | – keine |\n\n---\n\n"
                + "##" + NBSP + "Wie du die fehlenden Informationen beschaffst\n";
        Container rendered = render(markdown);

        List<JTextComponent> texts = textComponents(rendered);
        JTextComponent first = texts.get(0);
        assertTrue(first.getText().startsWith("Kurz-Leitfaden"), first.getText());
        assertTrue(first.getFont().isBold());
        assertTrue(first.getFont().getSize() > theme.getBodyFont().getSize());
        for (JTextComponent text : texts) {
            assertFalse(text.getText().contains("##"), text.getText());
        }
        boolean secondHeading = false;
        for (JTextComponent text : texts) {
            if (text.getText().startsWith("Wie du die fehlenden")) {
                secondHeading = text.getFont().isBold() && text.getFont().getSize() > theme.getBodyFont().getSize();
            }
        }
        assertTrue(secondHeading);
    }

    @Test
    public void listMarkersWithTypographicSpaceStillStartLists() {
        Container rendered = render("-" + NBSP + "Name\n-" + NBSP + "Telefon\n\n1." + NARROW_NBSP + "Erstens\n");
        List<String> markers = new ArrayList<String>();
        collectLabels(rendered, markers);
        assertEquals(3, markers.size());
        assertEquals("•", markers.get(0));
        assertEquals("1.", markers.get(2));
    }

    @Test
    public void codeFencesAndRunningTextKeepTheirCharacters() {
        String code = "```\n##" + NBSP + "bleibt\n```\nIAR" + NARROW_NBSP + "Embedded\n";
        String normalized = BlockMarkerSpacing.normalize(code);
        assertEquals(code, normalized);
    }

    @Test
    public void normalizingKeepsTheLengthSoSourceOffsetsStayValid() throws Exception {
        String markdown = "Text\n\n " + NBSP + "##" + NBSP + "Titel\n";
        String normalized = BlockMarkerSpacing.normalize(markdown);
        assertEquals(markdown.length(), normalized.length());
        assertEquals("Text\n\n  ## Titel\n", normalized);

        final boolean[] exact = new boolean[1];
        SwingUtilities.invokeAndWait(() -> {
            MarkdownMessageView view = new MarkdownMessageView(theme, url -> { }, (source, width) -> null);
            view.setMarkdown(markdown);
            exact[0] = view.sourceOffsetsExact();
        });
        assertTrue(exact[0]);
    }

    @Test
    public void hashtagsWithoutSpaceStayText() {
        assertEquals("#haVdB-support", BlockMarkerSpacing.normalize("#haVdB-support"));
        assertEquals("####### sieben" + NBSP, BlockMarkerSpacing.normalize("####### sieben" + NBSP));
    }

    private Container render(String markdown) {
        return new FlexmarkSwingRenderer(theme, url -> { }, null).render(markdown, true);
    }

    private static List<JTextComponent> textComponents(Component root) {
        List<JTextComponent> found = new ArrayList<JTextComponent>();
        collect(root, found);
        return found;
    }

    private static void collect(Component component, List<JTextComponent> found) {
        if (component instanceof JTextComponent) {
            found.add((JTextComponent) component);
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                collect(child, found);
            }
        }
    }

    private static void collectLabels(Component component, List<String> labels) {
        if (component instanceof JLabel && ((JComponent) component).getParent() != null) {
            labels.add(((JLabel) component).getText());
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                collectLabels(child, labels);
            }
        }
    }
}
