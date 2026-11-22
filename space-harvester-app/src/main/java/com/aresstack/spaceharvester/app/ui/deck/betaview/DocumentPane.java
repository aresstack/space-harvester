package com.aresstack.spaceharvester.app.ui.deck.betaview;

import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.app.ui.controls.BusyOverlay;
import com.aresstack.spaceharvester.app.ui.controls.WrapLayout;
import com.aresstack.spaceharvester.deck.betaview.DocumentTab;
import com.aresstack.spaceharvester.deck.betaview.OpenedDocument;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;

/**
 * Rechte Hälfte einer BetaView-Sitzung: die offenen Dokumente als Pillen, rechts daneben die Werkzeuge als
 * Symbolknöpfe (neu laden, Seiten blättern, herunterladen, mit Optionen herunterladen, merken, Dokumentinfo,
 * schließen); darunter der Text der Seite in Festbreitenschrift, wie der Host ihn liefert. Funktion wie
 * {@code DocumentPreviewPanel}/{@code BetaViewDocumentTab} in MainframeMate. Was der Server noch offen hat (etwa aus der
 * letzten Sitzung), steht als Pille da und wird erst beim Anklicken geladen.
 */
final class DocumentPane extends JPanel {

    /** Was die Dokumentseite auslöst; die Sitzung führt es aus. */
    interface Actions {
        void open(DocumentTab document);

        void page(DocumentTab document, int page);

        void download(DocumentTab document);

        void downloadWithOptions(DocumentTab document);

        void bookmark(DocumentTab document);

        void info(DocumentTab document);

        void close(DocumentTab document);

        void closeAll();
    }

    /** Was zu einem Dokument bekannt ist. */
    static final class Doc {
        DocumentTab tab;
        OpenedDocument opened;
        /** Die Seite, deren Text {@link #text} ist (0 = noch keine geladen). */
        int page = 1;
        /** Die zuletzt angeforderte Seite; erst wenn ihr Text da ist, wird sie zu {@link #page}. */
        int requested = 1;
        String text = "";

        Doc(DocumentTab tab) {
            this.tab = tab;
        }

        int pages() {
            return opened == null ? 1 : opened.pageCount();
        }
    }

    private final Actions actions;
    private final Map<String, Doc> docs = new LinkedHashMap<>();
    private final Map<String, ComposerToggleButton> pills = new LinkedHashMap<>();
    private final ButtonGroup group = new ButtonGroup();
    private final JPanel pillRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 4));
    final JTextArea text = Comic.code();
    private final BusyOverlay busy;
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    final IconButton refresh = tool(BetaViewIcons.refresh(), "Dokument neu laden");
    final IconButton first = tool(BetaViewIcons.first(), "Erste Seite");
    final IconButton previous = tool(ComposerIcons.chevronLeft(), "Vorherige Seite");
    final PlaceholderField pageField = new PlaceholderField(3, "1");
    final JLabel pageCount = Comic.muted("/ 1");
    final IconButton next = tool(ComposerIcons.chevronRight(), "Nächste Seite");
    final IconButton last = tool(BetaViewIcons.last(), "Letzte Seite");
    final IconButton download = tool(ComposerIcons.download(), "Herunterladen (alle Seiten, Original)");
    final IconButton downloadOptions = tool(BetaViewIcons.downloadOptions(), "Herunterladen mit Optionen …");
    final IconButton bookmark = tool(BetaViewIcons.star(), "Dokument merken");
    final IconButton info = tool(ComposerIcons.info(), "Dokumentinformationen");
    final IconButton close = tool(ComposerIcons.close(), "Dokument schließen (auch auf dem Server)");
    private Doc current;

    DocumentPane(Actions actions) {
        super(new BorderLayout());
        this.actions = actions;
        setOpaque(false);
        pillRow.setOpaque(false);
        pageField.setHorizontalAlignment(SwingConstants.RIGHT);
        pageField.setFont(Comic.regular(13f));
        pageField.addActionListener(e -> {
            try {
                go(Integer.parseInt(pageField.getText().trim()));
            } catch (NumberFormatException ex) {
                pageField.setText(current == null ? "" : String.valueOf(current.page));
            }
        });
        refresh.addActionListener(e -> withCurrent(doc -> actions.open(doc.tab)));
        first.addActionListener(e -> go(1));
        previous.addActionListener(e -> withCurrent(doc -> go(doc.requested - 1)));
        next.addActionListener(e -> withCurrent(doc -> go(doc.requested + 1)));
        last.addActionListener(e -> withCurrent(doc -> go(doc.pages())));
        download.addActionListener(e -> withCurrent(doc -> actions.download(doc.tab)));
        downloadOptions.addActionListener(e -> withCurrent(doc -> actions.downloadWithOptions(doc.tab)));
        bookmark.addActionListener(e -> withCurrent(doc -> actions.bookmark(doc.tab)));
        info.addActionListener(e -> withCurrent(doc -> actions.info(doc.tab)));
        close.addActionListener(e -> withCurrent(doc -> actions.close(doc.tab)));

        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        tools.setOpaque(false);
        for (JComponent part : new JComponent[] {refresh, first, previous, pageField, pageCount, next, last,
                download, downloadOptions, bookmark, info, close}) {
            tools.add(part);
        }
        // Zwei Zeilen: oben die Dokumente (brechen bei schmaler Breite um), darunter die Werkzeuge zum gewählten.
        // In einer gemeinsamen Zeile schnitten die Werkzeuge bei 960 px den Titel ab.
        JPanel head = new JPanel(new BorderLayout(0, 4));
        head.setOpaque(false);
        head.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        head.add(pillRow, BorderLayout.CENTER);
        head.add(tools, BorderLayout.SOUTH);

        Comic.noAutoScroll(text);
        busy = new BusyOverlay(Comic.scroll(text));
        JLabel empty = new JLabel("Kein Dokument offen – einen Treffer oder ein gemerktes Dokument anklicken",
                SwingConstants.CENTER);
        empty.setFont(Comic.regular(13f));
        empty.setForeground(Comic.MUTED);
        center.setOpaque(false);
        center.add(empty, "empty");
        center.add(busy, "doc");
        add(head, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        showCurrent();
    }

    private static IconButton tool(javax.swing.Icon icon, String tooltip) {
        return new IconButton(icon, tooltip).compact();
    }

    private void withCurrent(java.util.function.Consumer<Doc> action) {
        if (current != null) {
            action.accept(current);
        }
    }

    private void go(int page) {
        if (current == null || current.opened == null) {
            return;
        }
        int target = Math.max(1, Math.min(current.pages(), page));
        if (target == current.page && !current.text.isEmpty()) {
            current.requested = target;
            pageField.setText(String.valueOf(target));
            return;
        }
        current.requested = target;
        pageField.setText(String.valueOf(target));
        actions.page(current.tab, target);
    }

    /** Legt Pillen für Reiter des Servers an, ohne sie zu laden. */
    void known(List<DocumentTab> tabs) {
        for (DocumentTab tab : tabs) {
            doc(tab);
        }
        if (current == null && !docs.isEmpty()) {
            select(docs.values().iterator().next(), false);
        }
        refreshPills();
    }

    /** Ein Dokument ist (erneut) geöffnet: Reiter abgleichen, es zeigen und Seite 1 holen. */
    void opened(OpenedDocument opened) {
        known(opened.tabs());
        Doc doc = doc(opened.document());
        doc.opened = opened;
        doc.page = 1;
        doc.requested = 1;
        doc.text = "";
        select(doc, false);
        actions.page(doc.tab, 1);
    }

    /** Der Text einer Seite ist da. */
    void page(DocumentTab tab, int page, String content) {
        Doc doc = docs.get(tab.key());
        if (doc == null) {
            return;
        }
        if (doc.requested == page) {
            doc.page = page;
            doc.text = content;
        }
        if (doc == current) {
            showCurrent();
        }
    }

    /** Eine Seite kam nicht: angezeigt bleibt die zuletzt geladene, ein neuer Versuch lädt erneut. */
    void pageFailed(DocumentTab tab, int page) {
        Doc doc = docs.get(tab.key());
        if (doc == null || doc.requested != page) {
            return;
        }
        doc.requested = doc.page;
        if (doc == current) {
            showCurrent();
        }
    }

    /** Die Seite, die für {@code tab} gerade zu sehen ist (für „Aktuelle Seite“ beim Herunterladen). */
    int shownPage(DocumentTab tab) {
        Doc doc = docs.get(tab.key());
        return doc == null ? 1 : Math.max(1, doc.page);
    }

    void removed(DocumentTab tab) {
        Doc doc = docs.remove(tab.key());
        ComposerToggleButton pill = pills.remove(tab.key());
        if (pill != null) {
            group.remove(pill);
            pillRow.remove(pill);
        }
        if (doc == current) {
            current = null;
            if (!docs.isEmpty()) {
                select(docs.values().iterator().next(), true);
            }
        }
        refreshPills();
        showCurrent();
    }

    /** Alle Dokumente sind geschlossen: alles leeren, ohne ein anderes auszuwählen oder zu laden. */
    void clear() {
        for (ComposerToggleButton pill : pills.values()) {
            group.remove(pill);
            pillRow.remove(pill);
        }
        pills.clear();
        docs.clear();
        current = null;
        refreshPills();
        showCurrent();
    }

    void busy(boolean on, String message) {
        busy.setText(message);
        busy.setBusy(on);
    }

    Optional<DocumentTab> current() {
        return current == null ? Optional.empty() : Optional.of(current.tab);
    }

    List<DocumentTab> documents() {
        return docs.values().stream().map(doc -> doc.tab).toList();
    }

    private Doc doc(DocumentTab tab) {
        Doc doc = docs.get(tab.key());
        if (doc == null) {
            // Dasselbe Dokument kann als Reiter (mit Link-Kennung) und über seine Öffnen-Aktion bekannt sein.
            doc = docs.values().stream().filter(known -> !tab.docId().isEmpty()
                    && tab.docId().equals(known.tab.docId())).findFirst().orElse(null);
            if (doc != null) {
                // Unter dem Schlüssel des Servers weiterführen, damit Seiten ohne erneutes Öffnen kommen.
                String oldKey = doc.tab.key();
                docs.remove(oldKey);
                ComposerToggleButton pill = pills.remove(oldKey);
                doc.tab = tab.active(false);
                docs.put(tab.key(), doc);
                if (pill != null) {
                    pills.put(tab.key(), pill);
                }
                return doc;
            }
        }
        if (doc == null) {
            doc = new Doc(tab);
            docs.put(tab.key(), doc);
            ComposerToggleButton pill = new ComposerToggleButton(null, shortLabel(tab), tooltip(tab));
            pill.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
            pill.getAccessibleContext().setAccessibleName(tab.label());
            Doc target = doc;
            pill.addActionListener(e -> select(target, true));
            pill.setComponentPopupMenu(menu(target));
            group.add(pill);
            pills.put(tab.key(), pill);
            pillRow.add(pill);
        } else if (!doc.tab.title().equals(tab.title()) && !tab.title().isEmpty()) {
            doc.tab = tab.active(false);
        }
        return doc;
    }

    private JPopupMenu menu(Doc doc) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem closeOne = new JMenuItem("Schließen");
        closeOne.addActionListener(e -> actions.close(doc.tab));
        JMenuItem closeAll = new JMenuItem("Alle schließen");
        closeAll.addActionListener(e -> actions.closeAll());
        menu.add(closeOne);
        menu.add(closeAll);
        return menu;
    }

    /** Zeigt ein Dokument; ist es noch nicht geladen und {@code load}, öffnet es die Sitzung. */
    private void select(Doc doc, boolean load) {
        current = doc;
        ComposerToggleButton pill = pills.get(doc.tab.key());
        if (pill != null) {
            pill.setSelected(true);
        }
        showCurrent();
        if (load && doc.opened == null) {
            actions.open(doc.tab);
        }
    }

    private void refreshPills() {
        pillRow.setVisible(!pills.isEmpty());
        pillRow.revalidate();
        pillRow.repaint();
    }

    private void showCurrent() {
        boolean has = current != null;
        boolean loaded = has && current.opened != null;
        cards.show(center, has ? "doc" : "empty");
        if (has) {
            if (!current.text.equals(text.getText())) {
                text.setText(current.text);
                text.setCaretPosition(0);
            }
            pageField.setText(String.valueOf(current.requested));
            pageCount.setText("/ " + current.pages());
        } else {
            text.setText("");
            pageField.setText("");
            pageCount.setText("/ –");
        }
        refresh.setEnabled(has);
        first.setEnabled(loaded && current.page > 1);
        previous.setEnabled(loaded && current.page > 1);
        next.setEnabled(loaded && current.page < current.pages());
        last.setEnabled(loaded && current.page < current.pages());
        pageField.setEnabled(loaded);
        download.setEnabled(loaded);
        downloadOptions.setEnabled(loaded);
        bookmark.setEnabled(loaded);
        info.setEnabled(loaded);
        close.setEnabled(has);
    }

    /** Höchstlänge des Titels in der Pille; der volle Titel steht im Tooltip. */
    static final int MAX_PILL = 28;

    static String shortLabel(DocumentTab tab) {
        String label = tab.label();
        return label.length() > MAX_PILL ? label.substring(0, MAX_PILL - 1).strip() + "…" : label;
    }

    private static String tooltip(DocumentTab tab) {
        return tab.timestamp().isEmpty() ? tab.label() : tab.timestamp() + " – " + tab.label();
    }
}
