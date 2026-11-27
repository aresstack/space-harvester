package com.aresstack.spaceharvester.app.chat.browser;

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

    /** Der Browser des Chats (ADR 0046): msedge (Vorgabe), chrome oder camoufox. */
    static final String CHANNEL_KEY = "browser.channel";
    /** Edge ist installiert und nutzt Proxy und Zertifikate des Hauses, ohne Download (ADR 0046). */
    public static final String DEFAULT_CHANNEL = "msedge";
    /** Die Browser, zwischen denen der Benutzer wählt, mit ihren Namen in der Oberfläche. */
    public static final Map<String, String> CHANNELS = channels();

    private static Map<String, String> channels() {
        Map<String, String> channels = new java.util.LinkedHashMap<>();
        channels.put("msedge", "Microsoft Edge");
        channels.put("chrome", "Google Chrome");
        channels.put("camoufox", "Camoufox (wird beim ersten Start heruntergeladen)");
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
