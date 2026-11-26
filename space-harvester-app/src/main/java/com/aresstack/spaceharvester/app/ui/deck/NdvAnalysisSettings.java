package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.deck.ndv.analysis.NdvAccessRules;
import com.aresstack.spaceharvester.deck.ndv.NdvSession;
import com.aresstack.spaceharvester.deck.ndv.analysis.NdvAnalysis;
import com.aresstack.spaceharvester.deck.ndv.analysis.NdvLibraryResolver;
import com.aresstack.spaceharvester.deck.ndv.analysis.NdvSourceCache;
import com.aresstack.spaceharvester.deck.ndv.cache.FileNdvSourceStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Die NDV-Einstellungen der Analyse wie in MainframeMate ({@code Settings.ndvLibrarySearchOrder},
 * {@code naturalLibraryMappings} und die NDV-Gruppe des {@code SecurityFilterService}), gespeichert im App-Zustand;
 * dazu je Server Zwischenspeicher und Analyse. Der Zwischenspeicher liegt im App-Ordner unter {@code ndv-cache}.
 */
public final class NdvAnalysisSettings {

    static final String SEARCH_ORDER_KEY = "deck.ndv.searchOrder";
    static final String STEPLIB_KEY = "deck.ndv.stepLibs";
    static final String RULES_ALL_KEY = "deck.ndv.rules.blacklistAll";
    static final String RULES_WHITE_KEY = "deck.ndv.rules.whitelist";
    static final String RULES_BLACK_KEY = "deck.ndv.rules.blacklist";
    /** Systemeigenschaft für einen anderen Ort des Zwischenspeichers (Tests). */
    static final String CACHE_PROPERTY = "space-harvester.ndv-cache";

    private static final NdvAccessRules RULES = new NdvAccessRules();
    private static final Map<String, NdvAnalysis> ANALYSES = new ConcurrentHashMap<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile NdvSearchLink searchLink = NdvSearchLink.NONE;

    static {
        reload();
        RULES.observe(NdvAnalysisSettings::saveRules);
    }

    private NdvAnalysisSettings() {
    }

    /** Liest Sperr- und Freigabeliste neu aus dem App-Zustand (beim Start und in Tests). */
    public static void reload() {
        AppState state = AppState.shared();
        RULES.restore(new NdvAccessRules.Snapshot(state.getBoolean(RULES_ALL_KEY, false),
                lines(state.get(RULES_WHITE_KEY)), lines(state.get(RULES_BLACK_KEY))));
    }

    public static NdvAccessRules rules() {
        return RULES;
    }

    private static void saveRules() {
        NdvAccessRules.Snapshot snapshot = RULES.snapshot();
        AppState state = AppState.shared();
        state.put(RULES_ALL_KEY, snapshot.blacklistAll());
        state.put(RULES_WHITE_KEY, String.join("\n", snapshot.whitelist()));
        state.put(RULES_BLACK_KEY, String.join("\n", snapshot.blacklist()));
    }

    public static List<String> searchOrder() {
        return lines(AppState.shared().get(SEARCH_ORDER_KEY));
    }

    public static void searchOrder(List<String> libraries) {
        AppState.shared().put(SEARCH_ORDER_KEY, String.join("\n", new NdvLibraryResolver(libraries, Map.of())
                .searchOrder()));
        changed();
    }

    public static Map<String, String> stepLibs() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : lines(AppState.shared().get(STEPLIB_KEY))) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                result.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return new NdvLibraryResolver(List.of(), result).stepLibs();
    }

    public static void stepLibs(Map<String, String> mappings) {
        List<String> lines = new ArrayList<>();
        new NdvLibraryResolver(List.of(), mappings).stepLibs().forEach((step, lib) -> lines.add(step + "=" + lib));
        AppState.shared().put(STEPLIB_KEY, String.join("\n", lines));
        changed();
    }

    public static NdvLibraryResolver resolver() {
        return new NdvLibraryResolver(searchOrder(), stepLibs());
    }

    /** Isolate cached content by endpoint, selected credential and authenticated principal. */
    public static NdvAnalysis analysis(NdvSession session) {
        String namespace = cacheNamespace(session.profile().label(), session.account(), session.user(), session.id());
        return ANALYSES.computeIfAbsent(namespace, key -> new NdvAnalysis(new NdvSourceCache(key,
                new FileNdvSourceStore(cacheFolder()), RULES)));
    }

    static String cacheNamespace(String endpoint, String account, String user, String sessionId) {
        String identity = endpoint.toLowerCase(java.util.Locale.ROOT) + "\u0000" + account
                + "\u0000" + user.toUpperCase(java.util.Locale.ROOT);
        if (account.isBlank() || user.isBlank()) {
            identity += "\u0000" + sessionId;
        }
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 fehlt", impossible);
        }
    }

    static Path cacheFolder() {
        String override = System.getProperty(CACHE_PROPERTY);
        return override == null || override.isBlank() ? AppSettingsLoader.defaultHome().resolve("ndv-cache")
                : Path.of(override);
    }

    /** Vergisst die Analysen (Tests). */
    static void forgetAnalyses() {
        ANALYSES.clear();
    }

    /** Der Anschluss an die globale Suche (Wissensquellen der App); ohne App {@link NdvSearchLink#NONE}. */
    public static NdvSearchLink searchLink() {
        return searchLink;
    }

    /** Setzt das Hauptfenster; geänderte Wissensquellen melden sich über {@link #observe(Runnable)}. */
    public static void searchLink(NdvSearchLink link) {
        searchLink = link == null ? NdvSearchLink.NONE : link;
        searchLink.observe(NdvAnalysisSettings::changed);
        changed();
    }

    public static void observe(Runnable listener) {
        LISTENERS.add(listener);
    }

    public static void unobserve(Runnable listener) {
        LISTENERS.remove(listener);
    }

    private static void changed() {
        LISTENERS.forEach(Runnable::run);
    }

    private static List<String> lines(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return value.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
    }
}
