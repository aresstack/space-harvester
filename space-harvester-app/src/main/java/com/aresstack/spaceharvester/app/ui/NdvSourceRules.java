package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.settings.SourceDraft;
import com.aresstack.spaceharvester.app.ui.deck.NdvSearchLink;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.deck.ndv.NdvProfile;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.awt.Component;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * „Indexieren (Regel anlegen)“ des NDV-Bereichs über die Wissensquellen der App: Die Regel ist eine Quelle vom Typ
 * {@code ndv}; ihr Abgleich legt die Natural-Quelltexte in den Suchindex des Core, den die globale Suche und die
 * KI-Suche (Chat-Werkzeuge) durchsuchen. Geschrieben wird nur über das Quellenformular, wie bei jeder anderen Quelle.
 */
final class NdvSourceRules implements NdvSearchLink {

    static final String TYPE = "ndv";
    static final String START_POINTS = "startPoints";
    private static final int DEFAULT_PORT = NdvProfile.DEFAULT_PORT;

    private final AppContext context;

    NdvSourceRules(AppContext context) {
        this.context = context;
    }

    @Override
    public boolean available() {
        return context.settings() != null;
    }

    @Override
    public Optional<Rule> rule(NdvProfile profile) {
        return Optional.empty();
    }

    @Override
    public Optional<Rule> rule(NdvProfile profile, String account) {
        AppSettings settings = context.settings();
        if (settings == null || profile == null) {
            return Optional.empty();
        }
        List<SourceSettingsConfig> matching = settings.sources().stream()
                .map(AppSettings.SourceEntry::config)
                .filter(config -> matches(config, profile)
                        && sameAccount(config, settings.keePassEntry(config.id()), account))
                .toList();
        if (matching.size() > 1) {
            throw new IllegalStateException("Mehrere passende NDV-Quellen. Bitte in der Quellenverwaltung auswählen.");
        }
        if (matching.isEmpty()) {
            return Optional.empty();
        }
        SourceSettingsConfig config = matching.getFirst();
        if (context.runtime() == null || context.runtime().workspace().sources().stream()
                .noneMatch(source -> source.id().equals(config.id()))) {
            throw new IllegalStateException("Passende NDV-Quelle ist deaktiviert oder nicht verfügbar.");
        }
        return Optional.of(new Rule(config.id(), config.title() == null || config.title().isBlank()
                ? config.id() : config.title(), libraries(config.options().get(START_POINTS))));
    }

    static boolean sameAccount(SourceSettingsConfig config, String configuredAccount, String account) {
        if (account == null || account.isBlank()) {
            return false;
        }
        if (configuredAccount != null) {
            return account.equals(configuredAccount);
        }
        return config.auth().type() == SourceSettingsConfig.AuthType.NONE
                && ("keepass:" + account).equals(config.options().get("credentialRef"));
    }

    /** Match the actual NDV default port and canonical host. */
    static boolean matches(SourceSettingsConfig config, NdvProfile profile) {
        if (config == null || !TYPE.equals(config.type())) {
            return false;
        }
        String host = config.options().get("host");
        if (host == null || !host.strip().equalsIgnoreCase(profile.host().strip())) {
            return false;
        }
        return port(config.options().get("port")) == profile.port();
    }

    private static int port(String value) {
        try {
            return value == null || value.isBlank() ? DEFAULT_PORT : Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Die Bibliotheken aus {@code startPoints}: kommagetrennt, Filter {@code LIB/PREFIX*} zählt als {@code LIB}. */
    static List<String> libraries(String startPoints) {
        Set<String> found = new LinkedHashSet<>();
        if (startPoints != null) {
            for (String part : startPoints.split(",")) {
                String library = part.strip();
                int slash = library.indexOf('/');
                if (slash >= 0) {
                    String filter = library.substring(slash + 1).strip();
                    if (!filter.isEmpty() && !"*".equals(filter)) {
                        continue;
                    }
                    library = library.substring(0, slash).strip();
                }
                if (!library.isEmpty()) {
                    found.add(library.toUpperCase(Locale.ROOT));
                }
            }
        }
        return List.copyOf(found);
    }

    /** {@code startPoints} um fehlende Bibliotheken ergänzt; Vorhandenes (auch mit Filter) bleibt, wie es ist. */
    static String withLibraries(String startPoints, List<String> wanted) {
        List<String> parts = new ArrayList<>();
        if (startPoints != null) {
            for (String part : startPoints.split(",")) {
                if (!part.isBlank()) {
                    parts.add(part.strip());
                }
            }
        }
        List<String> known = libraries(startPoints);
        for (String library : wanted) {
            String upper = library.strip().toUpperCase(Locale.ROOT);
            if (!upper.isEmpty() && !known.contains(upper) && !parts.contains(upper)) {
                parts.add(upper);
            }
        }
        return String.join(", ", parts);
    }

    @Override
    public void createRule(Component parent, NdvProfile profile, String account, List<String> libraries,
                           Consumer<String> saved) {
        if (account == null || account.isBlank()) {
            throw new IllegalStateException("Für die automatische Zuordnung bitte einen KeePass-Eintrag wählen.");
        }
        Optional<Rule> existing = rule(profile, account);
        if (existing.isPresent()) {
            SourceDraft.Editable editable;
            try {
                editable = new ConfigFileEditor(context.loader()).editable(context.configFile(),
                        existing.get().sourceId());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            SourceForm.open(parent, context, extended(editable, libraries), saved);
            return;
        }
        Map<String, String> options = new LinkedHashMap<>();
        options.put("host", profile.host());
        options.put("port", String.valueOf(profile.port()));
        options.put(START_POINTS, withLibraries(null, libraries));
        SourceForm.open(parent, context, TYPE, options, account, saved);
    }

    /** Der bestehende Eintrag mit den Bibliotheken in {@code startPoints} ergänzt. */
    static SourceDraft.Editable extended(SourceDraft.Editable editable, List<String> libraries) {
        SourceDraft d = editable.draft();
        Map<String, String> options = new TreeMap<>(d.extraOptions());
        options.put(START_POINTS, withLibraries(options.get(START_POINTS), libraries));
        SourceDraft draft = new SourceDraft(d.id(), d.type(), d.title(), d.baseUrl(), d.directory(), d.include(),
                d.authType(), d.username(), d.secretVariable(), d.certificateType(), d.certificatePath(),
                d.certificatePasswordVariable(), d.certificateAlias(), d.thumbprint(), d.trustBundle(), options,
                d.baseUrlType());
        return new SourceDraft.Editable(draft, editable.plaintextSecrets());
    }

    @Override
    public String indexNow(String sourceId) {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            throw new IllegalStateException("Die Wissensquellen laufen noch nicht");
        }
        List<Partition> partitions = runtime.workspace().spaces(sourceId);
        if (partitions.isEmpty()) {
            throw new IllegalStateException("Die Quelle „" + sourceId + "“ hat nichts zum Abgleichen");
        }
        // Ausdrücklich indexieren heißt Quelltexte laden (FULL); ein Nachholen (SYNC_CACHE) würde neue Objekte nur
        // katalogisieren, ihr Text käme nie in die Suche.
        for (Partition partition : partitions) {
            runtime.workspace().startSync(sourceId, partition.key(),
                    com.aresstack.spaceharvester.application.sync.SyncMode.FULL);
        }
        return "Indexieren gestartet: Abgleich der Quelle „" + sourceId + "“ (siehe Aufträge)";
    }

    @Override
    public void observe(Runnable changed) {
        context.onChange(ignored -> changed.run());
    }
}
