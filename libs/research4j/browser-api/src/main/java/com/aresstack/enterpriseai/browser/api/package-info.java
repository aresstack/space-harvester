/**
 * Port for a persistent browser that belongs to one chat (#141).
 *
 * <p>{@link com.aresstack.enterpriseai.browser.api.ChatBrowserPort} hands out the
 * {@link com.aresstack.enterpriseai.browser.api.ChatBrowser} of a chat. The chat identity always comes from the host
 * (chat turn or agent endpoint), never from model arguments. Tab ids are chat-local and stable while the browser
 * runs; raw protocol handles (BiDi context ids, ports, profile paths) never leave the adapter.
 *
 * <p>Terminology: a browser <em>profile</em> is the persistent folder of one chat; a BiDi {@code userContext} is a
 * cookie/storage context inside a running browser; a BiDi {@code browsingContext} is a tab or frame; a BiDi
 * {@code sessionId} is one automation session. They are different things and must not be mixed up.
 */
package com.aresstack.enterpriseai.browser.api;
