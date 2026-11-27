package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifact;
import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifactStore;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.ClosableTab;
import com.aresstack.spaceharvester.app.ui.controls.WrapLayout;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Schließbare Reiter über einer Fläche, in zwei Rollen (ADR 0047 §5, Entwurf UX 27.11.2026):
 * <ul>
 *   <li>als <b>Artefakt-Mitte</b>, wenn links „Artefakte“ steht: die geöffneten Artefakte des Projekts, über einem
 *       Hinweis, solange keines gewählt ist. Welche Reiter offen waren, merkt sich der Chat
 *       ({@link ChatArtifactStore#openTabs}).</li>
 *   <li>im Reiter <b>„Export“</b>: nur die Export-Reiter über der Export-Fläche der Auswahl links. Ein Klick auf einen
 *       Knopf der Ausgabe öffnet einen eigenen Reiter für Auswahl und Auftrag, der stehen bleibt.</li>
 * </ul>
 * Die Reiterzeile erscheint erst, wenn ein Reiter offen ist. Die Knöpfe unten ({@link #footBar}) richten sich nach dem
 * gewählten Reiter.
 */
final class ArtifactTabs implements ChatPanel.ArtifactHost {

    static final String CLOSE = "Reiter schließen (auch Mittelklick auf den Reiter)";
    static final String UNDO = "Letzte Änderung rückgängig machen";
    static final String DELETE = "Artefakt löschen (bis zum nächsten Start links unter „Artefakte“ wiederherstellbar)";
    static final String DELETE_TITLE = com.aresstack.spaceharvester.app.ui.Texts.ARTIFACT_DELETE_TITLE;

    static String deleteQuestion(String title) {
        return com.aresstack.spaceharvester.app.ui.Texts.artifactDeleteQuestion(title);
    }
    static final String EXPORT_AS = "Exportieren als %s (Ablage im Ausgabeordner)";
    static final String CLOSE_EXPORT = "Reiter schließen; der Auftrag läuft weiter (auch Mittelklick)";
    static final String NEW_EXPORT = "Export";
    private static final String PAGE = "seite";
    private static final String ARTIFACT = "artefakt";

    private final ChatArtifactStore store;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JPanel strip = new JPanel(new BorderLayout());
    // Umbrechend mit mitwachsender Höhe, damit bei vielen oder langen Reitern keiner abgeschnitten wird.
    private final JPanel pills = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 4));
    /** Rechts in der Reiterzeile, nur bei gewähltem Artefakt: Rückgängig (ADR 0045, Abschnitt 8). */
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 4));
    private final com.aresstack.spaceharvester.app.ui.controls.FlatIconButton undo =
            new com.aresstack.spaceharvester.app.ui.controls.FlatIconButton(
                    com.aresstack.spaceharvester.app.ui.Glyph.UNDO, UNDO);
    private final com.aresstack.spaceharvester.app.ui.controls.FlatIconButton delete =
            new com.aresstack.spaceharvester.app.ui.controls.FlatIconButton(
                    com.aresstack.spaceharvester.app.ui.Glyph.TRASH, DELETE);
    /** Die „wirklich“-Rückfrage (Ja/Nein) vor dem Löschen; Tests antworten selbst. */
    java.util.function.BiPredicate<String, String> confirm = (title, question) ->
            com.aresstack.spaceharvester.app.ui.Dialogs.confirm(root, title, question);
    /** Die Formate des gewählten Artefakts für die untere Leiste: nur, was es kann (ADR 0045 §5). */
    private final JPanel documentBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
    private final JPanel graphicBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
    /** Export-Reiter der Sitzung, je Kennung; sie gehören keinem Chat und bleiben beim Chatwechsel. */
    private final Map<String, ExportTab> exports = new LinkedHashMap<>();
    private final java.util.function.Supplier<ExportPanel> exportPanels;
    private int nextExport = 1;
    private String selectedExport;
    /** Meldet einen Wechsel des gewählten Reiters, damit die untere Leiste folgt. */
    private Runnable selectionChanged = () -> { };
    private final java.util.function.Supplier<Path> output;
    private final Consumer<String> status;
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JPanel artifactSlot = new JPanel(new BorderLayout());
    private final Map<String, Tab> tabs = new LinkedHashMap<>();
    /** Zeigt die Export-Ansicht, wenn ein Artefakt geöffnet wird (etwa weil die KI daran schreibt). */
    private Runnable reveal = () -> { };
    private ButtonGroup group = new ButtonGroup();
    private final Consumer<ChatArtifactStore.Event> titles = this::retitle;
    private Path folder;
    private String chatId;
    private volatile String selected;
    /** Ob diese Fläche vorn steht; nur die Artefakt-Mitte wird je verdeckt. */
    private boolean front = true;
    private int unseen;
    private java.util.function.IntConsumer unseenChanged = count -> { };

    private record Tab(ClosableTab pill, ArtifactView view) {
    }

    private record ExportTab(ClosableTab pill, ExportPanel panel) {
    }

    ArtifactTabs(ChatArtifactStore store, JComponent page) {
        this(store, page, () -> null, text -> { }, () -> null);
    }

    /**
     * @param output der Ausgabeordner der Einstellungen ({@code null} ohne)
     * @param status zeigt eine Meldung in der Statuszeile
     * @param exportPanels eine neue Export-Fläche für einen Export-Reiter; {@code null} ohne Export-Reiter
     */
    ArtifactTabs(ChatArtifactStore store, JComponent page, java.util.function.Supplier<Path> output,
                 Consumer<String> status, java.util.function.Supplier<ExportPanel> exportPanels) {
        this.store = store;
        this.output = output;
        this.status = status;
        this.exportPanels = exportPanels;
        documentBar.setOpaque(false);
        graphicBar.setOpaque(false);
        for (com.aresstack.spaceharvester.app.chat.ChatExport.Format format
                : com.aresstack.spaceharvester.app.chat.ChatExport.Format.values()) {
            documentBar.add(formatButton(formatIcon(format), format.label, () -> exportSelected(format)));
        }
        graphicBar.add(formatButton("svg", "SVG", () -> exportSelected(null)));
        pills.setOpaque(false);
        actions.setOpaque(false);
        actions.add(undo);
        actions.add(delete);
        actions.setVisible(false);
        undo.addActionListener(event -> undo());
        delete.addActionListener(event -> deleteSelected());
        strip.add(pills, BorderLayout.CENTER);
        strip.add(actions, BorderLayout.EAST);
        strip.setOpaque(true);
        strip.setBackground(Comic.SURFACE);
        strip.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Comic.LINE));
        strip.setVisible(false);
        artifactSlot.setOpaque(false);
        center.setOpaque(false);
        center.add(page, PAGE);
        center.add(artifactSlot, ARTIFACT);
        root.setOpaque(false);
        root.add(strip, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        store.addListener(titles);
    }

    JComponent component() {
        return root;
    }

    @Override
    public void chat(Path folder, String chatId) {
        if (Objects.equals(this.folder, folder) && Objects.equals(this.chatId, chatId)) {
            return;
        }
        this.folder = folder;
        this.chatId = chatId;
        boolean wasShowing = selected != null;
        String showingExport = selectedExport;
        tabs.values().forEach(tab -> tab.view().dispose());
        tabs.clear();
        group = new ButtonGroup();
        pills.removeAll();
        exports.values().forEach(tab -> {
            group.add(tab.pill());
            pills.add(tab.pill());
        });
        selected = null;
        artifactSlot.removeAll();
        if (folder != null && chatId != null) {
            for (String id : store.openTabs(folder, chatId)) {
                add(id);
            }
        }
        refreshStrip();
        // Export-Reiter gehören nicht zum Chat: der gewählte bleibt vorn.
        if (showingExport != null && exports.containsKey(showingExport)) {
            selectExport(showingExport);
        } else if (wasShowing) {
            showPage();
        }
    }

    @Override
    public void open(String artifactId) {
        if (chatId == null || artifactId == null) {
            return;
        }
        String id = artifactId.strip().toUpperCase(java.util.Locale.ROOT);
        if (!tabs.containsKey(id) && add(id) == null) {
            return;
        }
        remember();
        select(id);
        reveal.run();
    }

    /** Was beim Öffnen eines Artefakts die Artefakt-Mitte nach vorn holt (links „Artefakte“). */
    void reveal(Runnable reveal) {
        this.reveal = reveal == null ? () -> { } : reveal;
    }

    /**
     * Die KI hat ein Artefakt angelegt oder geändert (ADR 0047 §5, Entwurf UX): Steht die Artefakt-Mitte vorn, wie
     * {@link #open}. Sonst schaltet nichts um: Der Reiter kommt still dazu, die Zahl am Chip der Wippe steigt, und die
     * Statuszeile meldet es einmal.
     */
    @Override
    public void changed(String artifactId) {
        if (front || chatId == null || artifactId == null) {
            open(artifactId);
            return;
        }
        String id = artifactId.strip().toUpperCase(java.util.Locale.ROOT);
        ChatArtifact artifact = store.read(folder, chatId, id);
        if (artifact == null) {
            return;
        }
        if (!tabs.containsKey(id) && add(id) != null) {
            remember();
        }
        unseen++;
        unseenChanged.accept(unseen);
        status.accept((artifact.kind() == ChatArtifact.Kind.GRAPHIC ? "Grafik" : "Dokument") + " „"
                + artifact.title() + "“ " + (artifact.revision() <= 1 ? "angelegt" : "geändert"));
    }

    /** Ob die Artefakt-Mitte vorn steht; beim Öffnen verschwindet die Zahl am Chip. */
    void front(boolean shown) {
        front = shown;
        if (shown && unseen > 0) {
            unseen = 0;
            unseenChanged.accept(0);
        }
    }

    /** Meldet, wie viele Artefakte seit dem letzten Blick neu oder geändert sind (Chip an „Artefakte“). */
    void onUnseen(java.util.function.IntConsumer listener) {
        unseenChanged = listener == null ? count -> { } : listener;
    }

    /** Alle Artefakte des offenen Chats (für das Menü „Weitere Aktionen“), auch die ohne offenen Reiter. */
    List<ChatArtifact> artifacts() {
        return folder == null || chatId == null ? List.of() : store.list(folder, chatId);
    }

    @Override
    public String focus() {
        return selected;
    }

    /** Meldet jeden Wechsel des gewählten Reiters (für die untere Leiste). */
    void onSelectionChanged(Runnable changed) {
        selectionChanged = changed == null ? () -> { } : changed;
    }

    /**
     * Die Knöpfe für die untere Leiste: beim Artefakt nur seine Formate, beim Export-Reiter die Knöpfe seiner Fläche;
     * {@code null} ohne gewählten Reiter (dann die Knöpfe der Export-Fläche der Auswahl links).
     */
    JComponent footBar() {
        if (selectedExport != null) {
            ExportTab tab = exports.get(selectedExport);
            return tab == null ? null : tab.panel().formatBar();
        }
        if (selected == null) {
            return null;
        }
        ChatArtifact artifact = store.read(folder, chatId, selected);
        return artifact != null && artifact.kind() == ChatArtifact.Kind.GRAPHIC ? graphicBar : documentBar;
    }

    /**
     * Öffnet einen Export-Reiter für {@code selection} und startet darin {@code key} (Format oder Export aus der
     * Quelle); ohne {@code key} („Neuer Export“) nur den Reiter. Der Reiter bleibt bei dieser Auswahl stehen.
     */
    void openExport(ExportPanel.Selection selection, String key) {
        ExportPanel panel = exportPanels == null || selection == null ? null : exportPanels.get();
        if (panel == null) {
            return;
        }
        String id = "E" + nextExport++;
        String title = (selection.title() == null || selection.title().isBlank() ? NEW_EXPORT : selection.title())
                + (key == null ? "" : " · " + ExportPanel.exportTitle(key));
        ClosableTab pill = new ClosableTab(ComposerIcons.export(), title, title, CLOSE_EXPORT, () -> closeExport(id));
        pill.addActionListener(event -> selectExport(id));
        exports.put(id, new ExportTab(pill, panel));
        group.add(pill);
        pills.add(pill);
        panel.selection(selection);
        refreshStrip();
        selectExport(id);
        reveal.run();
        panel.startWhenShown(key);
    }

    /** Für Tests: die offenen Export-Reiter. */
    List<String> exportIds() {
        return new ArrayList<>(exports.keySet());
    }

    /** Für Tests: die Fläche des gewählten Export-Reiters, sonst {@code null}. */
    ExportPanel selectedExport() {
        ExportTab tab = selectedExport == null ? null : exports.get(selectedExport);
        return tab == null ? null : tab.panel();
    }

    private void selectExport(String id) {
        ExportTab tab = exports.get(id);
        if (tab == null) {
            return;
        }
        selected = null;
        selectedExport = id;
        actions.setVisible(false);
        tab.pill().setSelected(true);
        artifactSlot.removeAll();
        artifactSlot.add(tab.panel(), BorderLayout.CENTER);
        artifactSlot.revalidate();
        artifactSlot.repaint();
        cards.show(center, ARTIFACT);
        selectionChanged.run();
    }

    /** Schließt einen Export-Reiter; sein Auftrag läuft weiter, keine Datei wird gelöscht. */
    private void closeExport(String id) {
        ExportTab tab = exports.remove(id);
        if (tab == null) {
            return;
        }
        tab.panel().dispose();
        group.remove(tab.pill());
        pills.remove(tab.pill());
        refreshStrip();
        if (id.equals(selectedExport)) {
            showPage();
        }
    }

    /** Die Seite kommt zurück (der Benutzer hat links etwas gewählt); die Reiter bleiben stehen. */
    void showPage() {
        selected = null;
        selectedExport = null;
        actions.setVisible(false);
        group.clearSelection();
        artifactSlot.removeAll();
        cards.show(center, PAGE);
        selectionChanged.run();
    }

    /** Für Tests: die offenen Reiter. */
    List<String> openIds() {
        return new ArrayList<>(tabs.keySet());
    }

    /** Für Tests und den Export: die Ansicht des gewählten Artefakts, sonst {@code null}. */
    ArtifactView selectedView() {
        Tab tab = selected == null ? null : tabs.get(selected);
        return tab == null ? null : tab.view();
    }

    private Tab add(String id) {
        ChatArtifact artifact = store.read(folder, chatId, id);
        if (artifact == null) {
            return null;
        }
        ClosableTab pill = new ClosableTab(artifact.kind() == ChatArtifact.Kind.GRAPHIC ? ComposerIcons.picture()
                : ComposerIcons.document(), artifact.title(), artifact.title(), CLOSE, () -> close(artifact.id()));
        pill.addActionListener(event -> select(artifact.id()));
        Tab tab = new Tab(pill, new ArtifactView(store, folder, chatId, artifact.id(), status));
        tabs.put(artifact.id(), tab);
        group.add(pill);
        pills.add(pill);
        refreshStrip();
        return tab;
    }

    private void select(String id) {
        Tab tab = tabs.get(id);
        if (tab == null) {
            return;
        }
        selected = id;
        selectedExport = null;
        tab.pill().setSelected(true);
        artifactSlot.removeAll();
        artifactSlot.add(tab.view(), BorderLayout.CENTER);
        artifactSlot.revalidate();
        artifactSlot.repaint();
        cards.show(center, ARTIFACT);
        actions.setVisible(true);
        refreshUndo();
        selectionChanged.run();
    }

    private void close(String id) {
        Tab tab = tabs.remove(id);
        if (tab == null) {
            return;
        }
        tab.view().dispose();
        group.remove(tab.pill());
        pills.remove(tab.pill());
        remember();
        refreshStrip();
        if (id.equals(selected)) {
            showPage();
        }
    }

    private void remember() {
        if (folder != null && chatId != null) {
            store.rememberTabs(folder, chatId, new ArrayList<>(tabs.keySet()));
        }
    }

    private void refreshStrip() {
        strip.setVisible(!tabs.isEmpty() || !exports.isEmpty());
        strip.revalidate();
        strip.repaint();
    }

    /** Holt den Stand vor der letzten Änderung des gewählten Artefakts zurück (als neue Revision). */
    void undo() {
        String id = selected;
        if (id == null) {
            return;
        }
        ChatArtifactStore.WriteResult result = store.undo(folder, chatId, id, ChatArtifactStore.ANY,
                ChatArtifact.Origin.USER);
        status.accept(result.success() ? "Rückgängig: " + result.artifact().title() + ", Stand "
                + result.revision() : result.reason());
        refreshUndo();
    }

    /** Löscht das gewählte Artefakt nach „wirklich löschen?“ (ADR 0047); der Reiter schließt sich. */
    void deleteSelected() {
        ChatArtifact artifact = selected == null ? null : store.read(folder, chatId, selected);
        if (artifact == null || !confirm.test(DELETE_TITLE, deleteQuestion(artifact.title()))) {
            return;
        }
        ChatArtifactStore.WriteResult result = store.delete(folder, chatId, artifact.id());
        if (result.success()) {
            close(artifact.id());
        }
        status.accept(result.success() ? "Gelöscht: " + artifact.title() + " (bis zum nächsten Start links unter "
                + "„Artefakte“ wiederherstellbar)" : result.reason());
    }

    private void refreshUndo() {
        ChatArtifact artifact = selected == null ? null : store.read(folder, chatId, selected);
        undo.setEnabled(artifact != null && artifact.undoable());
    }

    /** Exportiert das gewählte Artefakt; {@code format} {@code null} heißt SVG. */
    private void exportSelected(com.aresstack.spaceharvester.app.chat.ChatExport.Format format) {
        ChatArtifact artifact = selected == null ? null : store.read(folder, chatId, selected);
        if (artifact != null) {
            export(artifact, format);
        }
    }

    private static String formatIcon(com.aresstack.spaceharvester.app.chat.ChatExport.Format format) {
        return switch (format) {
            case MARKDOWN -> "markdown";
            case WORD -> "docx";
            case PDF -> "pdf";
        };
    }

    /** Ein Knopf der unteren Leiste wie die der Export-Fläche (Formatsymbol, Tooltip). */
    private static com.aresstack.spaceharvester.app.ui.IconButton formatButton(String icon, String label,
                                                                               Runnable action) {
        com.aresstack.spaceharvester.app.ui.IconButton button = new com.aresstack.spaceharvester.app.ui.IconButton(
                com.aresstack.spaceharvester.app.ui.FormatIcon.of(icon), EXPORT_AS.formatted(label));
        button.addActionListener(event -> action.run());
        return button;
    }

    /** Exportiert im Hintergrund in den Ausgabeordner; {@code format} {@code null} heißt SVG. */
    void export(ChatArtifact artifact, com.aresstack.spaceharvester.app.chat.ChatExport.Format format) {
        export(artifact, format, id -> store.read(folder, chatId, id));
    }

    /** Wie {@link #export}; {@code resolve} findet eingebettete Grafiken (etwa die des Projekts ohne Chat). */
    void export(ChatArtifact artifact, com.aresstack.spaceharvester.app.chat.ChatExport.Format format,
                java.util.function.Function<String, ChatArtifact> resolve) {
        Path directory = output.get();
        if (directory == null) {
            status.accept("Export nicht möglich: kein Ausgabeordner eingestellt");
            return;
        }
        status.accept("Export läuft: " + artifact.title());
        com.aresstack.spaceharvester.app.ui.Background.run(() -> format == null
                        ? com.aresstack.spaceharvester.app.chat.artifact.ArtifactExport.writeSvg(artifact, directory)
                        : com.aresstack.spaceharvester.app.chat.artifact.ArtifactExport.write(artifact, format,
                        directory, resolve),
                file -> status.accept("Export gespeichert: " + file.getFileName()),
                error -> status.accept("Export fehlgeschlagen: " + error.getClass().getSimpleName()));
    }

    /** Ein umbenanntes Artefakt bekommt seinen neuen Titel auch am Reiter. */
    private void retitle(ChatArtifactStore.Event event) {
        Runnable update = () -> {
            if (chatId == null || !Objects.equals(store.owner(folder, chatId), event.owner())) {
                return;
            }
            if (event.deleted()) {
                // In einem anderen Chat desselben Projekts gelöscht: der Reiter geht auch hier zu.
                close(event.artifact().id());
                return;
            }
            if (event.artifact().id().equals(selected)) {
                undo.setEnabled(event.artifact().undoable());
            }
            Tab tab = tabs.get(event.artifact().id());
            if (tab != null && !tab.pill().getText().equals(event.artifact().title())) {
                tab.pill().setText(event.artifact().title());
                tab.pill().setToolTipText(event.artifact().title());
                refreshStrip();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            update.run();
        } else {
            SwingUtilities.invokeLater(update);
        }
    }
}
