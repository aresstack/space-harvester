package com.aresstack.spaceharvester.browser.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * What the host configures for the runtime: which browser, which program file, and where its data lies. Nothing of
 * it comes from a research request (research4j #1: the host chooses the browser and the folders).
 *
 * @param channel             the browser to start
 * @param executable          the program file the user chose, or empty for the standard install locations
 *                            (Firefox, Chrome, Edge; ignored for Camoufox)
 * @param dataDirectory       the host's data folder: profiles under {@code browser-profiles/<channel>}, start
 *                            failures under {@code logs}, the driver JARs under {@code playwright-driver}
 * @param camoufoxRoot        where Camoufox is (or is to be) installed
 * @param startTimeout        how long a browser start may take; zero = no limit: the start ends when the browser is
 *                            ready or its process ends (the user closed it, it crashed), never while it is still
 *                            starting
 * @param profileReleaseWait  how long a restart waits for the previous browser of the same profile to release it
 *                            before it falls back to a temporary profile
 * @param staleTemporaryAge   temporary profiles older than this are left-overs of a crash and are removed
 */
public record BrowserRuntimeConfig(BrowserChannel channel, String executable, Path dataDirectory, Path camoufoxRoot,
                                   Duration startTimeout, Duration profileReleaseWait, Duration staleTemporaryAge) {

    /** Where Camoufox lies when the host names no folder: {@code <data>/browser}. */
    public static final String DEFAULT_CAMOUFOX_FOLDER = "browser";
    /** The waits Ask AI used for its chat profiles (15 s release wait, one day for crash left-overs). */
    public static final Duration DEFAULT_PROFILE_RELEASE_WAIT = Duration.ofSeconds(15);
    public static final Duration DEFAULT_STALE_TEMPORARY_AGE = Duration.ofDays(1);

    public BrowserRuntimeConfig {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        executable = executable == null ? "" : executable.trim();
        camoufoxRoot = camoufoxRoot == null ? dataDirectory.resolve(DEFAULT_CAMOUFOX_FOLDER) : camoufoxRoot;
        startTimeout = startTimeout == null ? Duration.ZERO : startTimeout;
        profileReleaseWait = profileReleaseWait == null ? DEFAULT_PROFILE_RELEASE_WAIT : profileReleaseWait;
        staleTemporaryAge = staleTemporaryAge == null ? DEFAULT_STALE_TEMPORARY_AGE : staleTemporaryAge;
    }

    /** The configuration with the default folders and waits. */
    public static BrowserRuntimeConfig of(BrowserChannel channel, String executable, Path dataDirectory,
                                          Duration startTimeout) {
        return new BrowserRuntimeConfig(channel, executable, dataDirectory, null, startTimeout, null, null);
    }

    Path profilesDirectory() {
        return dataDirectory.resolve("browser-profiles").resolve(channel.id());
    }

    Path logDirectory() {
        return dataDirectory.resolve("logs");
    }
}
