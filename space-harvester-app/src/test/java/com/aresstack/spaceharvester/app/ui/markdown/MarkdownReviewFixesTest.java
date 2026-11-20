package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.enterpriseai.ui.comic.bubble.BubblePalette;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import javax.swing.text.AttributeSet;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.StyledDocument;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Codex-Befunde zu #966: frühes Schließen des Behälterzauns, Referenzlinks mit Definition. */
class MarkdownReviewFixesTest {

    @Test
    void aMarkdownExampleFollowedByProseAndAnotherBlockIsNotUnwrapped() {
        String answer = "```markdown\n# Beispiel\n```\n\nErklärung dazwischen.\n\n```java\nint x = 1;\n```";
        assertThat(MarkdownResponseNormalizer.normalize(answer, true)).isEqualTo(answer);
        assertThat(MarkdownResponseNormalizer.normalize(answer, false)).isEqualTo(answer);
    }

    @Test
    void aSingleWrapperWithAnInnerLabelledBlockIsStillUnwrapped() {
        String answer = "```markdown\n# Titel\n\n```java\nint x = 1;\n```\n\nText.\n```";
        assertThat(MarkdownResponseNormalizer.normalize(answer, true))
                .isEqualTo("# Titel\n\n```java\nint x = 1;\n```\n\nText.");
    }

    @Test
    void aDefinedReferenceLinkIsALinkAndAnUndefinedOneKeepsItsBrackets() throws Exception {
        AtomicReference<List<String>> links = new AtomicReference<>();
        AtomicReference<String> text = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            BubblePalette palette = BubblePalette.windowsPhoneInspired();
            MarkdownMessageView view = new MarkdownMessageView(MarkdownTheme.forBubble(
                    palette.getAssistantBackground(), palette.getAssistantForeground()), url -> { },
                    (code, width) -> null);
            view.setMarkdown("Siehe [Doku][docs] und Quelle [3].\n\n[docs]: https://example.org/doku\n");
            List<String> found = new ArrayList<>();
            StringBuilder all = new StringBuilder();
            for (JTextComponent component : texts(view)) {
                all.append(component.getText());
                if (component.getDocument() instanceof StyledDocument document) {
                    collect(document.getDefaultRootElement(), found);
                }
            }
            links.set(found);
            text.set(all.toString());
        });
        assertThat(text.get()).contains("Doku").contains("[3]");
        assertThat(links.get()).contains("https://example.org/doku");
    }

    private static void collect(Element element, List<String> urls) {
        if (element.isLeaf()) {
            AttributeSet attributes = element.getAttributes();
            for (java.util.Enumeration<?> names = attributes.getAttributeNames(); names.hasMoreElements(); ) {
                Object value = attributes.getAttribute(names.nextElement());
                if (value instanceof String url && url.startsWith("https://")) {
                    urls.add(url);
                }
            }
            return;
        }
        for (int i = 0; i < element.getElementCount(); i++) {
            collect(element.getElement(i), urls);
        }
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
