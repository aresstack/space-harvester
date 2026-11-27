package com.aresstack.enterpriseai.browser.api;

import com.aresstack.enterpriseai.astu.chat.ChatConversationId;

/**
 * Decides whether a chat browser may open an address. Called before every navigation and again with the
 * final address after redirects. An implementation may block while it asks the user.
 */
public interface BrowserAccessGuard {

    /**
     * Returns normally when the address may be opened.
     *
     * @throws BrowserException of kind {@link BrowserException.Kind#BLOCKED} when it must not be opened
     */
    void admit(ChatConversationId chat, String url) throws BrowserException;

    /**
     * Whether the block list of the chat's policy refuses {@code url}, without asking anyone. Used where the chat
     * only sees addresses (search hits, the search engines a search visits): what is refused is left out, an
     * unknown address is asked about only when it is opened ({@link #admit}).
     */
    default boolean refuses(ChatConversationId chat, String url) {
        return false;
    }

    /** A guard that admits every address. */
    static BrowserAccessGuard allowAll() {
        return (chat, url) -> { };
    }
}
