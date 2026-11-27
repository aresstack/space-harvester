package com.aresstack.spaceharvester.provingground.run;

import com.aresstack.spaceharvester.provingground.model.TestUser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Tracing;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The browser contexts of one test case, one per user, opened when a step of that user first needs one. Each is traced
 * when an output directory is set; closing writes one trace per user.
 */
final class UserSessions implements AutoCloseable {

    private static final String NO_USER = "ohne-benutzer";

    private final ContextFactory factory;
    private final long timeoutMillis;
    private final Path traceDirectory;
    private final String title;
    private final Map<String, BrowserContext> contexts = new LinkedHashMap<>();
    private final List<Path> traces = new ArrayList<>();

    UserSessions(ContextFactory factory, Duration actionTimeout, Path traceDirectory, String title) {
        this.factory = factory;
        this.timeoutMillis = actionTimeout.toMillis();
        this.traceDirectory = traceDirectory;
        this.title = title;
    }

    BrowserContext context(TestUser user) {
        String key = user == null ? NO_USER : user.name();
        BrowserContext context = contexts.get(key);
        if (context == null) {
            context = factory.open(user);
            if (timeoutMillis > 0) {
                context.setDefaultTimeout(timeoutMillis);
            }
            if (traceDirectory != null) {
                context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true)
                        .setTitle(title + " · " + key));
            }
            contexts.put(key, context);
        }
        return context;
    }

    /** The traces written by {@link #close()}. */
    List<Path> traces() {
        return List.copyOf(traces);
    }

    @Override
    public void close() {
        for (Map.Entry<String, BrowserContext> entry : contexts.entrySet()) {
            BrowserContext context = entry.getValue();
            try {
                if (traceDirectory != null) {
                    Path trace = traceDirectory.resolve(safe(entry.getKey()) + ".zip");
                    context.tracing().stop(new Tracing.StopOptions().setPath(trace));
                    traces.add(trace);
                }
            } catch (PlaywrightException notWritten) {
                // the browser is gone; the result says what happened
            }
            try {
                context.close();
            } catch (PlaywrightException alreadyClosed) {
                // nothing left to close
            }
        }
        contexts.clear();
    }

    static String safe(String name) {
        String cleaned = name.replaceAll("[^\\p{L}\\p{N}_.-]+", "_");
        return cleaned.isEmpty() ? "_" : cleaned;
    }
}
