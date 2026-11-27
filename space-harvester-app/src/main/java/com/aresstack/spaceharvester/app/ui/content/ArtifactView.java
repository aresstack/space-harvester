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
    /** Der Abschnitt, den der Benutzer gerade bearbeitet, sonst {@code null}; Änderungen warten bis danach. */
    private String editing;
    private javax.swing.JTextArea editor;
    private ChatArtifact pending;
    private final Consumer<String> status;

    static final String EDIT = "Abschnitt bearbeiten (Markdown)";
    static final String SAVE = "Übernehmen (Strg+Enter)";
    static final String CANCEL = "Verwerfen (Esc)";

    ArtifactView(ChatArtifactStore store, Path folder, String chatId, String artifactId) {
        this(store, folder, chatId, artifactId, text -> { });
    }

    ArtifactView(ChatArtifactStore store, Path folder, String chatId, String artifactId, Consumer<String> status) {
        super(new BorderLayout());
        this.status = status;
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
        if (!chatId.equals(event.chatId()) || !java.util.Objects.equals(folder, event.folder())) {
            return;
        }
        if (!artifactId.equals(event.artifact().id())) {
            // Eine eingebundene Grafik wurde geändert: das Dokument zeigt sie neu (Verweis, keine Kopie).
            ChatArtifact current = shown;
            if (current != null && current.kind() == ChatArtifact.Kind.DOCUMENT
                    && com.aresstack.spaceharvester.app.chat.artifact.GraphicReferences.ids(current.content())
                    .contains(event.artifact().id())) {
                SwingUtilities.invokeLater(() -> {
                    if (editing == null && shown != null) {
                        render(shown);
                    }
                });
            }
            return;
        }
        Runnable update = () -> {
            if (!event.changed().isEmpty() || highlighted.isEmpty()) {
                highlighted = event.changed();
            }
            if (editing != null) {
                pending = event.artifact();
                return;
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
                JComponent block = block(com.aresstack.spaceharvester.app.chat.artifact.GraphicReferences.inline(
                        section.markdown(), this::svgOf) + references, theme, highlighted.contains(section.id()));
                ((Section) block).pen(() -> edit(section.id()));
                block.setName(section.id());
                blocks.put(section.id(), block);
                column.add(block);
            }
        }
        column.revalidate();
        column.repaint();
    }

    /** Das SVG einer Grafik des Chats, sonst {@code null}. */
    private String svgOf(String graphicId) {
        ChatArtifact graphic = store.read(folder, chatId, graphicId);
        return graphic == null || graphic.kind() != ChatArtifact.Kind.GRAPHIC ? null : graphic.current().svg();
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

    /**
     * Öffnet das Markdown eines Abschnitts zum Bearbeiten (ADR 0045, Abschnitt 5: Stift am Abschnitt). Übernehmen
     * schreibt eine Revision des Benutzers, aber nur auf den Stand, den er gesehen hat; hat die KI inzwischen
     * geschrieben, bleibt der Editor offen und die Statuszeile sagt es.
     */
    void edit(String sectionId) {
        if (shown == null || editing != null) {
            return;
        }
        JComponent block = blocks.get(sectionId);
        SectionDocument.Section section;
        try {
            section = shown.document().section(sectionId);
        } catch (SectionDocument.SectionException gone) {
            return;
        }
        editing = sectionId;
        // Nur Leerraum am Ende weg: ein eingerückter Codeblock am Anfang bleibt beim unveränderten Übernehmen gleich.
        editor = new javax.swing.JTextArea(section.markdown().stripTrailing());
        editor.setLineWrap(true);
        editor.setWrapStyleWord(true);
        editor.setFont(Comic.regular(14f));
        editor.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        com.aresstack.spaceharvester.app.ui.controls.FlatIconButton save =
                new com.aresstack.spaceharvester.app.ui.controls.FlatIconButton(
                        com.aresstack.spaceharvester.app.ui.Glyph.ACCEPT, SAVE);
        com.aresstack.spaceharvester.app.ui.controls.FlatIconButton cancel =
                new com.aresstack.spaceharvester.app.ui.controls.FlatIconButton(
                        com.aresstack.spaceharvester.app.ui.Glyph.CANCEL, CANCEL);
        save.addActionListener(event -> saveEdit());
        cancel.addActionListener(event -> closeEditor());
        editor.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("control ENTER"), "uebernehmen");
        editor.getActionMap().put("uebernehmen", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                saveEdit();
            }
        });
        editor.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ESCAPE"), "verwerfen");
        editor.getActionMap().put("verwerfen", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                closeEditor();
            }
        });
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 3, 0));
        buttons.setOpaque(false);
        buttons.add(save);
        buttons.add(cancel);
        JPanel box = new JPanel(new BorderLayout(0, 4)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        box.setName(sectionId);
        box.setOpaque(false);
        box.setAlignmentX(LEFT_ALIGNMENT);
        box.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 3, 1, 1, CHANGED),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        box.add(editor, BorderLayout.CENTER);
        box.add(buttons, BorderLayout.SOUTH);
        int index = java.util.Arrays.asList(column.getComponents()).indexOf(block);
        column.remove(index);
        column.add(box, index);
        column.revalidate();
        column.repaint();
        editor.requestFocusInWindow();
    }

    /** Für Tests: der offene Editor eines Abschnitts, sonst {@code null}. */
    javax.swing.JTextArea editor() {
        return editor;
    }

    /** Übernimmt den bearbeiteten Abschnitt als Revision des Benutzers. */
    void saveEdit() {
        if (editing == null || shown == null) {
            return;
        }
        String id = editing;
        String text = editor.getText();
        ChatArtifactStore.WriteResult result = store.edit(folder, chatId, artifactId, shown.revision(),
                ChatArtifact.Origin.USER, "vom Benutzer bearbeitet", document -> document.replaceSection(id, text));
        if (!result.success()) {
            status.accept(result.reason().contains("inzwischen geändert")
                    ? "Der Abschnitt wurde inzwischen geändert. Text kopieren, verwerfen und neu bearbeiten."
                    : result.reason());
            return;
        }
        highlighted = result.changed();
        pending = result.artifact();
        closeEditor();
    }

    /** Verwirft die Bearbeitung (Esc). */
    void closeEditor() {
        editing = null;
        editor = null;
        ChatArtifact next = pending != null ? pending : shown;
        pending = null;
        if (next != null) {
            render(next);
        }
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

    /** Ein Abschnitt: so hoch wie sein Markdown bei der Breite der Spalte; rechts der Stift zum Bearbeiten. */
    private static final class Section extends JPanel {
        private final MarkdownMessageView view;
        private JComponent pen;

        Section(MarkdownMessageView view) {
            super(new BorderLayout());
            this.view = view;
            setOpaque(false);
            setAlignmentX(LEFT_ALIGNMENT);
            add(view, BorderLayout.CENTER);
        }

        void pen(Runnable edit) {
            com.aresstack.spaceharvester.app.ui.controls.FlatIconButton button =
                    new com.aresstack.spaceharvester.app.ui.controls.FlatIconButton(
                            com.aresstack.spaceharvester.app.ui.Glyph.PENCIL, EDIT);
            button.addActionListener(event -> edit.run());
            JPanel holder = new JPanel(new BorderLayout());
            holder.setOpaque(false);
            holder.add(button, BorderLayout.NORTH);
            pen = holder;
            add(holder, BorderLayout.EAST);
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
            int penWidth = pen == null ? 0 : pen.getPreferredSize().width;
            int inner = Math.max(1, width - own.left - own.right - penWidth);
            int height = Math.max(view.preferredHeightForWidth(inner), pen == null ? 0 : pen.getPreferredSize().height);
            return new Dimension(width, own.top + own.bottom + height);
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
