package com.aresstack.spaceharvester.browser.runtime;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Playwright;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One browser the runtime started, as the original Playwright objects. Whoever asked for it owns it and ends it with
 * {@link #close()} (or {@link #abandon()} from a stuck owner); the runtime closes the browser, its driver process and
 * its profile, and deletes what was temporary.
 */
public final class RunningBrowser implements AutoCloseable {

    private final BrowserChannel channel;
    private final Playwright playwright;
    private final Browser browser;
    private final BrowserContext context;
    private final ProfileLease profile;
    private final Path temporaryFiles;
    private final String addonFailure;
    private final boolean addonLoaded;
    private final AtomicBoolean ended = new AtomicBoolean();

    RunningBrowser(BrowserChannel channel, Playwright playwright, Browser browser, BrowserContext context,
                   ProfileLease profile, Path temporaryFiles, boolean addonLoaded, String addonFailure) {
        this.channel = channel;
        this.playwright = playwright;
        this.browser = browser;
        this.context = context;
        this.profile = profile;
        this.temporaryFiles = temporaryFiles;
        this.addonLoaded = addonLoaded;
        this.addonFailure = addonFailure;
    }

    public BrowserChannel channel() {
        return channel;
    }

    public Playwright playwright() {
        return playwright;
    }

    /** The browser process; for a persistent context Playwright may report none ({@code null}). */
    public Browser browser() {
        return browser;
    }

    /** The profile's context of a persistent start; {@code null} for an ephemeral one (contexts come from the caller). */
    public BrowserContext context() {
        return context;
    }

    /**
     * A new context for a test (its own cookies and storage, like {@code browser.newContext()}) whose first page
     * closes the start window of this browser: Firefox opens a window on its own at every start, and Playwright's
     * {@code newPage()} always opens another one. A persistent start ({@link BrowserRuntime#forTestRun}) makes that
     * start window a page of {@link #context()}; it is closed as soon as the test's window is open, so exactly one
     * window stays. For an ephemeral start (no profile context) it is a plain {@code browser.newContext()}.
     */
    public BrowserContext newTestContext() {
        return newTestContext(null);
    }

    /** As {@link #newTestContext()}, with Playwright's context options (viewport, locale ...; null = its defaults). */
    public BrowserContext newTestContext(Browser.NewContextOptions options) {
        BrowserContext test = browser.newContext(options);
        if (context != null) {
            AtomicBoolean done = new AtomicBoolean();
            test.onPage(page -> {
                if (done.compareAndSet(false, true)) {
                    closeStartWindow();
                }
            });
        }
        return test;
    }

    /** Closes the pages of the profile context of a persistent start (its start window); nothing for an ephemeral one. */
    public void closeStartWindow() {
        if (context == null) {
            return;
        }
        for (com.microsoft.playwright.Page start : java.util.List.copyOf(context.pages())) {
            if (!start.isClosed()) {
                start.close();
            }
        }
    }

    /** Whether an add-on handed to the start is loaded. */
    public boolean addonLoaded() {
        return addonLoaded;
    }

    /** Why the add-on handed to the start is not loaded, or {@code null} (loaded, or none was asked for). */
    public String addonFailure() {
        return addonFailure;
    }

    /** The profile folder of a persistent start, else {@code null}. */
    public Path profileDirectory() {
        return profile == null ? null : profile.directory();
    }

    /** Orderly end: context, browser, driver process, then the profile and temporary files. Idempotent. */
    @Override
    public void close() {
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        closeQuietly(context, browser, playwright);
        release(profile, temporaryFiles);
    }

    /**
     * Emergency end from a stuck owner: cuts the driver connection on a daemon thread (Playwright's close waits,
     * bounded, for the driver process) and releases the profile, without closing context or browser over the stuck
     * connection. The browser window of a cut connection may survive as an orphan; {@link #close()} is the orderly
     * path. Returns at once.
     */
    public void abandon() {
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        Thread cutter = new Thread(() -> {
            closeQuietly(null, null, playwright);
            release(profile, temporaryFiles); // best effort: an orphaned browser may still hold its profile
        }, "browser-runtime-abandon");
        cutter.setDaemon(true);
        cutter.start();
    }

    static void closeQuietly(BrowserContext context, Browser browser, Playwright playwright) {
        if (context != null) {
            try {
                context.close();
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
        if (browser != null) {
            try {
                browser.close();
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
        if (playwright != null) {
            try {
                playwright.close();
            } catch (RuntimeException ignored) {
                // the driver process already ended
            }
        }
    }

    private static void release(ProfileLease profile, Path temporaryFiles) {
        if (profile != null) {
            try {
                profile.close();
            } catch (RuntimeException ignored) {
                // a profile still held by an orphaned browser stays on disk
            }
        }
        ProfileLease.deleteRecursively(temporaryFiles);
    }
}
