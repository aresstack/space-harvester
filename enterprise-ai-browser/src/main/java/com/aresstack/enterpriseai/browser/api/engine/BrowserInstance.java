package com.aresstack.enterpriseai.browser.api.engine;

import com.aresstack.enterpriseai.browser.api.BrowserException;

/** One running browser process of one chat profile, with its loopback automation endpoint. */
public interface BrowserInstance {

    /** Is the process still running? The process, not the connection: a closed window ends the process. */
    boolean isAlive();

    /**
     * Opens a new automation connection to this process (also after a lost connection). Must not start a process
     * and must not open a tab.
     */
    BrowserConnection connect() throws BrowserException;

    /** Ends the process; the profile folder stays untouched. */
    void stop();
}
