/**
 * Implements {@link com.aresstack.enterpriseai.browser.api.ChatBrowserPort} over any
 * {@link com.aresstack.enterpriseai.browser.api.engine.BrowserEngine}.
 *
 * <p>Pattern from askai-java8 (arch) {@code LazyRestartableBrowserRuntime} (lazy start, single owner, one bounded
 * retry), changed for #141: the owner is the chat, stopping ends process and connection but never the profile, and
 * a closed browser behind a live connection owner is recovered explicitly.
 */
package com.aresstack.enterpriseai.browser.runtime;
