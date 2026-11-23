package com.aresstack.enterpriseai.browser.api.engine;

import com.aresstack.enterpriseai.browser.api.BrowserException;

import java.nio.file.Path;

/** Starts a visible browser on a persistent profile folder, bound to loopback on a free port. */
public interface BrowserEngine {

    /**
     * Starts the browser normally (not {@code -silent}) on {@code profileDirectory} and waits until its automation
     * endpoint is really ready.
     *
     * @throws BrowserException {@code START_FAILED} or {@code TIMEOUT}
     */
    BrowserInstance start(Path profileDirectory) throws BrowserException;
}
