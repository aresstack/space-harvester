package com.aresstack.enterpriseai.browser.firefox;

import com.aresstack.enterpriseai.astu.chat.ChatConversationId;
import com.aresstack.enterpriseai.browser.api.BrowserPage;
import com.aresstack.enterpriseai.browser.api.BrowserTab;
import com.aresstack.enterpriseai.browser.api.ChatBrowser;
import com.aresstack.enterpriseai.browser.runtime.BrowserProfileStore;
import com.aresstack.enterpriseai.browser.runtime.ChatBrowserRuntime;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Two chats with two real, visible Firefox instances at the same time (#141). Needs a desktop session, so it runs
 * only on request: {@code ./gradlew :browser-firefox:test -Denterpriseai.firefox.it=true} (Firefox from
 * {@code -Denterpriseai.firefox} or the usual install location); skipped otherwise.
 */
public class TwoFirefoxChatsIT {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ChatBrowserRuntime runtime;

    @Before
    public void setUp() {
        Assume.assumeTrue("Firefox integration test not requested", Boolean.getBoolean("enterpriseai.firefox.it"));
        Path firefox = FirefoxEngine.locate(System.getProperty("enterpriseai.firefox"), System.getenv(),
                System.getProperty("os.name"));
        Assume.assumeTrue("Firefox not installed", firefox != null);
        runtime = new ChatBrowserRuntime(new FirefoxEngine(firefox, 60000L),
                new BrowserProfileStore(folder.getRoot().toPath()));
    }

    @After
    public void tearDown() {
        if (runtime != null) {
            runtime.releaseAll();
        }
    }

    @Test
    public void twoChatsRunSeparateBrowsersAtTheSameTime() throws Exception {
        ChatBrowser a = runtime.browserOf(new ChatConversationId("chat-a"));
        ChatBrowser b = runtime.browserOf(new ChatConversationId("chat-b"));
        int tabsOfA = a.tabs().size();

        a.newTab("about:blank#a");
        BrowserPage pageOfB = b.navigate(null, "about:blank#b");

        assertEquals(tabsOfA + 1, a.tabs().size());
        assertEquals("about:blank#b", pageOfB.url());
        for (BrowserTab tab : b.tabs()) {
            assertEquals(false, tab.url().endsWith("#a"));
        }
    }

    @Test
    public void reconnectAfterReleaseKeepsTheProfile() throws Exception {
        ChatConversationId chat = new ChatConversationId("chat-c");
        List<BrowserTab> before = runtime.browserOf(chat).tabs();
        runtime.release(chat);
        List<BrowserTab> after = runtime.browserOf(chat).tabs();
        assertEquals(before.isEmpty(), after.isEmpty());
    }
}
