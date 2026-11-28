package com.aresstack.enterpriseai.browser.api;

import com.aresstack.enterpriseai.astu.chat.ChatConversationId;
import com.aresstack.enterpriseai.browser.api.engine.BrowserInstance;

import java.util.List;
import java.util.function.Predicate;

/**
 * The web search of the chat browsers. It searches in the chat's own browser (Angelo 28.11.2026: one browser, the
 * search as a tab): the engines are asked in tabs that close again after the search, the chat's tabs stay as they are.
 */
public interface WebSearchPort {

    /**
     * Searches {@code query} with the search engines the host set up for {@code chat} and answers the hits as text,
     * one block per hit: {@code id: title — address} and the snippet.
     *
     * @param browser the chat's running browser the search opens its tabs in
     * @param engines ids of the chat's engines for this one call, {@code null} for all of them
     * @param refused the addresses the chat's browser policy refuses (its block list): a search engine whose pages
     *                it refuses is not visited, a hit it refuses is left out
     */
    String search(ChatConversationId chat, BrowserInstance browser, String query, List<String> engines,
                  Predicate<String> refused) throws BrowserException;

    /** No web search: every search is refused with the reason. */
    WebSearchPort NONE = (chat, browser, query, engines, refused) -> {
        throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                "Für diesen Browser ist keine Websuche eingerichtet; die Suchseite der Website über "
                        + "browser_controls und browser_act nutzen");
    };
}
