package com.aresstack.spaceharvester.app.ui.markdown;

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
            MarkdownTheme theme = MarkdownTheme.forSurface(java.awt.Color.WHITE);
            MarkdownMessageView view = new MarkdownMessageView(theme, url -> { }, (code, width) -> null);
            view.setMarkdown("# Test\n\n* **Zielsetzung:** Das Thema [3].\n* **Prüfprozess:** mehrere Phasen\n");
            AssistantMarkdownMessage bubble = new AssistantMarkdownMessage(view, theme.getMutedForeground());
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

    @Test
    void assistantUsesFullWidthWithoutBubbleAndPlacesActionsBelowMarkdown() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MarkdownTheme theme = MarkdownTheme.forSurface(java.awt.Color.WHITE);
            MarkdownMessageView view = new MarkdownMessageView(theme, url -> { }, (code, width) -> null);
            view.setMarkdown("Eine Antwort");
            AssistantMarkdownMessage message = new AssistantMarkdownMessage(view, theme.getMutedForeground());
            javax.swing.JButton action = new javax.swing.JButton("Kopieren");
            message.setActions(action);
            message.setTimestamp(1700000000000L);
            javax.swing.JPanel parent = new javax.swing.JPanel();
            parent.setLayout(new javax.swing.BoxLayout(parent, javax.swing.BoxLayout.Y_AXIS));
            parent.add(message);
            parent.setSize(800, 1000);
            parent.doLayout();
            message.doLayout();
            assertThat(message.isOpaque()).isFalse();
            assertThat(message.getWidth()).isEqualTo(800);
            assertThat(view.getWidth()).isEqualTo(772);
            java.awt.Component footer = ((java.awt.BorderLayout) message.getLayout())
                    .getLayoutComponent(java.awt.BorderLayout.SOUTH);
            assertThat(footer.getY()).isGreaterThanOrEqualTo(view.getY() + view.getHeight());
            assertThat(SwingUtilities.isDescendingFrom(action, footer)).isTrue();
        });
    }

    @Test
    void footerReservesRightEdgeForActionsAndWrapsSourcesWithoutOverlap() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MarkdownTheme theme = MarkdownTheme.forSurface(java.awt.Color.WHITE);
            AssistantMessageFooter footer = new AssistantMessageFooter(theme.getMutedForeground());
            javax.swing.JPanel actions = new javax.swing.JPanel();
            actions.add(new javax.swing.JButton("Kopieren"));
            footer.setActions(actions);
            footer.setTimestamp(1700000000000L);
            javax.swing.JButton qr = new javax.swing.JButton("QR");
            footer.setQrAction(qr);
            javax.swing.JPanel links = new javax.swing.JPanel();
            links.setPreferredSize(new java.awt.Dimension(300, 60));
            footer.setSources(links);
            for (int width : new int[] {900, 600, 300}) {
                footer.setSize(width, footer.preferredHeightForWidth(width));
                footer.doLayout();
                java.awt.Rectangle controls = qr.getParent().getBounds();
                assertThat(controls.x + controls.width).isEqualTo(width);
                assertThat(links.getBounds().intersects(controls)).isFalse();
                assertThat(links.getX()).isZero();
                assertThat(links.getY() + links.getHeight()).isLessThanOrEqualTo(footer.getHeight());
                assertThat(qr.getParent().getComponent(qr.getParent().getComponentZOrder(qr) - 1))
                        .isInstanceOf(MessageTimestamp.class);
            }
        });
    }

    @Test
    void surfaceThemeKeepsReadableTextInLightAndDarkMode() {
        for (java.awt.Color background : List.of(java.awt.Color.WHITE, new java.awt.Color(24, 24, 24))) {
            MarkdownTheme theme = MarkdownTheme.forSurface(background);
            assertThat(com.aresstack.enterpriseai.ui.comic.theme.ColorContrast.ratio(
                    theme.getForeground(), background)).isGreaterThanOrEqualTo(4.5);
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
