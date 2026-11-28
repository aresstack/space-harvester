package com.aresstack.spaceharvester.app.chat.browser;

import com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection;
import com.aresstack.enterpriseai.astu.chat.ChatConversationId;
import com.aresstack.enterpriseai.browser.api.ChatBrowser;
import com.aresstack.enterpriseai.browser.api.ChatBrowserPort;
import com.aresstack.enterpriseai.proasteion.application.browser.DelegatingBrowserAccessGuard;
import com.aresstack.enterpriseai.proasteion.application.config.BrowserProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Suchmaschinen eines Chats nach seinem Profil und die Rückfrage bei der ersten internen Suche. */
class ChatWebBrowserSearchEnginesTest {

    private final Map<String, String> values = new HashMap<>();
    private final BrowserSettings settings = new BrowserSettings(new BrowserSettings.Store() {
        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public void putAll(Map<String, String> changes) {
            values.putAll(changes);
        }
    });
    private final ChatWebBrowser browser = new ChatWebBrowser(new NoBrowsers(), new DelegatingBrowserAccessGuard(),
            settings);
    private final List<String> asked = new ArrayList<>();

    @Test
    void externSearchesItsEnginesWithoutAsking() {
        browser.searchPageQuestion(() -> {
            asked.add("gefragt");
            return null;
        });

        assertThat(browser.engines(new ChatConversationId("chat-1")).resolvedEnabledEngines()).isNotEmpty();
        assertThat(asked).isEmpty();
    }

    @Test
    void theFirstInternalSearchAsksOnceForTheAddressOfThePageAndKeepsIt() {
        browser.profile("chat-1", true);
        browser.searchPageQuestion(() -> {
            asked.add("gefragt");
            return "https://mapz.example/";
        });

        BrowserSearchEngineSelection engines = browser.engines(new ChatConversationId("chat-1"));

        assertThat(engines.getEntries()).extracting(BrowserSearchEngineSelection.Entry::getEngineId)
                .as("nur die Adresse, ohne {query} und ohne Ablehnung").containsExactly("https://mapz.example/");
        assertThat(asked).hasSize(1);

        browser.engines(new ChatConversationId("chat-1"));
        assertThat(asked).as("eingetragen: keine zweite Frage").hasSize(1);
    }

    @Test
    void cancellingTheQuestionSearchesNothingAndStoresNothing() {
        browser.profile("chat-1", true);
        browser.searchPageQuestion(() -> null);

        assertThat(browser.engines(new ChatConversationId("chat-1")).getEntries()).isEmpty();
        assertThat(values).doesNotContainKey("browser.search.internal.engines");
    }

    @Test
    void theQuestionAsksOnlyForThePage() {
        assertThat(ChatWebBrowser.INTERNAL_SEARCH_HINT).isEqualTo("Auf welcher Seite soll intern gesucht werden?");
    }

    /** Kein Browser: die Suchmaschinenwahl braucht keinen. */
    private static final class NoBrowsers implements ChatBrowserPort {
        @Override
        public ChatBrowser browserOf(ChatConversationId conversation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void release(ChatConversationId conversation) {
        }

        @Override
        public void deleteProfile(ChatConversationId conversation) {
        }

        @Override
        public void releaseAll() {
        }
    }
}
