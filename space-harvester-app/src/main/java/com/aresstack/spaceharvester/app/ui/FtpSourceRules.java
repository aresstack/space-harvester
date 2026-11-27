package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.settings.SourceDraft;
import com.aresstack.spaceharvester.app.ui.controls.StatusNote;
import com.aresstack.spaceharvester.app.ui.deck.FtpSourceLink;
import com.aresstack.spaceharvester.deck.ftp.FtpSession;
import com.aresstack.spaceharvester.deck.ftp.MvsPath;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.awt.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * {@link FtpSourceLink} über die Wissensquellen der App: schreibt die Quelle wie das Quellenformular in die
 * Konfigurationsdatei ({@link ConfigFileEditor}), nur ohne Formular. Anmeldung über den KeePass-Eintrag der Sitzung
 * ({@code credentialRef = keepass:<Eintrag>}, wie {@link FtpNavigationFill} ihn erkennt). Danach lädt die App die
 * Quellen neu; ein Abgleich startet nicht (Angelo, 24.11.2026: nichts ohne Klick).
 */
final class FtpSourceRules implements FtpSourceLink {

    static final String TYPE = "ftp";
    static final String START_POINTS = "startPoints";

    private final AppContext context;
    private boolean running;

    FtpSourceRules(AppContext context) {
        this.context = context;
    }

    /** Was {@link #add} schreiben würde: neue Quelle, Startpunkt ergänzen oder schon enthalten. */
    record Plan(String sourceId, String title, String startPoint, String startPoints, boolean covered) {
        boolean newSource() {
            return sourceId == null;
        }
    }

    @Override
    public void add(Component parent, FtpSession session, String location, Consumer<StatusNote> status) {
        if (running) {
            return;
        }
        AppSettings settings = context.settings();
        if (settings == null || context.runtime() == null) {
            status.accept(warning("Wissensquellen sind gerade nicht verfügbar."));
            return;
        }
        Plan plan;
        try {
            plan = plan(settings, session, location);
        } catch (RuntimeException problem) {
            status.accept(warning(problem.getMessage()));
            return;
        }
        if (plan.covered()) {
            status.accept(shown("„" + plan.startPoint() + "“ gehört schon zur Wissensquelle „" + plan.title()
                    + "“. Synchronisieren per Klick im Space Harvester: anzeigen", plan));
            return;
        }
        if (!Dialogs.restartAllowed(parent, context)) {
            return;
        }
        running = true;
        status.accept(StatusNote.plain("Wissensquelle wird eingetragen …"));
        String account = session.label();
        Background.run(() -> {
            ConfigFileEditor editor = new ConfigFileEditor(context.loader());
            // Gegen die gespeicherte Datei erneut planen: Hat sich etwas geändert, gilt der neue Stand.
            AppSettings current = context.loader().load(context.configFile());
            Plan checked = plan(current, session, location);
            if (checked.covered()) {
                return checked;
            }
            ConfigFileEditor.Draft draft;
            if (checked.newSource()) {
                String id = "ftp-" + UUID.randomUUID().toString().substring(0, 8);
                Map<String, String> options = new LinkedHashMap<>();
                options.put("host", session.profile().host());
                options.put("port", String.valueOf(session.profile().port()));
                options.put("credentialRef", "keepass:" + account);
                options.put(START_POINTS, checked.startPoint());
                SourceDraft source = new SourceDraft(id, TYPE, checked.startPoint(), null, null, null,
                        SourceSettingsConfig.AuthType.NONE, null, null, null, null, null, null, null, null, options)
                        .withUsage(true, globalSearch());
                draft = editor.draft(context.configFile(), source);
                checked = new Plan(id, checked.startPoint(), checked.startPoint(), checked.startPoint(), false);
            } else {
                SourceDraft.Editable editable = editor.editable(context.configFile(), checked.sourceId());
                Map<String, String> options = new TreeMap<>(editable.draft().extraOptions());
                options.put(START_POINTS, checked.startPoints());
                draft = editor.replace(context.configFile(), editable.draft().withOptions(options));
            }
            editor.save(context.configFile(), draft, !editor.fileHasPlaintextSecret(context.configFile()));
            return checked;
        }, done -> {
            if (done.covered()) {
                running = false;
                status.accept(shown("„" + done.startPoint() + "“ gehört schon zur Wissensquelle „" + done.title()
                        + "“. Synchronisieren per Klick im Space Harvester: anzeigen", done));
                return;
            }
            context.reload(() -> {
                running = false;
                status.accept(shown("Wissensquelle „" + done.title() + "“ "
                        + (done.startPoints().equals(done.startPoint()) ? "eingetragen" : "um „" + done.startPoint()
                        + "“ ergänzt") + ". Synchronisieren per Klick im Space Harvester: anzeigen", done));
            });
        }, error -> {
            running = false;
            Problems.Problem problem = Problems.describe(error);
            status.accept(new StatusNote(problem.title() + ": " + problem.message(), StatusNote.Tone.ERROR, null, null));
        });
    }

    private boolean globalSearch() {
        return context.runtime() != null && context.runtime().workspace().sourceTypes().stream()
                .filter(type -> TYPE.equals(type.id())).findFirst()
                .map(type -> type.defaults().includeInGlobalSearch()).orElse(false);
    }

    private StatusNote shown(String text, Plan plan) {
        return new StatusNote(text, StatusNote.Tone.PLAIN, "anzeigen",
                () -> context.navigator().openSource(plan.sourceId(), plan.title()));
    }

    private static StatusNote warning(String text) {
        return new StatusNote(text, StatusNote.Tone.WARNING, null, null);
    }

    /**
     * Neue Quelle, Startpunkt ergänzen oder schon enthalten – für Server und Konto der Sitzung.
     *
     * @throws IllegalStateException ohne KeePass-Eintrag, ohne Ort oder bei mehreren passenden Quellen
     */
    static Plan plan(AppSettings settings, FtpSession session, String location) {
        String account = session.label();
        if (account == null || account.isBlank() || session.user().isBlank()) {
            throw new IllegalStateException("Bitte zuerst mit einem KeePass-Eintrag verbinden.");
        }
        String startPoint = startPoint(location, session.mvs());
        List<SourceSettingsConfig> matching = settings.sources().stream().map(AppSettings.SourceEntry::config)
                .filter(config -> FtpNavigationFill.serves(config, session.profile(), account, session.user(),
                        session.mvs()) || sameKeePass(config, settings.keePassEntry(config.id()), account, session))
                .toList();
        if (matching.size() > 1) {
            throw new IllegalStateException("Mehrere FTP-Wissensquellen passen zu dieser Verbindung. Bitte unter "
                    + "Wissensquellen eine auswählen und den Startpunkt dort eintragen.");
        }
        if (matching.isEmpty()) {
            return new Plan(null, startPoint, startPoint, startPoint, false);
        }
        SourceSettingsConfig config = matching.getFirst();
        String title = config.title() == null || config.title().isBlank() ? config.id() : config.title();
        String configured = config.options().get(START_POINTS);
        boolean covered = covered(configured, startPoint, session.mvs());
        return new Plan(config.id(), title, startPoint, covered ? configured : withStartPoint(configured, startPoint),
                covered);
    }

    /** Eine Quelle, deren KeePass-Eintrag die App selbst führt (Formular „aus KeePass“), auf demselben Server. */
    private static boolean sameKeePass(SourceSettingsConfig config, String configuredEntry, String account,
                                       FtpSession session) {
        return configuredEntry != null && configuredEntry.equals(account)
                && FtpNavigationFill.sameEndpoint(config, session.profile());
    }

    /** Der Startpunkt einer Quelle: MVS ohne Hochkommas und groß, Unix unverändert. */
    static String startPoint(String location, boolean mvs) {
        String value = location == null ? "" : location.strip();
        if (mvs) {
            value = MvsPath.unquote(value).strip().toUpperCase(Locale.ROOT);
        }
        if (value.isEmpty() || (!mvs && "/".equals(value))) {
            throw new IllegalStateException("Bitte zuerst ein Verzeichnis öffnen oder auswählen.");
        }
        return value;
    }

    /** Ob einer der Startpunkte den Ort schon umfasst (gleich oder darüber). */
    static boolean covered(String startPoints, String startPoint, boolean mvs) {
        for (String part : parts(startPoints)) {
            if (mvs) {
                String known = MvsPath.unquote(part).strip().toUpperCase(Locale.ROOT);
                if (startPoint.equals(known) || startPoint.startsWith(known + ".")
                        || startPoint.startsWith(known + "(")) {
                    return true;
                }
            } else {
                String known = part.endsWith("/") && part.length() > 1 ? part.substring(0, part.length() - 1) : part;
                if ("/".equals(known) || startPoint.equals(known) || startPoint.startsWith(known + "/")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Die Startpunkte um diesen ergänzt; Vorhandenes bleibt in seiner Reihenfolge. */
    static String withStartPoint(String startPoints, String startPoint) {
        List<String> all = new ArrayList<>(parts(startPoints));
        all.add(startPoint);
        return String.join("; ", all);
    }

    private static List<String> parts(String startPoints) {
        List<String> result = new ArrayList<>();
        if (startPoints != null) {
            for (String part : startPoints.split("[;,\\r\\n]+")) {
                if (!part.isBlank()) {
                    result.add(part.strip());
                }
            }
        }
        return result;
    }
}
