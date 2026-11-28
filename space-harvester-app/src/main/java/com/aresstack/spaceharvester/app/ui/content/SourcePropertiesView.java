package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Facts;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.Problems;
import com.aresstack.spaceharvester.app.ui.Texts;
import com.aresstack.spaceharvester.app.ui.controls.CopyButton;
import com.aresstack.spaceharvester.app.ui.controls.HelpButton;
import com.aresstack.spaceharvester.application.changelog.ChangeLogListener;
import com.aresstack.spaceharvester.application.changelog.ChangeUpdate;
import com.aresstack.spaceharvester.application.holdings.HoldingCounts;
import com.aresstack.spaceharvester.application.holdings.HoldingsListener;
import com.aresstack.spaceharvester.application.holdings.SpaceSummary;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.sources.SourceField;
import com.aresstack.spaceharvester.application.sources.SourceType;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reiter „Eigenschaften“ der Quellenseite (Angelo, 17.11.2026): Kennzahlen der Quelle aus dem Bestand (Spaces,
 * Seiten, lokal, veraltet, gesperrt, Stand, Updates), je Space eine Zeile, darunter die Einstellungen der Quelle.
 * Neu gelesen wird nur auf Meldung von Bestand und Änderungsprotokoll (ADR 0027), nie in Abständen. Bearbeitet wird
 * die Quelle unter Quellen; der Stift oben in der Kopfzeile führt dorthin. Für „Wissensquellen“ (alle Quellen, Angelo
 * 17.11.2026) dieselben Kästen als Gesamtsicht: Kennzahlen über alle Quellen, je Space eine Zeile mit Quellentitel
 * davor und statt der Einstellungen der Kasten „Quellen“ mit einer Zeile je Quelle.
 */
final class SourcePropertiesView extends JPanel {

    static final String HELP = "Die Kennzahlen stammen aus dem lokalen Bestand: „Seiten“ zählt alle Seiten, die der "
            + "Bestand von der Quelle kennt, „lokal“ die mit gespeichertem Inhalt, „veraltet“ die, deren Inhalt seit dem "
            + "Laden in der Quelle geändert wurde und noch nicht neu geladen ist, meist weil die Betriebsart der Quelle "
            + "„Nur katalogisieren“ ist (Quellen › „Diese Quelle anpassen …“ › Betriebsart). Ein Space ohne Inventur kennt nur einzelne Seiten; seine Zahlen gelten "
            + "nur für diese.\n\n"
            + "Die Einstellungen stehen in der Konfigurationsdatei. Der Stift öffnet die Quelle unter Quellen, dort lässt "
            + "sich alles ändern.";
    static final String EDIT_TIP = "Quelle bearbeiten";
    static final String NOT_RUNNING = "Die Eigenschaften erscheinen, sobald der Dienst läuft.";

    /** Was die Ansicht vom Core braucht; in Tests ersetzbar. */
    interface Actions {

        List<SpaceSummary> spaces(String sourceId);

        List<ChangeUpdate> updates(String sourceId);

        boolean enabled(String sourceId);

        List<SourceType> types();

        Subscription subscribeHoldings(String sourceId, HoldingsListener listener);

        Subscription subscribeChangeLog(String sourceId, ChangeLogListener listener);

        static Actions of(Workspace workspace) {
            return new Actions() {
                @Override
                public List<SpaceSummary> spaces(String sourceId) {
                    return workspace.holdingSpaces(sourceId);
                }

                @Override
                public List<ChangeUpdate> updates(String sourceId) {
                    return workspace.changeUpdates(sourceId);
                }

                @Override
                public boolean enabled(String sourceId) {
                    return workspace.sourceEnabled(sourceId);
                }

                @Override
                public List<SourceType> types() {
                    return workspace.sourceTypes();
                }

                @Override
                public Subscription subscribeHoldings(String sourceId, HoldingsListener listener) {
                    return workspace.subscribeHoldings(sourceId, null, listener);
                }

                @Override
                public Subscription subscribeChangeLog(String sourceId, ChangeLogListener listener) {
                    return workspace.subscribeChangeLog(sourceId, listener);
                }
            };
        }
    }

    /** Führt Arbeit im Hintergrund aus und meldet auf dem EDT zurück (Tests: synchron). */
    interface Runner {
        <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure);
    }

    private record Loaded(List<SpaceSummary> spaces, List<ChangeUpdate> updates, boolean enabled,
                          List<SourceType> types) {
    }

    private final Supplier<Actions> actions;
    private final Supplier<AppSettings> settings;
    /** Woher Passwort bzw. Token kommen; ohne Sitzung und Tresor nur aus der Datei ({@link #secretState}). */
    private java.util.function.BiFunction<AppSettings, AppSettings.SourceEntry,
            com.aresstack.spaceharvester.app.ui.SecretState> secretState =
            (current, entry) -> com.aresstack.spaceharvester.app.ui.SecretState.of(current, entry, false, false);
    private final Runner runner;
    private final JPanel tools = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 2, 0));
    private final IconButton edit = new IconButton(Glyph.PENCIL, EDIT_TIP);
    private final JPanel column = new JPanel();
    private final Facts figures = new Facts();
    private final Facts spaces = new Facts();
    private final Facts settingsFacts = new Facts();
    private final List<JLabel> headings = new ArrayList<>();
    /** Die drei Kästen (Angelo 17.11.2026): Kennzahlen, Spaces und Einstellungen, je eine Platte mit Farbstreifen. */
    private final JComponent figuresPlate = plate("Kennzahlen", Comic.PETROL, figures);
    private final JComponent spacesPlate = plate("Spaces", Comic.GREEN, spaces);
    private final JComponent settingsPlate = plate("Einstellungen", Comic.BLUE, settingsFacts);
    /** Die Überschrift des dritten Kastens: „Einstellungen“ einer Quelle, „Quellen“ in der Gesamtsicht. */
    private final JLabel settingsTitle = headings.get(headings.size() - 1);
    static final String ALL_SOURCES = "Quellen";
    private final com.aresstack.spaceharvester.app.ui.controls.WrappedText note =
            Comic.wrapped(" ", Comic.MUTED, 13.5f);
    private static final java.time.format.DateTimeFormatter WHEN =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", java.util.Locale.GERMAN);
    private java.time.ZoneId zone = java.time.ZoneId.systemDefault();
    private Consumer<String> onEdit = id -> { };
    private String sourceId;
    /** Die Gesamtsicht über alle Quellen ({@link #all}); leer, wenn eine Quelle oder keine gezeigt wird. */
    private List<String> allIds = List.of();
    private final List<Subscription> allSubscriptions = new ArrayList<>();
    private int generation;
    private Subscription holdings = Subscription.NONE;
    private Subscription changes = Subscription.NONE;
    private Loaded shown;
    private Map<String, Loaded> shownAll;
    /** Höchstens ein Lauf gleichzeitig; Meldungen währenddessen oder bei verdecktem Reiter merken einen Nachlauf vor. */
    private boolean running;
    private boolean pending;
    private java.util.function.BooleanSupplier showing = this::isShowing;
    /** Die Quellentypen ändern sich zur Laufzeit nicht; einmal gelesen. */
    private volatile List<SourceType> types;

    SourcePropertiesView(Supplier<Workspace> workspace, Supplier<AppSettings> settings) {
        this(() -> {
            Workspace current = workspace.get();
            return current == null ? null : Actions.of(current);
        }, settings, new Runner() {
            @Override
            public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
                Background.run(work, onSuccess, onFailure);
            }
        });
    }

    /** Wie Quellenverwaltung und Sicherheit: mit Sitzung und Tresor der App, damit überall dasselbe steht. */
    void secretState(java.util.function.BiFunction<AppSettings, AppSettings.SourceEntry,
            com.aresstack.spaceharvester.app.ui.SecretState> state) {
        this.secretState = Objects.requireNonNull(state, "state");
    }

    SourcePropertiesView(Supplier<Actions> actions, Supplier<AppSettings> settings, Runner runner) {
        super(new BorderLayout());
        this.actions = Objects.requireNonNull(actions, "actions");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.runner = Objects.requireNonNull(runner, "runner");
        setOpaque(false);
        tools.setOpaque(false);
        edit.addActionListener(e -> {
            if (sourceId != null) {
                onEdit.accept(sourceId);
            }
        });
        tools.add(edit);
        tools.add(new CopyButton(this::copyText));
        // Die Hilfe steht nicht mehr bei den Werkzeugen im Kopf, sondern unten rechts in der Leiste ({@link #help()}).

        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        column.setBorder(BorderFactory.createEmptyBorder(10, 14, 14, 14));
        add(column, note);
        add(column, figuresPlate);
        add(column, gap());
        add(column, spacesPlate);
        add(column, gap());
        add(column, settingsPlate);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(column, BorderLayout.NORTH);
        add(Comic.scroll(top), BorderLayout.CENTER);
        // Beim Sichtbarwerden einmal nachholen, was verdeckt gemeldet wurde.
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                becameVisible();
            }
        });
        source(null);
    }

    /** Die Werkzeuge für die gemeinsame Kopfzeile. */
    /** Die „?“-Hilfe dieser Ansicht für die Ecke unten rechts in der Werkzeugleiste. */
    JComponent help() {
        return help;
    }

    private final com.aresstack.spaceharvester.app.ui.controls.HelpButton help =
            new com.aresstack.spaceharvester.app.ui.controls.HelpButton(24, "Eigenschaften der Quelle", HELP);

    JComponent tools() {
        return tools;
    }

    /** {@code listener} öffnet die Quelle zum Bearbeiten. */
    void onEdit(Consumer<String> listener) {
        onEdit = Objects.requireNonNull(listener, "listener");
    }

    /** Tests: ob der Reiter als sichtbar gilt (sonst {@link #isShowing()}). */
    void showingForTest(java.util.function.BooleanSupplier visible) {
        showing = Objects.requireNonNull(visible, "visible");
    }

    /** Tests: ob ein Nachlauf vorgemerkt ist. */
    boolean pendingForTest() {
        return pending;
    }

    /** Die gezeigte Quelle, {@code null} für keine: meldet sich an Bestand und Änderungsprotokoll an und liest. */
    void source(String id) {
        unsubscribe();
        generation++;
        sourceId = id;
        allIds = List.of();
        shown = null;
        shownAll = null;
        edit.setEnabled(id != null);
        if (id == null) {
            show(null, " ");
            return;
        }
        Actions current = actions.get();
        if (current == null) {
            show(null, NOT_RUNNING);
            return;
        }
        // Erst anmelden, dann lesen (ADR 0027); Meldungen kommen auf dem Zustell-Thread des Core.
        holdings = subscribeHoldings(current, id);
        changes = subscribeChanges(current, id);
        reload();
    }

    /**
     * Die Gesamtsicht über alle Wissensquellen (Angelo 17.11.2026: die Überschrift „Wissensquellen“ im Explorer):
     * meldet sich bei jeder Quelle an und liest alle; der Stift gilt hier nicht.
     */
    void all(List<String> ids) {
        unsubscribe();
        generation++;
        sourceId = null;
        allIds = List.copyOf(ids);
        shown = null;
        shownAll = null;
        edit.setEnabled(false);
        Actions current = actions.get();
        if (current == null) {
            show(null, NOT_RUNNING);
            return;
        }
        for (String id : allIds) {
            allSubscriptions.add(subscribeHoldings(current, id));
            allSubscriptions.add(subscribeChanges(current, id));
        }
        reload();
    }

    /** Ob die Gesamtsicht über alle Quellen gezeigt wird. */
    boolean showsAll() {
        return !allIds.isEmpty();
    }

    /** Die Quellen der Gesamtsicht, leer für eine einzelne oder keine. */
    List<String> allIds() {
        return allIds;
    }

    static final String NOT_LOADED = "noch nicht geladen";
    static final String SINCE_START = "Seit Beginn der Aufzeichnung";

    private void unsubscribe() {
        holdings.close();
        changes.close();
        holdings = Subscription.NONE;
        changes = Subscription.NONE;
        allSubscriptions.forEach(Subscription::close);
        allSubscriptions.clear();
    }

    private Subscription subscribeHoldings(Actions current, String id) {
        try {
            return current.subscribeHoldings(id, new HoldingsListener() {
                @Override
                public void pagesChanged(String source, String spaceKey, Set<String> pageIds) {
                    later(source);
                }

                @Override
                public void spaceChanged(String source, String spaceKey) {
                    later(source);
                }
            });
        } catch (RuntimeException e) {
            return Subscription.NONE;
        }
    }

    private Subscription subscribeChanges(Actions current, String id) {
        try {
            return current.subscribeChangeLog(id, this::later);
        } catch (RuntimeException e) {
            return Subscription.NONE;
        }
    }

    private void later(String changed) {
        SwingUtilities.invokeLater(() -> {
            if (Objects.equals(changed, sourceId) || allIds.contains(changed)) {
                reload();
            }
        });
    }

    /**
     * Liest Kennzahlen und Einstellungen neu; zeichnet nur, wenn sich etwas geändert hat. Gelesen wird nur, solange
     * der Reiter sichtbar ist, und höchstens ein Lauf gleichzeitig; sonst wird ein Nachlauf vorgemerkt.
     */
    void reload() {
        Actions current = actions.get();
        String id = sourceId;
        List<String> ids = allIds;
        if (current == null || id == null && ids.isEmpty()) {
            pending = false;
            return;
        }
        if (running || !showing.getAsBoolean()) {
            pending = true;
            return;
        }
        pending = false;
        running = true;
        int mine = ++generation;
        runner.run(() -> {
            List<SourceType> known = types;
            if (known == null) {
                known = List.copyOf(current.types());
                types = known;
            }
            Map<String, Loaded> loaded = new java.util.LinkedHashMap<>();
            for (String each : id != null ? List.of(id) : ids) {
                loaded.put(each, new Loaded(List.copyOf(current.spaces(each)), List.copyOf(current.updates(each)),
                        current.enabled(each), known));
            }
            return loaded;
        }, loaded -> {
            finished();
            if (mine != generation) {
                return;
            }
            if (id != null) {
                Loaded found = loaded.get(id);
                if (found.equals(shown)) {
                    return;
                }
                shown = found;
                show(found, null);
            } else {
                if (loaded.equals(shownAll)) {
                    return;
                }
                shownAll = loaded;
                showAll(loaded);
            }
        }, failure -> {
            finished();
            if (mine == generation) {
                show(null, "Eigenschaften nicht geladen: " + Problems.describe(failure).title());
            }
        });
    }

    /** Der Reiter ist sichtbar geworden: einmal nachholen, falls verdeckt etwas gemeldet wurde. */
    void becameVisible() {
        if (pending) {
            reload();
        }
    }

    private void finished() {
        running = false;
        if (pending) {
            SwingUtilities.invokeLater(this::reload);
        }
    }

    private void show(Loaded found, String message) {
        figures.reset();
        spaces.reset();
        settingsFacts.reset();
        settingsTitle.setText("Einstellungen");
        note.setText(message == null ? "" : message);
        note.setVisible(message != null && !message.isBlank());
        if (found != null) {
            fillFigures(found, 0);
            fillSpaces(found.spaces(), Map.of());
            fillSettings(found);
        }
        finishShow(found != null, found != null && !found.spaces().isEmpty());
    }

    /** Die Gesamtsicht: Kennzahlen über alle Quellen, Spaces mit Quellentitel davor, der Kasten „Quellen“. */
    private void showAll(Map<String, Loaded> loaded) {
        figures.reset();
        spaces.reset();
        settingsFacts.reset();
        settingsTitle.setText(ALL_SOURCES);
        note.setText("");
        note.setVisible(false);
        List<SpaceSummary> allSpaces = new ArrayList<>();
        List<ChangeUpdate> allUpdates = new ArrayList<>();
        Map<String, String> titles = new java.util.HashMap<>();
        List<SourceType> types = List.of();
        int notLoaded = 0;
        for (Map.Entry<String, Loaded> entry : loaded.entrySet()) {
            allSpaces.addAll(entry.getValue().spaces());
            allUpdates.addAll(entry.getValue().updates());
            titles.put(entry.getKey(), sourceTitle(entry.getKey()));
            types = entry.getValue().types();
            if (entry.getValue().spaces().isEmpty()) {
                notLoaded++;
            }
        }
        fillFigures(new Loaded(allSpaces, allUpdates, true, types), notLoaded);
        fillSpaces(allSpaces, titles);
        for (Map.Entry<String, Loaded> entry : loaded.entrySet()) {
            // Eine Quelle ohne Space im Bestand ist nie geladen: Zusatz in Orange, wie beim Export (Architekt 17.11.).
            boolean empty = entry.getValue().spaces().isEmpty();
            settingsFacts.add(titles.get(entry.getKey()), sourceLine(entry.getKey(), entry.getValue())
                    + (empty ? " · " + NOT_LOADED : ""),
                    empty || !entry.getValue().enabled() ? Comic.ORANGE : Comic.INK, null, "ID " + entry.getKey());
        }
        finishShow(true, !allSpaces.isEmpty());
    }

    private void finishShow(boolean any, boolean anySpaces) {
        for (Component part : column.getComponents()) {
            if (part != note) {
                part.setVisible(any);
            }
        }
        // Die drei Listen stehen bündig untereinander: gleiche Breite der Bezeichnungsspalte.
        int labels = Math.max(figures.labelColumnWidth(),
                Math.max(spaces.labelColumnWidth(), settingsFacts.labelColumnWidth()));
        for (Facts facts : List.of(figures, spaces, settingsFacts)) {
            facts.labelWidth(labels);
        }
        spacesPlate.setVisible(any && anySpaces);
        column.revalidate();
        column.repaint();
    }

    /** Der Anzeigename einer Quelle aus den Einstellungen, sonst ihre ID. */
    private String sourceTitle(String id) {
        AppSettings.SourceEntry entry = entry(id);
        return entry == null || entry.config().title() == null || entry.config().title().isBlank() ? id
                : entry.config().title();
    }

    /** Eine Zeile je Quelle in der Gesamtsicht: „Confluence · Personal Access Token · aktiv“. */
    private String sourceLine(String id, Loaded found) {
        AppSettings.SourceEntry entry = entry(id);
        List<String> parts = new ArrayList<>();
        if (entry != null) {
            SourceSettingsConfig config = entry.config();
            SourceType type = found.types().stream().filter(candidate -> candidate.id().equals(config.type()))
                    .findFirst().orElse(null);
            parts.add(type == null ? Texts.sourceType(config.type()) : type.displayName());
            parts.add(Texts.auth(config.auth()));
        }
        parts.add(found.enabled() ? "aktiv" : "nicht aktiv");
        return String.join(" · ", parts);
    }

    private AppSettings.SourceEntry entry(String id) {
        AppSettings current = settings.get();
        return current == null ? null : current.sources().stream().filter(source -> source.id().equals(id))
                .findFirst().orElse(null);
    }

    /** @param notLoaded in der Gesamtsicht die Quellen ohne Space im Bestand */
    private void fillFigures(Loaded found, int notLoaded) {
        HoldingCounts total = HoldingCounts.ZERO;
        int inventoried = 0;
        Instant asOf = null;
        for (SpaceSummary space : found.spaces()) {
            total = total.plus(space.counts());
            if (space.inventoried()) {
                inventoried++;
                asOf = asOf == null || space.asOf().isAfter(asOf) ? space.asOf() : asOf;
            }
        }
        int spaceCount = found.spaces().size();
        String spacesText = spaceCount == 0 ? "noch keiner im Bestand"
                : Texts.count(spaceCount, "Space", "Spaces") + (inventoried == spaceCount ? ""
                : inventoried == 0 ? ", keiner mit Inventur" : ", davon " + inventoried + " mit Inventur");
        figures.add("Spaces", notLoaded == 0 ? spacesText : spacesText + " · "
                + Texts.count(notLoaded, "Quelle", "Quellen") + " " + NOT_LOADED, notLoaded == 0 ? Comic.INK : Comic.ORANGE);
        figures.add("Seiten", String.valueOf(total.pages()));
        figures.add("Lokal", total.local() + " aktuell, " + total.outdated() + " veraltet",
                total.outdated() > 0 ? Comic.ORANGE : Comic.INK, null, total.outdated() > 0 ? Texts.OUTDATED_WHY : null);
        figures.add("Nicht lokal", String.valueOf(Math.max(0, total.pages() - total.stored() - total.locked())));
        figures.add("Gesperrt", String.valueOf(total.locked()), total.locked() > 0 ? Comic.RED : Comic.INK);
        figures.add("Stand vom", asOf == null ? "keine Inventur" : when(asOf));
        List<ChangeUpdate> done = found.updates().stream().filter(update -> !update.open()).toList();
        // In der Gesamtsicht je Quelle ein offenes Update: die geänderten Seiten zählen zusammen. Die Erstbefüllung ist
        // keine Änderung (ADR 0030) und zählt nicht; ohne abgeschlossenes Update zählt es seit Beginn der Aufzeichnung.
        List<ChangeUpdate> open = found.updates().stream().filter(ChangeUpdate::open)
                .filter(update -> !update.initial()).toList();
        figures.add("Updates", done.isEmpty() ? "noch keines" : String.valueOf(done.size()));
        done.stream().map(ChangeUpdate::to).filter(Objects::nonNull).max(Comparator.naturalOrder())
                .ifPresent(last -> figures.add("Letztes Update", when(last)));
        if (!open.isEmpty()) {
            figures.add(done.isEmpty() ? SINCE_START : "Seit dem letzten Update",
                    Texts.count(open.stream().mapToInt(ChangeUpdate::pages).sum(), "geänderte Seite",
                            "geänderte Seiten"));
        }
    }

    /**
     * Je Space eine Zeile in der farbigen Form des Export-Reiters (Angelo 17.11.2026): „305 von 340 Seiten lokal ·
     * 5 veraltet · 2 gesperrt · Stand vom 16.11.2026, 18:30“, grün wenn alles lokal und inventarisiert, orange
     * sonst, grau „Noch nicht geladen“ ohne Stand ({@link ExportPanel#standTone}).
     */
    private void fillSpaces(List<SpaceSummary> list, Map<String, String> titles) {
        list.stream().sorted(Comparator.comparing(SpaceSummary::sourceId).thenComparing(SpaceSummary::spaceKey))
                .forEach(space -> {
            HoldingCounts counts = space.counts();
            String title = titles.get(space.sourceId());
            spaces.add(title == null ? space.spaceKey() : title + " › " + space.spaceKey(), spaceText(space),
                    ExportPanel.standTone(space, null), null, counts.outdated() > 0 ? Texts.OUTDATED_WHY : null);
        });
    }

    String spaceText(SpaceSummary space) {
        HoldingCounts counts = space.counts();
        if (counts.stored() == 0 && !space.inventoried()) {
            return ExportPanel.NOT_LOADED;
        }
        List<String> parts = new ArrayList<>();
        parts.add(counts.stored() + " von " + Texts.count(counts.pages(), "Seite", "Seiten") + " lokal");
        if (counts.outdated() > 0) {
            parts.add(counts.outdated() + " veraltet");
        }
        if (counts.locked() > 0) {
            parts.add(counts.locked() + " gesperrt");
        }
        parts.add(space.inventoried() ? "Stand vom " + when(space.asOf()) : ExportPanel.INCOMPLETE);
        return String.join(" · ", parts);
    }

    private void fillSettings(Loaded found) {
        AppSettings current = settings.get();
        AppSettings.SourceEntry entry = current == null ? null : current.sources().stream()
                .filter(source -> source.id().equals(sourceId)).findFirst().orElse(null);
        settingsFacts.add("ID", sourceId);
        settingsFacts.add("Aktiv", found.enabled() ? "ja" : "nein", found.enabled() ? Comic.INK : Comic.ORANGE);
        if (entry == null) {
            return;
        }
        SourceSettingsConfig config = entry.config();
        SourceType type = found.types().stream().filter(candidate -> candidate.id().equals(config.type()))
                .findFirst().orElse(null);
        settingsFacts.add("Typ", type == null ? Texts.sourceType(config.type()) : type.displayName());
        settingsFacts.add("Anzeigename", config.title());
        if (config.baseUrl() != null) {
            settingsFacts.add("Adresse", withoutUser(config.baseUrl()));
        }
        for (Map.Entry<String, String> option : new java.util.TreeMap<>(config.options()).entrySet()) {
            SourceField field = type == null ? null : type.fields().stream()
                    .filter(candidate -> candidate.key().equals(option.getKey())).findFirst().orElse(null);
            settingsFacts.add(field == null ? option.getKey() : field.label(),
                    optionText(field, option.getValue(), entry.placeholderOptions().get(option.getKey())));
        }
        settingsFacts.add("Anmeldung", Texts.auth(config.auth()));
        if (config.auth().type() != SourceSettingsConfig.AuthType.NONE) {
            com.aresstack.spaceharvester.app.ui.SecretState secret = secretState.apply(current, entry);
            settingsFacts.add(config.auth().type() == SourceSettingsConfig.AuthType.PAT ? "Token" : "Passwort",
                    secret.text(), secret.color());
        }
        settingsFacts.add("Client-Zertifikat", config.clientCertificate().enabled() ? "an" : "aus");
    }

    /**
     * Der angezeigte Wert einer Option. Was in der Datei als {@code ${VAR}} steht, erscheint nie aufgelöst, sondern als
     * Platzhalter; ein Zugangsverweis nur als Verweis.
     */
    static String optionText(SourceField field, String value, String placeholder) {
        if (placeholder != null) {
            return placeholder;
        }
        if (field != null && field.kind() == SourceField.Kind.SECRET_REF) {
            return value == null || value.isBlank() ? "kein Verweis" : "Verweis " + value;
        }
        if (field != null && field.kind() == SourceField.Kind.FLAG) {
            return Boolean.parseBoolean(value) ? "ja" : "nein";
        }
        return value;
    }

    /** Die Adresse ohne Benutzer-Teil ({@code user:pass@}). */
    static String withoutUser(java.net.URI address) {
        if (address.getRawUserInfo() == null) {
            return address.toString();
        }
        try {
            return new java.net.URI(address.getScheme(), null, address.getHost(), address.getPort(),
                    address.getPath(), address.getQuery(), address.getFragment()).toString();
        } catch (java.net.URISyntaxException e) {
            return address.getScheme() + "://" + address.getHost();
        }
    }

    /** Alle Kennzahlen und Einstellungen als Text für die Zwischenablage. */
    String copyText() {
        if (shown == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        collect(column, text);
        return text.toString().strip();
    }

    private void collect(Component component, StringBuilder text) {
        if (!component.isVisible()) {
            return;
        }
        if (component instanceof JLabel label) {
            boolean heading = headings.contains(label);
            text.append(heading ? "\n" : "").append(label.getText()).append(heading ? "\n" : ": ");
        } else if (component instanceof javax.swing.text.JTextComponent area) {
            text.append(area.getText()).append('\n');
        } else if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                collect(child, text);
            }
        }
    }

    /** Ein Kasten aus der gemeinsamen Bibliothek; seine Überschrift zählt beim Kopieren als Abschnitt. */
    private JComponent plate(String title, java.awt.Color stripe, Facts content) {
        JComponent plate = Comic.plate(title, stripe, content);
        for (Component child : plate.getComponents()) {
            if (child instanceof JLabel label) {
                headings.add(label);
            }
        }
        return plate;
    }

    private static JComponent gap() {
        return (JComponent) javax.swing.Box.createVerticalStrut(10);
    }

    private static void add(JPanel column, JComponent part) {
        part.setAlignmentX(LEFT_ALIGNMENT);
        column.add(part);
    }

    private String when(Instant at) {
        return at == null ? "–" : WHEN.format(at.atZone(zone));
    }

    // ---- für Tests

    /** Die Überschriften der sichtbaren Kästen, von oben nach unten. */
    List<String> plateTitles() {
        return headings.stream().filter(label -> label.getParent() != null && label.getParent().isVisible())
                .map(JLabel::getText).toList();
    }

    void zone(java.time.ZoneId value) {
        zone = Objects.requireNonNull(value, "zone");
    }

    String sourceId() {
        return sourceId;
    }

    boolean subscribed() {
        return holdings != Subscription.NONE && changes != Subscription.NONE;
    }

    boolean editEnabled() {
        return edit.isEnabled();
    }

    void clickEdit() {
        edit.doClick();
    }

    /** Alle sichtbaren Texte, in Reihenfolge. */
    List<String> texts() {
        List<String> texts = new ArrayList<>();
        texts(column, texts);
        return texts;
    }

    private static void texts(Component component, List<String> texts) {
        if (!component.isVisible()) {
            return;
        }
        if (component instanceof JLabel label) {
            texts.add(label.getText());
        } else if (component instanceof javax.swing.text.JTextComponent area) {
            texts.add(area.getText());
        } else if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                texts(child, texts);
            }
        }
    }
}
