package com.aresstack.spaceharvester.app.ui.markdown;

import org.junit.jupiter.api.Test;

import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backslash-Escapes sind Markdown-Syntax: der Beleg {@code [\[1\]](…)} im Reiter „Dokument“ zeigt „[1]“. */
public class MarkdownEscapesTest {

    @Test
    public void escapedBracketsAndStarsShowWithoutBackslashes() throws Exception {
        MarkdownTheme theme = MarkdownTheme.forBubble(new Color(20, 120, 110), Color.WHITE);
        Container rendered = new FlexmarkSwingRenderer(theme, url -> { }, null)
                .render("IAR bündelt die Auswertung. [\\[1\\]](research-source:1) und \\*kein\\* Stern", true);
        String text = text(rendered);
        assertTrue(text.contains("Auswertung. [1] und *kein* Stern"), text);
    }

    private static String text(Component component) {
        StringBuilder text = new StringBuilder();
        if (component instanceof JTextComponent) {
            text.append(((JTextComponent) component).getText());
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                text.append(text(child));
            }
        }
        return text.toString();
    }
}
