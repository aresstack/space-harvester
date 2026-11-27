package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifact;
import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifactStore;
import com.aresstack.spaceharvester.app.chat.artifact.SectionDocument;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.markdown.CachingMermaidImageRenderer;
import com.aresstack.spaceharvester.app.ui.markdown.DesktopLinkOpener;
import com.aresstack.spaceharvester.app.ui.markdown.MarkdownMessageView;
import com.aresstack.spaceharvester.app.ui.markdown.MarkdownTheme;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Ein Artefakt des Chats in der Mitte (ADR 0045, Abschnitt 5): das Dokument gerendert mit der vorhandenen
 * Markdown-Anzeige, ein Block je Abschnitt. Ändert die KI einen Abschnitt, rollt die Ansicht dorthin und hebt ihn
 * hervor, bis die nächste Änderung kommt. Die Ansicht liest immer aus dem {@link ChatArtifactStore}; jede Änderung kommt
 * als Ereignis (ADR 0027, kein Polling).
 */
final class ArtifactView extends JPanel {

    /** Gelb wie der Griff der Teiler: die zuletzt geänderten Abschnitte. */
    static final Color CHANGED = new Color(0xF2C94C);

    private final ChatArtifactStore store;
    private final Path folder;
    private final String chatId;
    private final String artifactId;
    private final JPanel column = new JPanel();
    private final JScrollPane scroll;
    private final Map<String, JComponent> blocks = new LinkedHashMap<>();
    private final Consumer<ChatArtifactStore.Event> listener = this::changed;
    private final CachingMermaidImageRenderer mermaid = CachingMermaidImageRenderer.forChat();
    private ChatArtifact shown;
    private List<String> highlighted = List.of();

    ArtifactView(ChatArtifactStore store, Path folder, String chatId, String artifactId) {
        super(new BorderLayout());
        this.store = store;
        this.folder = folder;
        this.chatId = chatId;
        this.artifactId = artifactId;
        setBackground(Comic.SURFACE);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        column.setBorder(BorderFactory.createEmptyBorder(16, 24, 24, 24));
        JPanel holder = new com.aresstack.spaceharvester.app.ui.controls.ScrollColumn(new BorderLayout());
        holder.add(column, BorderLayout.NORTH);
        scroll = Comic.scroll(holder);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll, BorderLayout.CENTER);
        store.addListener(listener);
        ChatArtifact artifact = store.read(folder, chatId, artifactId);
        if (artifact != null) {
            highlighted = artifact.current().changed();
            render(artifact);
        }
    }

    String artifactId() {
        return artifactId;
    }

    /** Der zuletzt gezeigte Stand; {@code null}, wenn es das Artefakt nicht mehr gibt. */
    ChatArtifact artifact() {
        return shown;
    }

    /** Die Kennungen der hervorgehobenen Abschnitte (für Tests). */
    List<String> highlighted() {
        return highlighted;
    }

    /** Meldet die Ansicht beim Speicher ab; danach zeigt sie keine Änderungen mehr. */
    void dispose() {
        store.removeListener(listener);
    }

    private void changed(ChatArtifactStore.Event event) {
        if (!artifactId.equals(event.artifact().id()) || !chatId.equals(event.chatId())
                || !java.util.Objects.equals(folder, event.folder())) {
            return;
        }
        Runnable update = () -> {
            if (!event.changed().isEmpty() || highlighted.isEmpty()) {
                highlighted = event.changed();
            }
            render(event.artifact());
            scrollTo(highlighted);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            update.run();
        } else {
            SwingUtilities.invokeLater(update);
        }
    }

    private void render(ChatArtifact artifact) {
        shown = artifact;
        column.removeAll();
        blocks.clear();
        MarkdownTheme theme = MarkdownTheme.forSurface(Comic.SURFACE);
        if (artifact.kind() == ChatArtifact.Kind.GRAPHIC) {
            column.add(block("````svg\n" + artifact.current().svg().strip() + "\n````", theme, false));
        } else if (artifact.document().sections().isEmpty()) {
            column.add(block("*Das Dokument ist noch leer.*", theme, false));
        } else {
            // Linkdefinitionen gelten im ganzen Dokument; jeder Abschnitt wird für sich gezeichnet und bekommt sie mit.
            List<String> definitions = artifact.document().referenceDefinitions();
            String references = definitions.isEmpty() ? "" : "\n\n" + String.join("\n", definitions) + "\n";
            for (SectionDocument.Section section : artifact.document().sections()) {
                JComponent block = block(section.markdown() + references, theme,
                        highlighted.contains(section.id()));
                block.setName(section.id());
                blocks.put(section.id(), block);
                column.add(block);
            }
        }
        column.revalidate();
        column.repaint();
    }

    private JComponent block(String markdown, MarkdownTheme theme, boolean changed) {
        MarkdownMessageView view = new MarkdownMessageView(theme, DesktopLinkOpener.systemDefault(), mermaid);
        view.setMarkdown(markdown);
        Section block = new Section(view);
        block.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, changed ? CHANGED : Comic.SURFACE),
                BorderFactory.createEmptyBorder(2, 9, 2, 0)));
        return block;
    }

    private void scrollTo(List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        JComponent first = blocks.get(ids.get(0));
        if (first == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            column.validate();
            first.scrollRectToVisible(new java.awt.Rectangle(0, 0, first.getWidth(), Math.min(first.getHeight(),
                    scroll.getViewport().getHeight())));
        });
    }

    /** Ein Abschnitt: so hoch wie sein Markdown bei der Breite der Spalte. */
    private static final class Section extends JPanel {
        private final MarkdownMessageView view;

        Section(MarkdownMessageView view) {
            super(new BorderLayout());
            this.view = view;
            setOpaque(false);
            setAlignmentX(LEFT_ALIGNMENT);
            add(view, BorderLayout.CENTER);
        }

        @Override
        public Dimension getPreferredSize() {
            int width = getWidth();
            Container parent = getParent();
            if (parent != null && parent.getWidth() > 0) {
                Insets insets = parent.getInsets();
                width = parent.getWidth() - insets.left - insets.right;
            }
            if (width <= 0) {
                width = 600;
            }
            Insets own = getInsets();
            int inner = Math.max(1, width - own.left - own.right);
            return new Dimension(width, own.top + own.bottom + view.preferredHeightForWidth(inner));
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, getPreferredSize().height);
        }
    }

    /** Für Tests: die Blöcke der Abschnitte in der Reihenfolge der Anzeige. */
    List<String> sectionOrder() {
        return new ArrayList<>(blocks.keySet());
    }
}
