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
 * die Quelle unter Quellen; der Stift oben in der Kopfzeile führt dorthin.
 */
final class SourcePropertiesView extends JPanel {

    static final String HELP = "Die Kennzahlen stammen aus dem lokalen Bestand: „Seiten“ zählt alle Seiten, die der "
            + "Bestand von der Quelle kennt, „lokal“ die mit gespeichertem Inhalt, „veraltet“ die, deren Inhalt seit dem "
            + "Laden in der Quelle geändert wurde. Ein Space ohne Inventur kennt nur einzelne Seiten; seine Zahlen gelten "
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
    private final Runner runner;
    private final JPanel tools = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 2, 0));
    private final IconButton edit = new IconButton(Glyph.PENCIL, EDIT_TIP);
    private final JPanel column = new JPanel();
    private final Facts figures = new Facts();
    private final Facts spaces = new Facts();
    private final Facts settingsFacts = new Facts();
    private final List<JLabel> headings = new ArrayList<>();
    private final JLabel spacesHeading = heading("Spaces");
    private final com.aresstack.spaceharvester.app.ui.controls.WrappedText note =
            Comic.wrapped(" ", Comic.MUTED, 13.5f);
    private static final java.time.format.DateTimeFormatter WHEN =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", java.util.Locale.GERMAN);
    private java.time.ZoneId zone = java.time.ZoneId.systemDefault();
    private Consumer<String> onEdit = id -> { };
    private String sourceId;
    private int generation;
    private Subscription holdings = Subscription.NONE;
    private Subscription changes = Subscription.NONE;
    private Loaded shown;

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
        tools.add(new HelpButton(24, "Eigenschaften der Quelle", HELP));

        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        column.setBorder(BorderFactory.createEmptyBorder(10, 14, 14, 14));
        add(column, note);
        add(column, heading("Kennzahlen"));
        add(column, figures);
        add(column, spacesHeading);
        add(column, spaces);
        add(column, heading("Einstellungen"));
        add(column, settingsFacts);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(column, BorderLayout.NORTH);
        add(Comic.scroll(top), BorderLayout.CENTER);
        source(null);
    }

    /** Die Werkzeuge für die gemeinsame Kopfzeile. */
    JComponent tools() {
        return tools;
    }

    /** {@code listener} öffnet die Quelle zum Bearbeiten. */
    void onEdit(Consumer<String> listener) {
        onEdit = Objects.requireNonNull(listener, "listener");
    }

    /** Die gezeigte Quelle, {@code null} für keine: meldet sich an Bestand und Änderungsprotokoll an und liest. */
    void source(String id) {
        holdings.close();
        changes.close();
        holdings = Subscription.NONE;
        changes = Subscription.NONE;
        generation++;
        sourceId = id;
        shown = null;
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
        try {
            holdings = current.subscribeHoldings(id, new HoldingsListener() {
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
            holdings = Subscription.NONE;
        }
        try {
            changes = current.subscribeChangeLog(id, this::later);
        } catch (RuntimeException e) {
            changes = Subscription.NONE;
        }
        reload();
    }

    private void later(String changed) {
        SwingUtilities.invokeLater(() -> {
            if (Objects.equals(changed, sourceId)) {
                reload();
            }
        });
    }

    /** Liest Kennzahlen und Einstellungen neu; zeichnet nur, wenn sich etwas geändert hat. */
    void reload() {
        Actions current = actions.get();
        String id = sourceId;
        if (current == null || id == null) {
            return;
        }
        int mine = ++generation;
        runner.run(() -> new Loaded(List.copyOf(current.spaces(id)), List.copyOf(current.updates(id)),
                current.enabled(id), List.copyOf(current.types())), found -> {
            if (mine != generation || found.equals(shown)) {
                return;
            }
            shown = found;
            show(found, null);
        }, failure -> {
            if (mine == generation) {
                show(null, "Eigenschaften nicht geladen: " + Problems.describe(failure).title());
            }
        });
    }

    private void show(Loaded found, String message) {
        figures.reset();
        spaces.reset();
        settingsFacts.reset();
        note.setText(message == null ? "" : message);
        note.setVisible(message != null && !message.isBlank());
        if (found != null) {
            fillFigures(found);
            fillSpaces(found.spaces());
            fillSettings(found);
        }
        boolean any = found != null;
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
        spacesHeading.setVisible(any && !found.spaces().isEmpty());
        spaces.setVisible(any && !found.spaces().isEmpty());
        column.revalidate();
        column.repaint();
    }

    private void fillFigures(Loaded found) {
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
        figures.add("Spaces", spaceCount == 0 ? "noch keiner im Bestand"
                : Texts.count(spaceCount, "Space", "Spaces") + (inventoried == spaceCount ? ""
                : inventoried == 0 ? ", keiner mit Inventur" : ", davon " + inventoried + " mit Inventur"));
        figures.add("Seiten", String.valueOf(total.pages()));
        figures.add("Lokal", total.local() + " aktuell, " + total.outdated() + " veraltet",
                total.outdated() > 0 ? Comic.ORANGE : Comic.INK);
        figures.add("Nicht lokal", String.valueOf(Math.max(0, total.pages() - total.stored() - total.locked())));
        figures.add("Gesperrt", String.valueOf(total.locked()), total.locked() > 0 ? Comic.RED : Comic.INK);
        figures.add("Stand vom", asOf == null ? "keine Inventur" : when(asOf));
        List<ChangeUpdate> done = found.updates().stream().filter(update -> !update.open()).toList();
        ChangeUpdate open = found.updates().stream().filter(ChangeUpdate::open).findFirst().orElse(null);
        figures.add("Updates", done.isEmpty() ? "noch keines" : String.valueOf(done.size()));
        done.stream().map(ChangeUpdate::to).filter(Objects::nonNull).max(Comparator.naturalOrder())
                .ifPresent(last -> figures.add("Letztes Update", when(last)));
        if (open != null) {
            figures.add("Seit dem letzten Update", Texts.count(open.pages(), "geänderte Seite", "geänderte Seiten"));
        }
    }

    private void fillSpaces(List<SpaceSummary> list) {
        list.stream().sorted(Comparator.comparing(SpaceSummary::spaceKey)).forEach(space -> {
            HoldingCounts counts = space.counts();
            List<String> parts = new ArrayList<>();
            parts.add(Texts.count(counts.pages(), "Seite", "Seiten"));
            parts.add(counts.local() + " lokal");
            if (counts.outdated() > 0) {
                parts.add(counts.outdated() + " veraltet");
            }
            if (counts.locked() > 0) {
                parts.add(counts.locked() + " gesperrt");
            }
            parts.add(space.inventoried() ? "Stand " + when(space.asOf()) : "keine Inventur");
            spaces.add(space.spaceKey(), String.join(" · ", parts), space.inventoried() ? Comic.INK : Comic.MUTED);
        });
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
            settingsFacts.add("Adresse", config.baseUrl().toString());
        }
        for (Map.Entry<String, String> option : new java.util.TreeMap<>(config.options()).entrySet()) {
            SourceField field = type == null ? null : type.fields().stream()
                    .filter(candidate -> candidate.key().equals(option.getKey())).findFirst().orElse(null);
            String value = option.getValue();
            if (field != null && field.kind() == SourceField.Kind.FLAG) {
                value = Boolean.parseBoolean(value) ? "ja" : "nein";
            }
            settingsFacts.add(field == null ? option.getKey() : field.label(), value);
        }
        settingsFacts.add("Anmeldung", Texts.auth(config.auth()));
        if (config.auth().type() != SourceSettingsConfig.AuthType.NONE) {
            settingsFacts.add(config.auth().type() == SourceSettingsConfig.AuthType.PAT ? "Token" : "Passwort",
                    entry.secretMissing() ? "fehlt" : "gesetzt", entry.secretMissing() ? Comic.RED : Comic.INK);
        }
        settingsFacts.add("Client-Zertifikat", config.clientCertificate().enabled() ? "an" : "aus");
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

    private JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Comic.semiBold(14f));
        label.setForeground(Comic.INK);
        label.setBorder(BorderFactory.createEmptyBorder(12, 0, 4, 0));
        headings.add(label);
        return label;
    }

    private static void add(JPanel column, JComponent part) {
        part.setAlignmentX(LEFT_ALIGNMENT);
        column.add(part);
    }

    private String when(Instant at) {
        return at == null ? "–" : WHEN.format(at.atZone(zone));
    }

    // ---- für Tests

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
