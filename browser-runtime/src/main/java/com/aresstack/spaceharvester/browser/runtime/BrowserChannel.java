package com.aresstack.spaceharvester.browser.runtime;

import java.util.Locale;

/** The browsers the runtime starts, with the channel names Playwright and the host's settings use. */
public enum BrowserChannel {
    /** The locally installed Mozilla Firefox, over WebDriver BiDi. */
    FIREFOX("firefox"),
    /** The pinned, verified Camoufox build (Firefox with fingerprint patches), over WebDriver BiDi. */
    CAMOUFOX("camoufox"),
    /** The locally installed Google Chrome, over the DevTools protocol. */
    CHROME("chrome"),
    /** The locally installed Microsoft Edge, over the DevTools protocol. */
    EDGE("msedge");

    /** Playwright's channel for a Firefox driven over WebDriver BiDi (installed Firefox and Camoufox). */
    static final String FIREFOX_BIDI_CHANNEL = "moz-firefox";

    private final String id;

    BrowserChannel(String id) {
        this.id = id;
    }

    /** The channel name in settings and for Playwright's Chromium channels. */
    public String id() {
        return id;
    }

    /** Whether Playwright drives it as Firefox over WebDriver BiDi (else Chromium over CDP). */
    public boolean firefoxFamily() {
        return this == FIREFOX || this == CAMOUFOX;
    }

    /** The channel Playwright's launch options take ({@code moz-firefox} for the Firefox family). */
    public String launchChannel() {
        return firefoxFamily() ? FIREFOX_BIDI_CHANNEL : id;
    }

    /** The channel of a settings value; an unknown or empty value fails readably. */
    public static BrowserChannel of(String id) {
        String wanted = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        for (BrowserChannel channel : values()) {
            if (channel.id.equals(wanted)) {
                return channel;
            }
        }
        throw new IllegalArgumentException("unknown browser channel '" + id + "'");
    }
}
