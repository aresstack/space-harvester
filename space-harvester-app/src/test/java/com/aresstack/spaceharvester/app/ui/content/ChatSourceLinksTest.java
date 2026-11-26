package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.chat.ChatRecord;
import com.aresstack.spaceharvester.app.ui.Glyph;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Die Quellen einer Antwort (Angelo, 26.11.2026): eingeklappt eine Textzeile, die mit „…“ endet, wenn sie nicht
 * passt; davor immer ein Pfeil (nach rechts, aufgeklappt nach unten); aufgeklappt die Links untereinander.
 */
class ChatSourceLinksTest {

    private static ChatPanel.SourceLinks links(int count) {
        ChatPanel.SourceLinks links = new ChatPanel.SourceLinks(source -> { });
        List<ChatRecord.Source> sources = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            sources.add(new ChatRecord.Source("Erfundene Beispielseite " + i, "handbuch", "seite-" + i + ".md", null));
        }
        links.show(sources);
        return links;
    }

    private static void size(ChatPanel.SourceLinks links, int width) {
        links.setSize(width, links.preferredHeightForWidth(width));
        links.doLayout();
    }

    @Test
    void collapsedItIsOneLineOfPlainTextBehindTheArrow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChatPanel.SourceLinks links = links(8);
            size(links, 400);
            assertThat(links.expanded()).isFalse();
            assertThat(links.getHeight()).isEqualTo(20);
            assertThat(links.toggle().isVisible()).isTrue();
            assertThat(links.toggle().getX()).isZero();
            assertThat(links.toggle().getIcon()).isSameAs(Glyph.EXPAND);
            assertThat(links.toggle().getToolTipText()).isEqualTo(ChatPanel.SourceLinks.SHOW_ALL);
            assertThat(links.summary()).startsWith("[1] Erfundene Beispielseite 1, [2] Erfundene Beispielseite 2");
            // Kein Link ist zu sehen; die Zeile endet am Rand, den Rest kürzt das Etikett mit „…“.
            for (int i = 0; i < 8; i++) {
                assertThat(links.getComponent(i).isVisible()).isFalse();
            }
            java.awt.Component summary = links.getComponent(9);
            assertThat(summary.isVisible()).isTrue();
            assertThat(summary.getX()).isGreaterThanOrEqualTo(links.toggle().getWidth());
            assertThat(summary.getX() + summary.getWidth()).isEqualTo(400);
            assertThat(summary.getPreferredSize().width).isGreaterThan(summary.getWidth());
        });
    }

    @Test
    void theArrowIsAlsoThereWhenEverythingFits() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChatPanel.SourceLinks links = links(1);
            size(links, 900);
            assertThat(links.toggle().isVisible()).isTrue();
            java.awt.Component summary = links.getComponent(2);
            assertThat(summary.getWidth()).isEqualTo(summary.getPreferredSize().width);
        });
    }

    @Test
    void expandedTheLinksStandBelowEachOtherAndItCollapsesAgain() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChatPanel.SourceLinks links = links(4);
            size(links, 400);
            links.toggle().doClick();
            size(links, 400);
            assertThat(links.expanded()).isTrue();
            assertThat(links.toggle().getIcon()).isSameAs(Glyph.COLLAPSE);
            assertThat(links.toggle().getToolTipText()).isEqualTo(ChatPanel.SourceLinks.SHOW_LESS);
            assertThat(links.getComponent(5).isVisible()).isFalse();
            int x = links.getComponent(0).getX();
            int y = -1;
            for (int i = 0; i < 4; i++) {
                java.awt.Component entry = links.getComponent(i);
                assertThat(entry).isInstanceOf(javax.swing.JButton.class);
                assertThat(entry.isVisible()).isTrue();
                assertThat(entry.getX()).isEqualTo(x);
                assertThat(entry.getY()).isGreaterThan(y);
                y = entry.getY();
            }
            assertThat(y + links.getComponent(3).getHeight()).isLessThanOrEqualTo(links.getHeight());
            links.toggle().doClick();
            size(links, 400);
            assertThat(links.getHeight()).isEqualTo(20);
        });
    }
}
