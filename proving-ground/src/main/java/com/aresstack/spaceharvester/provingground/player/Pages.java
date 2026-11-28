package com.aresstack.spaceharvester.provingground.player;

import com.microsoft.playwright.Page;

import java.util.HashMap;
import java.util.Map;

/**
 * The pages of a run by the page guid of the recording (tabs and popups), and the page the last step acted on (for
 * checks after the steps, e.g. a JavaScript expression). Shared by the test case and its preconditions.
 */
public final class Pages {

    private final Map<String, Page> byGuid = new HashMap<>();
    private Page current;

    public Page get(String pageGuid) {
        return byGuid.get(pageGuid);
    }

    public boolean has(String pageGuid) {
        return byGuid.containsKey(pageGuid);
    }

    public void put(String pageGuid, Page page) {
        byGuid.put(pageGuid, page);
    }

    /** The page the last step acted on, or null. */
    public Page current() {
        return current == null || current.isClosed() ? null : current;
    }

    void setCurrent(Page page) {
        current = page;
    }
}
