package com.aresstack.spaceharvester.app.chat.browser;

import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection;
import com.aresstack.askai.browser.search.engine.EngineAcquisitionMode;
import com.aresstack.enterpriseai.browser.api.BrowserAccessPolicy;
import com.aresstack.enterpriseai.proasteion.application.browser.BrowserPolicyStore;
import com.aresstack.enterpriseai.proasteion.application.config.BrowserAccessConfig;
import com.aresstack.enterpriseai.proasteion.application.config.BrowserProfile;
import com.aresstack.spaceharvester.app.ui.AppState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Die Browserprofile der Websuche im Chat (Extern und Intern) wie im Enterprise AI Client: je Profil Modus
 * (standardmäßig blockieren mit Whitelist oder standardmäßig erlauben mit Blacklist), beide Regellisten und die
 * Such-URLs. Gespeichert im App-Zustand unter denselben Schlüsseln wie im Enterprise AI Client
 * ({@code browser.access.*}, {@code browser.search.urls}, {@code browser.access.internal.*},
 * {@code browser.search.internal.urls}). „Immer erlauben“ und „Immer verbieten“ der Rückfrage tragen den Host hier
 * ins aktive Profil ein; für ändernde Aktionen ({@code browser_act}) merken sie den Host in eigenen Listen.
 */
public final class BrowserSettings implements BrowserPolicyStore, ChangeApprovals.Memory {

    /** Such-URL des Profils Extern ohne Angabe, wie {@code browser-search-defaults.properties} im EAC. */
    public static final String DEFAULT_EXTERNAL_SEARCH = "https://html.duckduckgo.com/html/?q={query}";

    /** Wo die Werte liegen. */
    public interface Store {
        String get(String key);

        void putAll(Map<String, String> values);
    }

    private static BrowserSettings shared;

    private final Store store;

    public BrowserSettings(Store store) {
        this.store = store;
    }

    /** Die Einstellungen der laufenden App (App-Zustand). */
    public static synchronized BrowserSettings shared() {
        if (shared == null) {
            AppState state = AppState.shared();
            shared = new BrowserSettings(new Store() {
                @Override
                public String get(String key) {
                    return state.get(key);
                }

                @Override
                public void putAll(Map<String, String> values) {
                    state.putAll(values);
                }
            });
        }
        return shared;
    }

    /** Beide Profile; {@link BrowserAccessConfig#profile} wählt eines. */
    public synchronized BrowserAccessConfig load() {
        return profile(BrowserProfile.EXTERNAL, DEFAULT_EXTERNAL_SEARCH)
                .withInternal(profile(BrowserProfile.INTERNAL, BrowserAccessConfig.NONE));
    }

    private BrowserAccessConfig profile(BrowserProfile profile, String searchDefault) {
        String search = store.get(profile.searchKey());
        return new BrowserAccessConfig(BrowserAccessConfig.isAllowMode(store.get(profile.modeKey())),
                BrowserAccessConfig.split(store.get(profile.allowKey())),
                BrowserAccessConfig.split(store.get(profile.blockKey())),
                BrowserAccessConfig.splitUrls(search == null || search.isBlank() ? searchDefault : search), null);
    }

    /** Speichert ein Profil. */
    public synchronized void save(BrowserProfile profile, BrowserAccessConfig config) {
        Map<String, String> values = new HashMap<>();
        values.put(profile.modeKey(), config.allowByDefault() ? BrowserAccessConfig.MODE_ALLOW
                : BrowserAccessConfig.MODE_BLOCK);
        values.put(profile.allowKey(), BrowserAccessConfig.join(config.allow()));
        values.put(profile.blockKey(), BrowserAccessConfig.join(config.block()));
        values.put(profile.searchKey(), BrowserAccessConfig.joinUrls(config.searchUrls()));
        store.putAll(values);
    }

    /**
     * Die Suchmaschinen eines Profils für {@code browser_search} (Angelo 27.11.2026): die Suchmaschinenliste aus
     * Ask AI mit Prioritäten (gleiche Priorität = eine Gruppe), gespeichert in deren Form. Intern stehen hier die
     * Suchseiten des Hauses (Such-URL mit {@code {query}}), Extern Startpage und DuckDuckGo als Vorgabe.
     */
    static String enginesKey(BrowserProfile profile) {
        return profile == BrowserProfile.INTERNAL ? "browser.search.internal.engines" : "browser.search.engines";
    }

    /**
     * Die Suchmaschinen des Profils. Ohne gespeicherte Liste: Extern die Vorgabe aus Ask AI mit Startpage und
     * DuckDuckGo (die übrigen Zeilen aus); Such-URLs, die das Profil vorher hatte, kommen als Suchseiten dazu.
     */
    public synchronized BrowserSearchEngineSelection engines(BrowserProfile profile) {
        String stored = store.get(enginesKey(profile));
        if (stored != null) {
            return new BrowserSearchEngineSelection(BrowserSearchEngineSelection.parseEntries(stored),
                    EngineAcquisitionMode.FIRST_USABLE);
        }
        List<BrowserSearchEngineSelection.Entry> rows = new java.util.ArrayList<>();
        int priority = 0;
        if (profile == BrowserProfile.EXTERNAL) {
            for (BrowserSearchEngineSelection.Entry row : LegacyBrowserSearchDefaults.engineSelection().getEntries()) {
                boolean on = EXTERNAL_DEFAULT_ENGINES.contains(row.getEngineId());
                rows.add(new BrowserSearchEngineSelection.Entry(row.getEngineId(), on, row.getResultPages(),
                        row.getDelayMillis(), row.getPriority(), row.getLanguages(), row.getFurtherLanguages()));
                priority = Math.max(priority, row.getPriority() + 1);
            }
        }
        // Die bisherigen Such-URLs des Profils (Extern ohne die alte Vorgabe) bleiben als Suchseiten erhalten.
        String urls = store.get(profile.searchKey());
        for (String url : BrowserAccessConfig.splitUrls(urls == null ? "" : urls)) {
            if (BrowserSearchEngineCatalog.isSearchPage(url) && !DEFAULT_EXTERNAL_SEARCH.equals(url)) {
                rows.add(searchPage(url, priority));
            }
        }
        return new BrowserSearchEngineSelection(rows, EngineAcquisitionMode.FIRST_USABLE);
    }

    /** Die Suchmaschinen, die Extern ohne gespeicherte Liste sucht (Angelo 27.11.2026). */
    static final List<String> EXTERNAL_DEFAULT_ENGINES = List.of(BrowserSearchEngineCatalog.STARTPAGE,
            BrowserSearchEngineCatalog.DUCKDUCKGO);

    /** Eine Zeile für eine Suchseite des Hauses. */
    static BrowserSearchEngineSelection.Entry searchPage(String url, int priority) {
        return new BrowserSearchEngineSelection.Entry(url, true, BrowserSearchEngineSelection.Entry.DEFAULT_RESULT_PAGES,
                BrowserSearchEngineSelection.Entry.DEFAULT_DELAY_MILLIS, priority, List.of(), List.of());
    }

    /** Speichert die Suchmaschinen des Profils in der Form der Ask-AI-Liste. */
    public synchronized void saveEngines(BrowserProfile profile, BrowserSearchEngineSelection engines) {
        Map<String, String> values = new HashMap<>();
        values.put(enginesKey(profile), engines.encodeEntries());
        store.putAll(values);
    }

    /**
     * Die Adresse einer Suchseite des Hauses, wie der Benutzer sie eingibt: nur die Seite, etwa
     * {@code https://portal.example/} (wie dort gesucht wird, findet die Suche selbst über ihr Suchfeld), ohne
     * Schema mit {@code https://} davor; {@code null} für eine leere Eingabe.
     */
    public static String searchPageAddress(String typed) {
        if (typed == null || typed.isBlank()) {
            return null;
        }
        String address = typed.strip();
        String lower = address.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") ? address : "https://" + address;
    }

    /**
     * Nimmt eine Suchseite in die Liste des Profils auf, mit Priorität 0 wie die übrigen Suchseiten: Weiß man nicht,
     * wo etwas steht, werden alle zusammen durchsucht.
     */
    public synchronized void addSearchPage(BrowserProfile profile, String url) {
        List<BrowserSearchEngineSelection.Entry> rows = new java.util.ArrayList<>(engines(profile).getEntries());
        for (BrowserSearchEngineSelection.Entry row : rows) {
            if (row.getEngineId().equals(url)) {
                return;
            }
        }
        rows.add(searchPage(url, 0));
        saveEngines(profile, new BrowserSearchEngineSelection(rows, EngineAcquisitionMode.FIRST_USABLE));
    }

    /** Ob das Fenster des Google-Suchbrowsers sichtbar ist (Vorgabe: ja, wie das eigene Suchfenster in Ask AI). */
    static final String SEARCH_WINDOW_KEY = "browser.search.window";

    /** Ob der Suchbrowser ein sichtbares Fenster hat; gilt ab seinem nächsten Start. */
    public synchronized boolean searchWindowShown() {
        return !"hidden".equals(store.get(SEARCH_WINDOW_KEY));
    }

    /** Speichert, ob der Suchbrowser ein sichtbares Fenster hat. */
    public synchronized void saveSearchWindowShown(boolean shown) {
        Map<String, String> values = new HashMap<>();
        values.put(SEARCH_WINDOW_KEY, shown ? "shown" : "hidden");
        store.putAll(values);
    }

    /** Der Browser des Chats (ADR 0046, research4j#1): firefox (Vorgabe), msedge, chrome oder camoufox. */
    static final String CHANNEL_KEY = "browser.channel";
    /**
     * Der installierte Firefox (research4j#1, Angelos Auftrag vom 27.11.2026, Schritt 8): ohne Zusatzinstallation,
     * über WebDriver BiDi. Edge und Chrome bleiben wählbar, sind aber nie die Vorgabe.
     */
    public static final String DEFAULT_CHANNEL = "firefox";
    /** Die Browser, zwischen denen der Benutzer wählt, mit ihren Namen in der Oberfläche. */
    public static final Map<String, String> CHANNELS = channels();

    private static Map<String, String> channels() {
        Map<String, String> channels = new java.util.LinkedHashMap<>();
        channels.put("firefox", "Mozilla Firefox");
        channels.put("msedge", "Microsoft Edge");
        channels.put("chrome", "Google Chrome");
        channels.put("camoufox", "Camoufox");
        return java.util.Collections.unmodifiableMap(channels);
    }

    /** Der gewählte Browser des Chats. */
    public synchronized String channel() {
        String channel = store.get(CHANNEL_KEY);
        return channel == null || !CHANNELS.containsKey(channel) ? DEFAULT_CHANNEL : channel;
    }

    /** Speichert den Browser des Chats; gilt für den nächsten Start des Browsers. */
    public synchronized void saveChannel(String channel) {
        Map<String, String> values = new HashMap<>();
        values.put(CHANNEL_KEY, CHANNELS.containsKey(channel) ? channel : DEFAULT_CHANNEL);
        store.putAll(values);
    }

    /** Die gewählte Programmdatei je Browser (research4j#1: der Host wählt sie); leer = die üblichen Installationsorte. */
    static final String EXECUTABLE_KEY = "browser.executable.";

    /** Die gewählte Programmdatei des Browsers {@code channel}, oder "". */
    public synchronized String executable(String channel) {
        String path = store.get(EXECUTABLE_KEY + channel);
        return path == null ? "" : path.trim();
    }

    /** Speichert die Programmdatei des Browsers {@code channel} ("" = übliche Orte). */
    public synchronized void saveExecutable(String channel, String path) {
        Map<String, String> values = new HashMap<>();
        values.put(EXECUTABLE_KEY + channel, path == null ? "" : path.trim());
        store.putAll(values);
    }

    /** Hosts, auf denen der Chat ändernde Browser-Aktionen ohne Rückfrage ausführt („Immer erlauben“). */
    static final String CHANGE_ALLOW_KEY = "browser.act.change.allow";
    /** Hosts, auf denen der Chat ändernde Browser-Aktionen nie ausführt („Immer verbieten“). */
    static final String CHANGE_BLOCK_KEY = "browser.act.change.block";

    /**
     * Die gemerkten Antworten auf die Rückfrage vor ändernden Aktionen ({@code browser_act}, Aktion {@code change}),
     * je Website (Angelo 24.11.2026): Regeln wie Whitelist und Blacklist, Verbieten gewinnt, sonst wird gefragt.
     */
    @Override
    public synchronized BrowserAccessPolicy changes() {
        return new BrowserAccessPolicy(BrowserAccessPolicy.Mode.BLOCK_BY_DEFAULT,
                BrowserAccessConfig.split(store.get(CHANGE_ALLOW_KEY)),
                BrowserAccessConfig.split(store.get(CHANGE_BLOCK_KEY)));
    }

    /** „Immer erlauben“: ändernde Aktionen auf diesem Host ohne Rückfrage. */
    @Override
    public void allowChanges(String host) {
        add(CHANGE_ALLOW_KEY, host);
    }

    /** „Immer verbieten“: ändernde Aktionen auf diesem Host nie. */
    @Override
    public void blockChanges(String host) {
        add(CHANGE_BLOCK_KEY, host);
    }

    /** Speichert beide Listen der ändernden Aktionen (Einstellungen › Browser). */
    public synchronized void saveChanges(List<String> allow, List<String> block) {
        Map<String, String> values = new HashMap<>();
        values.put(CHANGE_ALLOW_KEY, BrowserAccessConfig.join(allow));
        values.put(CHANGE_BLOCK_KEY, BrowserAccessConfig.join(block));
        store.putAll(values);
    }

    @Override
    public void allow(BrowserProfile profile, String host) {
        add(profile.allowKey(), host);
    }

    @Override
    public void block(BrowserProfile profile, String host) {
        add(profile.blockKey(), host);
    }

    private synchronized void add(String key, String host) {
        if (host == null || host.isBlank()) {
            return;
        }
        List<String> rules = BrowserAccessConfig.split(store.get(key));
        if (!rules.contains(host)) {
            rules.add(host);
            Map<String, String> values = new HashMap<>();
            values.put(key, BrowserAccessConfig.join(rules));
            store.putAll(values);
        }
    }
}
