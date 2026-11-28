package com.aresstack.askai.browser.playwright;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The result URLs the search pages' own fields led to, per search page, kept by the host across browsers: the chat's
 * browser ends with each answer, the learnt address of an intranet portal's results must not end with it.
 */
public final class SearchUrlMemory {

    final Map<String, PlaywrightBrowserSession.SearchUrl> urls =
            new ConcurrentHashMap<String, PlaywrightBrowserSession.SearchUrl>();
}
