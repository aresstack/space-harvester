package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifact;
import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifactStore;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Die globalen Artefakte des aktiven Projekts links unter „Artefakte“ (ADR 0047 §5, Angelo 27.11.2026): Dokumente,
 * Grafiken und Bücher aus dem Speicher des Projekts, ohne geladenen Chat; lokale Artefakte einer Seite (Zusammenfassung,
 * Podcast) stehen hier nicht. Neueste Änderung zuerst, das Filterfeld der Leiste filtert nach Titel. Ein Klick öffnet
 * das Artefakt in der Mitte. Neu gelesen wird nur auf Meldung (Wechsel des Projekts, Änderung am Speicher, Neustart der
 * App), nie per Zeitgeber. Nur auf dem EDT.
 */
final class ArtifactList {

    static final String EMPTY = "Noch keine Artefakte im Projekt: Dokumente und Grafiken entstehen im Chat";
    static final String NOTHING_FOUND = "Kein Artefakt mit diesem Titel";
    static final String OPEN = "Öffnen";
    static final String RENAME = "Umbenennen …";
    static final String RENAME_TITLE = "Artefakt umbenennen";
    static final String DELETE = "Löschen …";

    private final AppContext context;
    private final DefaultListModel<ChatArtifact> model = new DefaultListModel<>();
    private final JList<ChatArtifact> list = new JList<>(model);
    private final javax.swing.JScrollPane scroll;
    private final Consumer<ChatArtifactStore.Event> changed = event -> SwingUtilities.invokeLater(this::reload);
    private ChatArtifactStore store;
    private Consumer<String> open = id -> { };
    private Consumer<String> note = text -> { };
    private String filter = "";
    private List<ChatArtifact> all = List.of();
    /** Die „wirklich“-Rückfrage (Ja/Nein) vor dem Löschen; Tests antworten selbst. */
    java.util.function.BiPredicate<String, String> confirm = (title, question) ->
            Dialogs.confirm(list, title, question);
    /** Fragt den neuen Titel; {@code null} heißt abgebrochen. Tests antworten selbst. */
    java.util.function.UnaryOperator<String> askTitle = current ->
            Dialogs.askText(list, RENAME_TITLE, "Neuer Titel:", current);

    ArtifactList(AppContext context) {
        this.context = context;
        list.setOpaque(false);
        list.setFont(Comic.regular(13f));
        list.setSelectionBackground(Comic.YELLOW);
        list.setSelectionForeground(Comic.INK);
        list.setCellRenderer(new Row());
        list.getAccessibleContext().setAccessibleName("Artefakte");
        javax.swing.ToolTipManager.sharedInstance().registerComponent(list);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) {
                    openSelected();
                }
            }

            @Override
            public void mousePressed(MouseEvent event) {
                popup(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                popup(event);
            }
        });
        list.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DELETE, 0), "delete");
        list.getActionMap().put("delete", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                delete(list.getSelectedValue());
            }
        });
        list.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), "open");
        list.getActionMap().put("open", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                openSelected();
            }
        });
        scroll = Comic.scroll(list);
        context.projects().addListener(() -> SwingUtilities.invokeLater(this::reload));
        context.onChange(ignored -> reload());
    }

    JComponent component() {
        return scroll;
    }

    /**
     * Der Speicher der Artefakte (derselbe wie der des Chats, damit jede Änderung hier ankommt) und was ein Klick
     * öffnet.
     */
    void connect(ChatArtifactStore store, Consumer<String> open) {
        if (this.store != null) {
            this.store.removeListener(changed);
        }
        this.store = store;
        this.open = open == null ? id -> { } : open;
        if (store != null) {
            store.addListener(changed);
        }
        reload();
    }

    /** Wohin der Hinweis unter der Liste geht (die Fußleiste der Seitenleiste). */
    void onNote(Consumer<String> note) {
        this.note = note == null ? text -> { } : note;
    }

    /** Filtert nach Titel, ohne Groß- und Kleinschreibung; leer zeigt alle. */
    void filter(String text) {
        filter = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        show();
    }

    /** Liest die Artefakte des aktiven Projekts neu. */
    void reload() {
        if (store == null) {
            all = List.of();
        } else {
            all = store.listInProject(projectId()).stream()
                    .sorted(Comparator.comparingLong(ArtifactList::changedAt).reversed()
                            .thenComparing(ChatArtifact::id))
                    .toList();
        }
        show();
    }

    /** Für Tests: die gezeigten Kennungen in ihrer Reihenfolge. */
    List<String> shownIds() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < model.size(); i++) {
            ids.add(model.get(i).id());
        }
        return ids;
    }

    /** Für Tests: die Liste selbst. */
    JList<ChatArtifact> list() {
        return list;
    }

    private void show() {
        String keep = list.getSelectedValue() == null ? null : list.getSelectedValue().id();
        model.clear();
        for (ChatArtifact artifact : all) {
            if (filter.isEmpty() || artifact.title().toLowerCase(Locale.ROOT).contains(filter)) {
                model.addElement(artifact);
                if (artifact.id().equals(keep)) {
                    list.setSelectedIndex(model.size() - 1);
                }
            }
        }
        note.accept(all.isEmpty() ? EMPTY : model.isEmpty() ? NOTHING_FOUND : " ");
    }

    /** Das Rechtsklick-Menü: nur, was für die Art gilt (Entwurf UX 27.11.2026). */
    javax.swing.JPopupMenu menu(ChatArtifact artifact) {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem openItem = new javax.swing.JMenuItem(OPEN);
        openItem.addActionListener(event -> open.accept(artifact.id()));
        javax.swing.JMenuItem renameItem = new javax.swing.JMenuItem(RENAME);
        renameItem.addActionListener(event -> rename(artifact));
        javax.swing.JMenuItem deleteItem = new javax.swing.JMenuItem(DELETE);
        deleteItem.addActionListener(event -> delete(artifact));
        menu.add(openItem);
        menu.add(renameItem);
        menu.addSeparator();
        menu.add(deleteItem);
        return menu;
    }

    private void popup(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        int row = list.locationToIndex(event.getPoint());
        if (row < 0 || !list.getCellBounds(row, row).contains(event.getPoint())) {
            return;
        }
        list.setSelectedIndex(row);
        menu(model.get(row)).show(list, event.getX(), event.getY());
    }

    /** Benennt um; der Titel ist keine Revision. */
    void rename(ChatArtifact artifact) {
        if (artifact == null || store == null) {
            return;
        }
        String title = askTitle.apply(artifact.title());
        if (title != null && !title.isBlank() && !title.strip().equals(artifact.title())) {
            store.renameInProject(projectId(), artifact.id(), title.strip());
        }
    }

    /** Löscht nach Rückfrage; bis zum nächsten Start im Papierkorb. */
    void delete(ChatArtifact artifact) {
        if (artifact == null || store == null
                || !confirm.test(Texts.ARTIFACT_DELETE_TITLE, Texts.artifactDeleteQuestion(artifact.title()))) {
            return;
        }
        store.deleteInProject(projectId(), artifact.id());
    }

    private String projectId() {
        return context.projects().active() == null ? null : context.projects().active().id();
    }

    private void openSelected() {
        ChatArtifact artifact = list.getSelectedValue();
        if (artifact != null) {
            open.accept(artifact.id());
        }
    }

    /** Zeitpunkt des neuesten Stands. */
    private static long changedAt(ChatArtifact artifact) {
        return artifact.revisions().isEmpty() ? artifact.createdAt() : artifact.current().at();
    }

    /** Wann zuletzt geändert: heute als Uhrzeit, sonst als Datum. */
    static String when(long millis, java.time.ZoneId zone, java.time.LocalDate today) {
        java.time.ZonedDateTime at = Instant.ofEpochMilli(millis).atZone(zone);
        return at.toLocalDate().equals(today) ? TODAY.format(at) : DAY.format(at);
    }

    private static final java.time.format.DateTimeFormatter TODAY =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY);
    private static final java.time.format.DateTimeFormatter DAY =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMANY);

    /**
     * Eine Zeile, einzeilig: Symbol der Art, Titel (gekürzt mit „…“, voll im Tooltip), Zeit rechts gedämpft. Reicht die
     * Breite nicht, weicht zuerst die Zeit, dann wird der Titel gekürzt.
     */
    private static final class Row extends javax.swing.JPanel implements javax.swing.ListCellRenderer<ChatArtifact> {
        private final javax.swing.JLabel title = new javax.swing.JLabel();
        private final javax.swing.JLabel time = new javax.swing.JLabel();

        Row() {
            super(new java.awt.BorderLayout(6, 0));
            setBorder(BorderFactory.createEmptyBorder(3, 2, 3, 6));
            title.setIconTextGap(6);
            time.setForeground(Comic.MUTED);
            add(title, java.awt.BorderLayout.CENTER);
            add(time, java.awt.BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends ChatArtifact> owner, ChatArtifact artifact,
                                                      int index, boolean selected, boolean focus) {
            title.setFont(owner.getFont());
            time.setFont(owner.getFont().deriveFont(owner.getFont().getSize2D() - 1f));
            title.setForeground(selected ? owner.getSelectionForeground() : Comic.INK);
            title.setText(artifact.title());
            title.setIcon(icon(artifact));
            time.setText(when(changedAt(artifact), java.time.ZoneId.systemDefault(), java.time.LocalDate.now()));
            java.awt.Insets in = getInsets();
            int room = owner.getWidth() - in.left - in.right;
            time.setVisible(title.getPreferredSize().width + 6 + time.getPreferredSize().width <= room);
            setToolTipText(artifact.title());
            setOpaque(selected);
            setBackground(owner.getSelectionBackground());
            return this;
        }
    }

    private static Icon icon(ChatArtifact artifact) {
        if (artifact.id().startsWith(ChatArtifactStore.BOOK)) {
            return ComposerIcons.openBook();
        }
        return artifact.kind() == ChatArtifact.Kind.GRAPHIC ? ComposerIcons.picture() : ComposerIcons.document();
    }
}
