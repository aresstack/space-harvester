package com.aresstack.enterpriseai.browser.api;

import com.aresstack.enterpriseai.astu.chat.ChatConversationId;

/**
 * The browsers of all chats. Each chat owns one persistent profile; browser process and connection are exchangeable
 * runtime resources that are started lazily on the first call and recovered after a crash or manual close.
 */
public interface ChatBrowserPort {

    /** The browser of this chat. Starts nothing yet: the process starts on the first operation. */
    ChatBrowser browserOf(ChatConversationId conversation);

    /** Closes connection and process of this chat, keeps its profile (closing a chat is not deleting it). */
    void release(ChatConversationId conversation);

    /** Releases the chat and deletes its profile folder (cookies, storage); used when the chat is deleted. */
    void deleteProfile(ChatConversationId conversation) throws BrowserException;

    /** Releases every chat on application shutdown; profiles stay. */
    void releaseAll();
}
