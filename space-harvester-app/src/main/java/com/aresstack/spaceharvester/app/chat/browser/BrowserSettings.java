package com.aresstack.spaceharvester.app.chat.browser;

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
 * ins aktive Profil ein.
 */
public final class BrowserSettings implements BrowserPolicyStore {

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
