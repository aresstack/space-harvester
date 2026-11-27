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
import java.awt.Component;
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
    /** Der Ordner des Besitzers (Chat oder Projekt), aus dem die Ansicht liest. */
    private final Path owner;
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
    /** „Zitieren“ über einer Markierung, wie im Chatverlauf (ADR 0045 §7); das Zitat trägt den Abschnitt mit. */
    private final com.aresstack.spaceharvester.app.ui.controls.QuoteOverlayButton quoteOverlay =
            new com.aresstack.spaceharvester.app.ui.controls.QuoteOverlayButton();
    private final javax.swing.JLayeredPane layers;
    private com.aresstack.spaceharvester.app.ui.markdown.QuoteSelection quoted;

    static final String EDIT = "Abschnitt bearbeiten (Markdown)";
    static final String SAVE = "Übernehmen (Strg+Enter)";
    static final String CANCEL = "Verwerfen (Esc)";
    /** Rechtsklick auf eine Grafik (ADR 0045 §6): ein Element statt der ganzen Grafik an den Chat geben. */
    static final String ELEMENT_QUOTE = "Element an Chat anhängen";
    static final String WHOLE_GROUP = "Ganze Gruppe";
    /** Rechtsklick auf ein Mermaid- oder draw.io-Diagramm: als SVG-Grafik übernehmen, danach bearbeitbar. */
    static final String ADOPT = "Als Grafik bearbeiten";
    /** Stift an einer Grafik: die Zeichenfläche öffnen (ADR 0045 §6). */
    static final String DRAW = "Selbst zeichnen";
    /** Die offene Zeichenfläche, sonst {@code null}. */
    private GraphicCanvas drawing;

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
        this.owner = store.owner(folder, chatId);
        setBackground(Comic.SURFACE);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        column.setBorder(BorderFactory.createEmptyBorder(16, 24, 24, 24));
        JPanel holder = new com.aresstack.spaceharvester.app.ui.controls.ScrollColumn(new BorderLayout());
        holder.add(column, BorderLayout.NORTH);
        scroll = Comic.scroll(holder);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        layers = new javax.swing.JLayeredPane() {
            @Override
            public void doLayout() {
                scroll.setBounds(0, 0, getWidth(), getHeight());
            }
        };
        layers.add(scroll, javax.swing.JLayeredPane.DEFAULT_LAYER);
        layers.add(quoteOverlay, javax.swing.JLayeredPane.POPUP_LAYER);
        quoteOverlay.setVisible(false);
        quoteOverlay.addActionListener(event -> acceptQuote());
        scroll.getViewport().addChangeListener(event -> hideQuoteOverlay());
        add(layers, BorderLayout.CENTER);
        column.putClientProperty(com.aresstack.spaceharvester.app.ui.markdown.MermaidDiagramPanel.MENU,
                (java.util.function.Function<com.aresstack.spaceharvester.app.ui.markdown.MermaidDiagramPanel,
                        javax.swing.JPopupMenu>) this::diagramMenu);
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
        // Ein Projekt teilt seine Artefakte mit allen seinen Chats (ADR 0043, F3): maßgeblich ist der Besitzer.
        if (!java.util.Objects.equals(owner, event.owner()) || event.deleted()) {
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
            if (drawing != null) {
                drawing.external(event.artifact());
                return;
            }
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
            JComponent block = block("````svg\n" + artifact.current().svg().strip() + "\n````", theme, false);
            ((Section) block).pen(com.aresstack.spaceharvester.app.ui.Glyph.PENCIL, DRAW, this::draw);
            column.add(referenced(block, reference(artifact, null)));
        } else if (artifact.document().sections().isEmpty()) {
            column.add(block("*Das Dokument ist noch leer.*", theme, false));
        } else {
            // Linkdefinitionen gelten im ganzen Dokument; jeder Abschnitt wird für sich gezeichnet und bekommt sie mit.
            List<String> definitions = artifact.document().referenceDefinitions();
            String references = definitions.isEmpty() ? "" : "\n\n" + String.join("\n", definitions) + "\n";
            for (SectionDocument.Section section : artifact.document().sections()) {
                JComponent block = block(com.aresstack.spaceharvester.app.chat.artifact.GraphicReferences.inline(
                        section.markdown(), this::svgOf, true) + references, theme, highlighted.contains(section.id()));
                ((Section) block).pen(com.aresstack.spaceharvester.app.ui.Glyph.PENCIL, EDIT,
                        () -> edit(section.id()));
                referenced(block, reference(artifact, section.id()));
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

    /**
     * Der Bezug eines Zitats aus diesem Artefakt (ADR 0045 §7), so wie ihn die KI in ihren Werkzeugen wiederfindet:
     * „Dokument D1 „Gartenfibel“, Abschnitt a2“ oder „Grafik G1 „Ablauf““.
     */
    static String reference(ChatArtifact artifact, String sectionId) {
        String kind = artifact.kind() == ChatArtifact.Kind.GRAPHIC ? "Grafik" : "Dokument";
        return kind + " " + artifact.id() + " \u201E" + artifact.title() + "\u201C"
                + (sectionId == null ? "" : ", Abschnitt " + sectionId);
    }

    private static JComponent referenced(JComponent block, String reference) {
        block.putClientProperty(com.aresstack.spaceharvester.app.ui.controls.TextContextMenu.QUOTE_REFERENCE,
                reference);
        return block;
    }

    /** Eine Markierung im Artefakt: „Zitieren“ mittig darüber. */
    private void showQuoteOverlay(com.aresstack.spaceharvester.app.ui.markdown.QuoteSelection selection) {
        if (selection == null || selection.anchor() == null || !selection.anchor().isShowing()) {
            hideQuoteOverlay();
            return;
        }
        quoted = selection;
        quoteOverlay.place(selection.anchor(), selection.bounds(), layers);
    }

    private void hideQuoteOverlay() {
        quoted = null;
        if (quoteOverlay.isVisible()) {
            quoteOverlay.setVisible(false);
            layers.repaint();
        }
    }

    /** „Zitieren“ geklickt: über denselben Weg wie „An Chat anhängen“, mit Artefakt und Abschnitt. */
    void acceptQuote() {
        com.aresstack.spaceharvester.app.ui.markdown.QuoteSelection selection = quoted;
        hideQuoteOverlay();
        if (selection != null && com.aresstack.spaceharvester.app.ui.controls.TextContextMenu.quote(
                selection.anchor(), selection.selectedText())) {
            selection.clearHighlight();
        }
    }

    /**
     * Das Rechtsklick-Menü einer Grafik im Artefakt: „An Chat anhängen“ für die ganze Grafik und je Element (Gruppen
     * als Untermenü); ein Mermaid- oder draw.io-Diagramm eines Abschnitts lässt sich als Grafik übernehmen.
     */
    javax.swing.JPopupMenu diagramMenu(com.aresstack.spaceharvester.app.ui.markdown.MermaidDiagramPanel panel) {
        ChatArtifact current = shown;
        if (current == null || editing != null) {
            return null;
        }
        String sectionId = sectionOf(panel);
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        ChatArtifact graphic = graphicOf(panel, current, sectionId);
        if (graphic != null) {
            String reference = reference(graphic, null);
            javax.swing.JMenuItem whole = new javax.swing.JMenuItem(
                    com.aresstack.spaceharvester.app.ui.controls.TextContextMenu.QUOTE);
            whole.addActionListener(event -> com.aresstack.spaceharvester.app.ui.controls.TextContextMenu.quote(
                    panel, "Grafik \u201E" + graphic.title() + "\u201C", reference));
            menu.add(whole);
            List<com.aresstack.spaceharvester.app.chat.artifact.SvgElements.Item> items =
                    com.aresstack.spaceharvester.app.chat.artifact.SvgElements.list(graphic.current().svg());
            if (!items.isEmpty()) {
                javax.swing.JMenu elements = new javax.swing.JMenu(ELEMENT_QUOTE);
                addElements(elements, items, 0, items.size(), panel, reference);
                menu.add(elements);
            }
        } else if (current.kind() == ChatArtifact.Kind.DOCUMENT && sectionId != null
                && panel.kind() != com.aresstack.spaceharvester.app.ui.markdown.MermaidDiagramPanel.Kind.SVG) {
            javax.swing.JMenuItem adopt = new javax.swing.JMenuItem(ADOPT);
            adopt.addActionListener(event -> adopt(panel, sectionId));
            menu.add(adopt);
        }
        return menu.getComponentCount() == 0 ? null : menu;
    }

    /** Die Elemente {@code from} bis {@code to} einer Ebene; eine Gruppe mit Teilen wird ein Untermenü. */
    private static void addElements(javax.swing.JMenu into,
                                    List<com.aresstack.spaceharvester.app.chat.artifact.SvgElements.Item> items,
                                    int from, int to, Component source, String reference) {
        int i = from;
        while (i < to) {
            com.aresstack.spaceharvester.app.chat.artifact.SvgElements.Item item = items.get(i);
            int end = i + 1;
            while (end < to && items.get(end).depth() > item.depth()) {
                end++;
            }
            String label = item.line().strip();
            Runnable quote = () -> com.aresstack.spaceharvester.app.ui.controls.TextContextMenu.quote(source, label,
                    reference + ", Element " + item.id());
            if (end > i + 1) {
                javax.swing.JMenu group = new javax.swing.JMenu(label);
                javax.swing.JMenuItem all = new javax.swing.JMenuItem(WHOLE_GROUP);
                all.addActionListener(event -> quote.run());
                group.add(all);
                group.addSeparator();
                addElements(group, items, i + 1, end, source, reference);
                into.add(group);
            } else {
                javax.swing.JMenuItem one = new javax.swing.JMenuItem(label);
                one.addActionListener(event -> quote.run());
                into.add(one);
            }
            i = end;
        }
    }

    /** Der Abschnitt, in dem {@code component} steht, sonst {@code null}. */
    private String sectionOf(Component component) {
        for (Map.Entry<String, JComponent> entry : blocks.entrySet()) {
            if (SwingUtilities.isDescendingFrom(component, entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** Die Grafik hinter einem gezeigten SVG: diese Grafik selbst oder eine im Abschnitt eingebundene. */
    private ChatArtifact graphicOf(com.aresstack.spaceharvester.app.ui.markdown.MermaidDiagramPanel panel,
                                   ChatArtifact current, String sectionId) {
        if (panel.kind() != com.aresstack.spaceharvester.app.ui.markdown.MermaidDiagramPanel.Kind.SVG) {
            return null;
        }
        if (current.kind() == ChatArtifact.Kind.GRAPHIC) {
            return current;
        }
        if (sectionId == null) {
            return null;
        }
        // Das gezeigte SVG trägt die Kennung seiner Grafik (GraphicReferences.inline), nie raten über den Inhalt.
        String id = com.aresstack.spaceharvester.app.chat.artifact.GraphicReferences.idOf(panel.getDiagramCode());
        ChatArtifact graphic = id == null ? null : store.read(folder, chatId, id);
        return graphic != null && graphic.kind() == ChatArtifact.Kind.GRAPHIC ? graphic : null;
    }

    /**
     * Übernimmt ein Mermaid- oder draw.io-Diagramm als eigene Grafik (Angelo 27.11.): das SVG, das die Anzeige zeichnet,
     * wird eine Grafik mit Element-Kennungen, und im Abschnitt steht danach der Verweis statt des Quelltexts.
     */
    void adopt(com.aresstack.spaceharvester.app.ui.markdown.MermaidDiagramPanel panel, String sectionId) {
        ChatArtifact seen = shown;
        if (seen == null) {
            return;
        }
        status.accept("Diagramm wird als Grafik übernommen …");
        new javax.swing.SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return panel.svg();
            }

            @Override
            protected void done() {
                String svg;
                try {
                    svg = get();
                } catch (Exception failed) {
                    Throwable cause = failed.getCause() != null ? failed.getCause() : failed;
                    status.accept("Das Diagramm ließ sich nicht als Grafik übernehmen: " + cause.getMessage());
                    return;
                }
                status.accept(adoptSvg(seen, sectionId, panel.getDiagramCode(), svg));
            }
        }.execute();
    }

    /** Legt die Grafik an und setzt den Verweis an die Stelle des Codeblocks; liefert die Meldung für die Statuszeile. */
    String adoptSvg(ChatArtifact seen, String sectionId, String code, String svg) {
        SectionDocument.Section section;
        try {
            section = seen.document().section(sectionId);
        } catch (SectionDocument.SectionException gone) {
            return "Der Abschnitt ist nicht mehr da.";
        }
        if (com.aresstack.spaceharvester.app.chat.artifact.GraphicReferences.replaceBlock(section.markdown(), code,
                "") == null) {
            return "Das Diagramm steht nicht mehr im Abschnitt.";
        }
        String title = section.heading() == null || section.heading().isBlank() ? seen.title() : section.heading();
        ChatArtifact graphic = store.createGraphic(folder, chatId, title, svg, ChatArtifact.Origin.USER);
        if (graphic == null) {
            return "Die Grafik konnte nicht angelegt werden.";
        }
        String embed = com.aresstack.spaceharvester.app.chat.artifact.GraphicReferences.reference(graphic);
        ChatArtifactStore.WriteResult result = store.edit(folder, chatId, artifactId, seen.revision(),
                ChatArtifact.Origin.USER, "Diagramm als Grafik " + graphic.id() + " übernommen",
                document -> document.replaceSection(sectionId, com.aresstack.spaceharvester.app.chat.artifact
                        .GraphicReferences.replaceBlock(document.section(sectionId).markdown(), code, embed)));
        if (!result.success()) {
            return "Grafik " + graphic.id() + " angelegt, aber der Abschnitt wurde inzwischen geändert: "
                    + result.reason();
        }
        return "Diagramm ist jetzt Grafik " + graphic.id() + " und lässt sich bearbeiten.";
    }

    /** Der schwebende Knopf „Zitieren“ (für Tests). */
    com.aresstack.spaceharvester.app.ui.controls.QuoteOverlayButton quoteOverlay() {
        return quoteOverlay;
    }

    private JComponent block(String markdown, MarkdownTheme theme, boolean changed) {
        MarkdownMessageView view = new MarkdownMessageView(theme, DesktopLinkOpener.systemDefault(), mermaid);
        view.setMarkdown(markdown);
        view.setQuoteSelectionListener(new com.aresstack.spaceharvester.app.ui.markdown.QuoteSelectionListener() {
            @Override
            public void selectionMade(com.aresstack.spaceharvester.app.ui.markdown.QuoteSelection selection) {
                showQuoteOverlay(selection);
            }

            @Override
            public void selectionCleared() {
                hideQuoteOverlay();
            }
        });
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

    /**
     * Öffnet die Zeichenfläche dieser Grafik (Stift an der Grafik): sie füllt die Ansicht; jede Änderung ist sofort eine
     * Revision. Fertig (Esc) zeigt die Grafik wieder als Bild.
     */
    void draw() {
        ChatArtifact graphic = store.read(folder, chatId, artifactId);
        if (graphic == null || graphic.kind() != ChatArtifact.Kind.GRAPHIC || drawing != null) {
            return;
        }
        editing = artifactId;
        drawing = new GraphicCanvas(store, folder, chatId, graphic, this::closeDrawing, status) {
            @Override
            public Dimension getPreferredSize() {
                Insets insets = column.getInsets();
                int height = scroll.getViewport().getExtentSize().height - insets.top - insets.bottom;
                return new Dimension(super.getPreferredSize().width, Math.max(height, super.getMinimumSize().height));
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        drawing.setAlignmentX(LEFT_ALIGNMENT);
        column.removeAll();
        blocks.clear();
        column.add(drawing);
        column.revalidate();
        column.repaint();
    }

    /** Schließt die Zeichenfläche und zeigt den neuesten Stand. */
    void closeDrawing() {
        if (drawing == null) {
            return;
        }
        drawing = null;
        editing = null;
        pending = null;
        ChatArtifact latest = store.read(folder, chatId, artifactId);
        if (latest != null) {
            render(latest);
        }
    }

    /** Für Tests: die offene Zeichenfläche, sonst {@code null}. */
    GraphicCanvas drawing() {
        return drawing;
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

        void pen(com.aresstack.spaceharvester.app.ui.Glyph glyph, String tooltip, Runnable edit) {
            com.aresstack.spaceharvester.app.ui.controls.FlatIconButton button =
                    new com.aresstack.spaceharvester.app.ui.controls.FlatIconButton(glyph, tooltip);
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

    /** Der Block eines Abschnitts (für Tests), sonst {@code null}. */
    JComponent block(String sectionId) {
        return blocks.get(sectionId);
    }

    /** Für Tests: die Blöcke der Abschnitte in der Reihenfolge der Anzeige. */
    List<String> sectionOrder() {
        return new ArrayList<>(blocks.keySet());
    }
}
