package com.aresstack.enterpriseai.browser.api;

import com.aresstack.enterpriseai.astu.chat.ChatConversationId;

import java.util.List;
import java.util.function.Predicate;

/**
 * The web search of the chat browsers. It searches in a browser of its own (the search browser), never in a chat's
 * tabs: the chat's browser only opens the hits afterwards.
 */
public interface WebSearchPort {

    /**
     * Searches {@code query} with the search engines the host set up for {@code chat} and answers the hits as text,
     * one block per hit: {@code id: title — address} and the snippet.
     *
     * @param engines ids of the chat's engines for this one call, {@code null} for all of them
     * @param refused the addresses the chat's browser policy refuses (its block list): a search engine whose pages
     *                it refuses is not visited, a hit it refuses is left out
     */
    String search(ChatConversationId chat, String query, List<String> engines, Predicate<String> refused)
            throws BrowserException;

    /** No web search: every search is refused with the reason. */
    WebSearchPort NONE = (chat, query, engines, refused) -> {
        throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                "Für diesen Browser ist keine Websuche eingerichtet; die Suchseite der Website über "
                        + "browser_controls und browser_act nutzen");
    };
}
