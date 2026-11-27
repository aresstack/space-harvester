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

    /** A guard that admits every address. */
    static BrowserAccessGuard allowAll() {
        return (chat, url) -> { };
    }
}
