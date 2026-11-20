package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.enterpriseai.ui.comic.bubble.BubblePalette;
import com.aresstack.enterpriseai.ui.comic.bubble.BubbleSide;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Angelo 20.11.2026: Antworten im Chat zeigen Markdown statt roher Sternchen; die Blase ist die Kopie aus dem EAC.
 */
class ChatMarkdownBubbleTest {

    @Test
    void boldAndListsAreRenderedNotShownAsRawMarkdown() throws Exception {
        AtomicReference<String> shown = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            BubblePalette palette = BubblePalette.windowsPhoneInspired();
            MarkdownMessageView view = new MarkdownMessageView(MarkdownTheme.forBubble(
                    palette.getAssistantBackground(), palette.getAssistantForeground()), url -> { },
                    (code, width) -> null);
            view.setMarkdown("# Test\n\n* **Zielsetzung:** Das Thema [3].\n* **Prüfprozess:** mehrere Phasen\n");
            AssistantMarkdownBubble bubble = new AssistantMarkdownBubble(BubbleSide.LEFT, palette, "Assistent", view);
            bubble.setSize(600, bubble.preferredHeightForWidth(600));
            bubble.doLayout();
            StringBuilder text = new StringBuilder();
            for (JTextComponent component : texts(view)) {
                text.append(component.getText()).append('\n');
            }
            shown.set(text.toString());
        });
        assertThat(shown.get()).contains("Das Thema [3].").contains("Zielsetzung:").contains("Test").doesNotContain("**").doesNotContain("# ");
    }

    private static List<JTextComponent> texts(Container root) {
        List<JTextComponent> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof JTextComponent text) {
                found.add(text);
            } else if (child instanceof Container container) {
                found.addAll(texts(container));
            }
        }
        return found;
    }
}
