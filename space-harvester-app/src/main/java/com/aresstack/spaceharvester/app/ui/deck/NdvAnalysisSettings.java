package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.deck.ndv.analysis.NdvAccessRules;
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

    /** Zwischenspeicher und Analyse eines Servers ({@code host:port}); bleiben für die Laufzeit der App. */
    public static NdvAnalysis analysis(String server) {
        return ANALYSES.computeIfAbsent(server, key -> new NdvAnalysis(new NdvSourceCache(key,
                new FileNdvSourceStore(cacheFolder()), RULES)));
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
