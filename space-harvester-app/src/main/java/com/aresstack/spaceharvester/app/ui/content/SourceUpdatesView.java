package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Problems;
import com.aresstack.spaceharvester.app.ui.controls.ScrollColumn;
import com.aresstack.spaceharvester.app.ui.controls.TabHeader;
import com.aresstack.spaceharvester.app.ui.controls.WrappedText;
import com.aresstack.spaceharvester.application.changelog.ChangeKind;
import com.aresstack.spaceharvester.application.changelog.ChangeLogListener;
import com.aresstack.spaceharvester.application.changelog.ChangeReport;
import com.aresstack.spaceharvester.application.changelog.ChangeUpdate;
import com.aresstack.spaceharvester.application.changelog.ReportedPage;
import com.aresstack.spaceharvester.application.observe.Subscription;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Die Quellenseite (ADR 0030, Stufe 4): Wird im Explorer eine Wissensquelle gewählt, zeigt der Reiter „Leseansicht“ ihre
 * Updates. Links die Updates (das offene „seit dem letzten Update“ zuerst, dann je manueller Synchronisierung), rechts
 * die Änderungen des gewählten Updates nach Art; ein Klick auf eine Seite öffnet sie. Neue Zeilen und Marken meldet der
 * Core (ADR 0027), die Ansicht fragt nie in Abständen nach.
 */
final class SourceUpdatesView extends JPanel {

    static final String NO_UPDATES = "Für diese Quelle ist noch keine Änderung festgehalten. Ein Update entsteht mit "
            + "jeder manuellen Synchronisierung; was im Hintergrund dazukommt, zählt zum nächsten.";
    static final String NO_PAGES = "In diesem Update gibt es keine Änderung, die du sehen darfst.";
    static final String NOT_RUNNING = "Die Updates erscheinen, sobald der Dienst läuft.";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.GERMAN);

    private final Supplier<Actions> actions;
    private final Runner runner;
    private final TabHeader head = new TabHeader("Updates");
    private final JPanel list = new JPanel();
    private final ScrollColumn report = new ScrollColumn(new BorderLayout());
    private final JPanel reportBody = new JPanel();
    private Consumer<ReportedPage> onOpen = page -> { };
    private ZoneId zone = ZoneId.systemDefault();

    private String sourceId;
    private Subscription subscription = Subscription.NONE;
    private List<ChangeUpdate> updates = List.of();
    private String selected;
    private ChangeReport shown;
    private int generation;

    SourceUpdatesView(Supplier<Workspace> workspace) {
        this(() -> Actions.of(workspace.get()), new Runner() {
            @Override
            public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
                Background.run(work, onSuccess, onFailure);
            }
        });
    }

    SourceUpdatesView(Supplier<Actions> actions, Runner runner) {
        super(new BorderLayout(12, 8));
        this.actions = Objects.requireNonNull(actions, "actions");
        this.runner = Objects.requireNonNull(runner, "runner");
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(6, 2, 2, 2));
        head.note("Was sich in der Quelle geändert hat, je manueller Synchronisierung");
        head.copy(this::copyText);

        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(false);
        JPanel listTop = new JPanel(new BorderLayout());
        listTop.setOpaque(false);
        listTop.add(list, BorderLayout.NORTH);
        JComponent listScroll = Comic.scroll(listTop);
        listScroll.setPreferredSize(new Dimension(270, 200));

        reportBody.setLayout(new BoxLayout(reportBody, BoxLayout.Y_AXIS));
        reportBody.setOpaque(false);
        report.setOpaque(false);
        report.add(reportBody, BorderLayout.NORTH);

        add(head, BorderLayout.NORTH);
        add(listScroll, BorderLayout.WEST);
        add(Comic.scroll(report), BorderLayout.CENTER);
        source(null, null);
    }

    /** {@code listener} öffnet eine Seite aus dem Bericht. */
    void onOpen(Consumer<ReportedPage> listener) {
        onOpen = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Die gewählte Quelle, {@code null} für keine: meldet sich beim Änderungsprotokoll an und lädt die Updates im
     * Hintergrund. Eine vorige Anmeldung endet.
     */
    void source(String id, String title) {
        subscription.close();
        subscription = Subscription.NONE;
        generation++;
        sourceId = id;
        updates = List.of();
        selected = null;
        shown = null;
        head.title(title == null || title.isBlank() ? "Updates" : "Updates · " + title);
        list.removeAll();
        if (id == null) {
            message(" ");
            return;
        }
        Actions current = actions.get();
        if (current == null) {
            message(NOT_RUNNING);
            return;
        }
        try {
            subscription = current.subscribe(id, changed -> SwingUtilities.invokeLater(() -> {
                if (Objects.equals(changed, sourceId)) {
                    reload();
                }
            }));
        } catch (RuntimeException e) {
            subscription = Subscription.NONE;
        }
        reload();
    }

    /** Liest die Updates neu; die Auswahl bleibt, solange es das Update noch gibt. */
    void reload() {
        Actions current = actions.get();
        String id = sourceId;
        if (current == null || id == null) {
            return;
        }
        int mine = ++generation;
        runner.run(() -> current.updates(id), found -> {
            if (mine != generation) {
                return;
            }
            updates = List.copyOf(found);
            String keep = updates.stream().map(ChangeUpdate::id).filter(u -> u.equals(selected)).findFirst()
                    .orElse(updates.isEmpty() ? null : updates.getFirst().id());
            renderList();
            if (keep == null) {
                selected = null;
                shown = null;
                message(NO_UPDATES);
            } else {
                select(keep);
            }
        }, failure -> {
            if (mine == generation) {
                message("Updates nicht geladen: " + Problems.describe(failure).title());
            }
        });
    }

    /** Wählt ein Update und lädt seinen Bericht. */
    void select(String updateId) {
        Actions current = actions.get();
        String id = sourceId;
        if (current == null || id == null || updateId == null) {
            return;
        }
        selected = updateId;
        renderList();
        int mine = ++generation;
        runner.run(() -> current.report(id, updateId), found -> {
            if (mine != generation) {
                return;
            }
            shown = found;
            renderReport(found);
        }, failure -> {
            if (mine == generation) {
                message("Änderungen nicht geladen: " + Problems.describe(failure).title());
            }
        });
    }

    private void renderList() {
        list.removeAll();
        for (ChangeUpdate update : updates) {
            list.add(updateRow(update));
            list.add(Box.createVerticalStrut(6));
        }
        list.revalidate();
        list.repaint();
    }

    private JPanel updateRow(ChangeUpdate update) {
        boolean chosen = update.id().equals(selected);
        JLabel when = new JLabel(heading(update));
        when.setFont(Comic.semiBold(13f));
        when.setForeground(Comic.INK);
        WrappedText counts = Comic.wrapped(counts(update), Comic.MUTED, 12f);
        JPanel row = new JPanel(new BorderLayout(0, 2));
        row.setOpaque(false);
        row.setBorder(border(chosen));
        row.add(when, BorderLayout.NORTH);
        row.add(counts, BorderLayout.CENTER);
        row.setAlignmentX(LEFT_ALIGNMENT);
        clickable(() -> select(update.id()), "Änderungen dieses Updates zeigen", row, when, counts);
        return row;
    }

    private void renderReport(ChangeReport found) {
        reportBody.removeAll();
        ChangeUpdate update = found.update();
        JLabel title = new JLabel(heading(update));
        title.setFont(Comic.semiBold(15f));
        title.setForeground(Comic.INK);
        add(reportBody, title);
        add(reportBody, Comic.wrapped(period(update), Comic.MUTED, 12.5f));
        if (update.mark() != null && update.mark().failedSyncs() > 0) {
            add(reportBody, Comic.wrapped(update.mark().failedSyncs() == 1 ? "Ein Abgleich ist dabei gescheitert."
                    : update.mark().failedSyncs() + " Abgleiche sind dabei gescheitert.", Comic.RED, 12.5f));
        }
        reportBody.add(Box.createVerticalStrut(10));
        if (update.initial()) {
            add(reportBody, Comic.wrapped("Erstbefüllung: " + pages(update.pages()) + " kamen in den Bestand. Eine "
                    + "Seitenliste gibt es dafür nicht.", Comic.TEXT, 13.5f));
            for (Map.Entry<String, Integer> space : sortedSpaces(update)) {
                add(reportBody, Comic.wrapped("Space " + space.getKey() + ": " + pages(space.getValue()),
                        Comic.MUTED, 12.5f));
            }
        } else if (found.pages().isEmpty()) {
            add(reportBody, Comic.wrapped(NO_PAGES, Comic.MUTED, 13.5f));
        } else {
            for (Map.Entry<ChangeKind, List<ReportedPage>> group : found.byKind().entrySet()) {
                JLabel kind = new JLabel(kindLabel(group.getKey()) + " (" + group.getValue().size() + ")");
                kind.setFont(Comic.semiBold(14f));
                kind.setForeground(Comic.INK);
                kind.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
                add(reportBody, kind);
                for (ReportedPage page : group.getValue()) {
                    add(reportBody, pageRow(page));
                    reportBody.add(Box.createVerticalStrut(4));
                }
            }
        }
        String local = localCounts(update);
        if (!local.isEmpty()) {
            reportBody.add(Box.createVerticalStrut(8));
            add(reportBody, Comic.wrapped(local, Comic.MUTED, 12.5f));
        }
        reportBody.revalidate();
        reportBody.repaint();
    }

    private JPanel pageRow(ReportedPage page) {
        WrappedText title = Comic.wrapped(page.title() == null || page.title().isBlank() ? page.pageId()
                : page.title(), Comic.INK, 13.5f);
        title.setFont(Comic.semiBold(13.5f));
        JPanel row = new JPanel(new BorderLayout(0, 2));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        row.add(title, BorderLayout.NORTH);
        String details = details(page);
        WrappedText more = details.isEmpty() ? null : Comic.wrapped(details, Comic.MUTED, 12.5f);
        if (more != null) {
            row.add(more, BorderLayout.CENTER);
        }
        row.setAlignmentX(LEFT_ALIGNMENT);
        if (openable(page)) {
            List<Component> parts = new ArrayList<>(List.of(row, title));
            if (more != null) {
                parts.add(more);
            }
            clickable(() -> onOpen.accept(page), "Seite öffnen", parts.toArray(Component[]::new));
        }
        return row;
    }

    private static boolean openable(ReportedPage page) {
        return page.kind() != ChangeKind.REMOVED && page.kind() != ChangeKind.WITHHELD;
    }

    /** Pfad und was sich an der Seite geändert hat, in einer Zeile. */
    static String details(ReportedPage page) {
        List<String> parts = new ArrayList<>();
        if (!page.path().isEmpty()) {
            parts.add(String.join(" › ", page.path()));
        }
        if (page.kinds().contains(ChangeKind.RENAMED) && page.oldTitle() != null
                && !page.oldTitle().equals(page.title())) {
            parts.add("früher „" + page.oldTitle() + "“");
        }
        if (page.kinds().contains(ChangeKind.MOVED_OUT) && page.newSpaceKey() != null) {
            parts.add("jetzt in Space " + page.newSpaceKey());
        }
        if (page.kinds().contains(ChangeKind.CONTENT_CHANGED) && page.newVersion() != null) {
            parts.add(page.oldVersion() == null ? "Version " + page.newVersion()
                    : "Version " + page.newVersion() + " statt " + page.oldVersion());
        }
        if (page.contentPending()) {
            parts.add("Inhalt noch nicht geladen");
        }
        return String.join(" · ", parts);
    }

    String heading(ChangeUpdate update) {
        if (update.open()) {
            return "Seit dem letzten Update";
        }
        String user = update.mark().user();
        return when(update.to()) + (user == null || user.isBlank() ? "" : " · " + user);
    }

    String counts(ChangeUpdate update) {
        String text;
        if (update.initial()) {
            text = "Erstbefüllung · " + pages(update.pages());
        } else if (update.pages() == 0) {
            text = "keine Änderung";
        } else {
            List<String> parts = new ArrayList<>();
            for (ChangeKind kind : ChangeKind.values()) {
                Integer count = update.kinds().get(kind);
                if (count != null && count > 0 && kind.sourceChange()) {
                    parts.add(count + " " + kindLabel(kind).toLowerCase(Locale.GERMAN));
                }
            }
            text = String.join(" · ", parts);
        }
        return update.open() ? "noch nicht abgeschlossen · " + text : text;
    }

    private String period(ChangeUpdate update) {
        if (update.open()) {
            return update.from() == null ? "Alles, was bisher festgehalten ist"
                    : "Alles nach dem Update vom " + when(update.from());
        }
        return update.from() == null ? "Bis " + when(update.to())
                : "Nach dem Update vom " + when(update.from()) + " bis " + when(update.to());
    }

    private String localCounts(ChangeUpdate update) {
        List<String> parts = new ArrayList<>();
        if (update.loaded() > 0) {
            parts.add(update.loaded() == 1 ? "1 Inhalt erstmals lokal geladen"
                    : update.loaded() + " Inhalte erstmals lokal geladen");
        }
        if (update.reloaded() > 0) {
            parts.add(update.reloaded() == 1 ? "1 geänderter Inhalt nachgeladen"
                    : update.reloaded() + " geänderte Inhalte nachgeladen");
        }
        return parts.isEmpty() ? "" : "Außerdem im Bestand: " + String.join(", ", parts) + ".";
    }

    private static List<Map.Entry<String, Integer>> sortedSpaces(ChangeUpdate update) {
        return update.spaces().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    }

    private String when(Instant at) {
        return at == null ? "–" : WHEN.format(at.atZone(zone));
    }

    private static String pages(int count) {
        return count == 1 ? "1 Seite" : count + " Seiten";
    }

    static String kindLabel(ChangeKind kind) {
        return switch (kind) {
            case NEW -> "Neu";
            case CONTENT_CHANGED -> "Geändert";
            case RENAMED -> "Umbenannt";
            case MOVED -> "Verschoben";
            case LABELS_CHANGED -> "Labels geändert";
            case REMOVED -> "Entfernt";
            case WITHHELD -> "Gesperrt";
            case MOVED_OUT -> "In anderen Space";
            case LOADED -> "Erstmals geladen";
            case RELOADED -> "Nachgeladen";
        };
    }

    private void message(String text) {
        reportBody.removeAll();
        add(reportBody, Comic.wrapped(text, Comic.MUTED, 13.5f));
        reportBody.revalidate();
        reportBody.repaint();
    }

    private static void add(JPanel column, JComponent part) {
        part.setAlignmentX(LEFT_ALIGNMENT);
        column.add(part);
    }

    private static Border border(boolean chosen) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(chosen ? Comic.INK : Comic.LINE, chosen ? 2 : 1, true),
                BorderFactory.createEmptyBorder(chosen ? 5 : 6, chosen ? 9 : 10, chosen ? 5 : 6, chosen ? 9 : 10));
    }

    private static void clickable(Runnable action, String tip, Component... parts) {
        MouseAdapter click = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                action.run();
            }
        };
        // Die Textfelder fangen Mausklicks selbst ab; der Klick gilt auf Zeile und Texten gleich.
        for (Component part : parts) {
            part.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            part.addMouseListener(click);
            if (part instanceof JComponent component) {
                component.setToolTipText(tip);
            }
        }
    }

    /** Der gezeigte Bericht als Text für die Zwischenablage. */
    String copyText() {
        if (shown == null) {
            return "";
        }
        StringBuilder text = new StringBuilder(heading(shown.update())).append('\n').append(period(shown.update()));
        if (shown.update().initial()) {
            text.append('\n').append("Erstbefüllung: ").append(pages(shown.update().pages()));
        }
        for (Map.Entry<ChangeKind, List<ReportedPage>> group : shown.byKind().entrySet()) {
            text.append("\n\n").append(kindLabel(group.getKey()));
            for (ReportedPage page : group.getValue()) {
                text.append("\n- ").append(page.title() == null ? page.pageId() : page.title());
                String details = details(page);
                if (!details.isEmpty()) {
                    text.append(" (").append(details).append(')');
                }
            }
        }
        return text.toString();
    }

    /** Führt Arbeit im Hintergrund aus und meldet auf dem EDT zurück (Tests: synchron). */
    interface Runner {
        <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure);
    }

    /** Das Änderungsprotokoll der App; in Tests ersetzbar. */
    interface Actions {

        List<ChangeUpdate> updates(String sourceId);

        ChangeReport report(String sourceId, String updateId);

        Subscription subscribe(String sourceId, ChangeLogListener listener);

        static Actions of(Workspace workspace) {
            if (workspace == null) {
                return null;
            }
            return new Actions() {
                @Override
                public List<ChangeUpdate> updates(String sourceId) {
                    return workspace.changeUpdates(sourceId);
                }

                @Override
                public ChangeReport report(String sourceId, String updateId) {
                    return workspace.changeReport(sourceId, updateId);
                }

                @Override
                public Subscription subscribe(String sourceId, ChangeLogListener listener) {
                    return workspace.subscribeChangeLog(sourceId, listener);
                }
            };
        }
    }

    // ---- für Tests

    void zone(ZoneId zone) {
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    String sourceId() {
        return sourceId;
    }

    List<String> updateHeadings() {
        return updates.stream().map(this::heading).toList();
    }

    String selectedUpdate() {
        return selected;
    }

    /** Alle Texte des Berichts, in Reihenfolge. */
    List<String> reportTexts() {
        List<String> texts = new ArrayList<>();
        collect(reportBody, texts);
        return texts;
    }

    private static void collect(Component component, List<String> texts) {
        if (component instanceof JLabel label) {
            texts.add(label.getText());
        } else if (component instanceof javax.swing.text.JTextComponent text) {
            texts.add(text.getText());
        } else if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                collect(child, texts);
            }
        }
    }

    /** Klickt die Seite mit diesem Titel im Bericht an; {@code false}, wenn sie nicht anklickbar ist. */
    boolean clickPage(String title) {
        return click(reportBody, title);
    }

    /** Klickt das Update mit dieser Überschrift an. */
    boolean clickUpdate(String heading) {
        return click(list, heading);
    }

    private static boolean click(java.awt.Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JPanel row) {
                for (Component part : row.getComponents()) {
                    String shownText = part instanceof JLabel label ? label.getText()
                            : part instanceof javax.swing.text.JTextComponent area ? area.getText() : null;
                    if (text.equals(shownText)) {
                        MouseEvent event = new MouseEvent(row, MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, 1, 1,
                                false, MouseEvent.BUTTON1);
                        java.awt.event.MouseListener[] listeners = row.getMouseListeners();
                        if (listeners.length == 0) {
                            return false;
                        }
                        for (java.awt.event.MouseListener listener : listeners) {
                            listener.mouseClicked(event);
                        }
                        return true;
                    }
                }
            }
        }
        return false;
    }

    boolean subscribed() {
        return subscription != Subscription.NONE;
    }
}
