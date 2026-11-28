package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.enterpriseai.browser.api.engine.BrowserInstance;

import java.util.List;
import java.util.Map;

/**
 * The research browser's {@code web_*} search tools in a chat's own browser (Angelo 28.11.2026: one browser, the
 * search as a tab): the engines are asked in tabs of the chat's browser that close again after the search, and the
 * chat's tab stays where it was. Every call runs on the browser's owner thread, like the chat's own calls.
 */
public final class ChatBrowserSearch {

    private final BrowserToolSet tools;

    private ChatBrowserSearch(BrowserToolSet tools) {
        this.tools = tools;
    }

    /**
     * The search tools in {@code browser} with {@code settings} (the chat's engine table) and the host's {@code memory}
     * of search pages' result URLs; {@code null} when {@code browser} is not a browser of {@link PlaywrightChatEngine}.
     */
    public static ChatBrowserSearch in(BrowserInstance browser, final LegacyBrowserSearchSettings settings,
                                       final SearchUrlMemory memory) throws BrowserException {
        if (!(browser instanceof PlaywrightChatEngine.Instance)) {
            return null;
        }
        BrowserSessionActor actor = ((PlaywrightChatEngine.Instance) browser).actor;
        actor.onPlaywrightSession(new BrowserSessionActor.PlaywrightSessionTask<Void>() {
            public Void run(PlaywrightBrowserSession session) {
                session.useSearchSettings(settings, memory);
                return null;
            }
        });
        return new ChatBrowserSearch(new BrowserToolSet(actor, PlaywrightBrowserRuntime.repairToolsFor(actor,
                settings)));
    }

    /** Runs one {@code web_*} tool in the chat's browser and answers its text. */
    public String call(String tool, Map<String, Object> arguments) throws BrowserException {
        return tools.call(tool, arguments);
    }

    /** The tool names, as {@link PlaywrightBrowserRuntime#toolNames()}. */
    public static List<String> toolNames() {
        return BrowserToolSet.TOOL_NAMES;
    }
}
