package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserBackendKind;
import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLink;
import com.aresstack.askai.browser.BrowserPageReadiness;
import com.aresstack.askai.browser.BrowserPageSnapshot;
import com.aresstack.askai.browser.BrowserSession;
import com.aresstack.askai.browser.WebSearchResult;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The single-owner execution model for the Playwright-backed {@link BrowserSession}. Playwright Java is not
 * thread-safe and dispatches its events (route interception, exposeBinding, popup close) ONLY while the
 * owning thread is inside a Playwright call. Both contracts are enforced here: the underlying session —
 * including Playwright/Browser/Context/Page themselves — is CREATED on the dedicated owner thread and only
 * ever touched there; MCP HTTP workers enqueue commands and block on the result.
 *
 * <p>While no command is queued, the owner thread does not sleep — it pumps the Playwright event loop
 * ({@link PlaywrightDriver#pumpEvents}) so that a user-driven navigation still gets its
 * {@code route.resume()}, a HUD button click still reaches {@code exposeBinding}, and popups are still
 * closed, even when the research runtime is busy elsewhere or the run is already over. Without the pump,
 * the request interception freezes the visible browser the moment the browser runtime goes idle.</p>
 *
 * <p>Event callbacks run on the owner thread during the pump; they must never enqueue a command and wait
 * for it (self-deadlock) — the HUD binding only appends to a thread-safe buffer, the route handler only
 * tests a predicate. {@link #call} executes directly when already on the owner thread for the same reason.</p>
 */
final class BrowserSessionActor implements BrowserSession, EngineGroupSession {

    /** A unit of work executed on the owner thread against the real session. */
    interface SessionTask<T> {
        T run(BrowserSession session) throws BrowserException;
    }

    /** A unit of work that needs the Playwright-backed session specifically (repair capture, dev modes). */
    interface PlaywrightSessionTask<T> {
        T run(PlaywrightBrowserSession session) throws BrowserException;
    }

    static final String OWNER_THREAD_NAME = "playwright-owner";

    /**
     * Upper bound of one idle pump round. The wake condition (a queued command) is also checked inside the
     * pump, so this is a latency ceiling for the unusual case, not the common one.
     */
    private static final long PUMP_SLICE_MILLIS = 50L;

    private final Thread ownerThread;
    private final BrowserSession underlying;
    /** The Playwright-backed session when this actor fronts one, else null (e.g. UnavailableSession). */
    private final PlaywrightBrowserSession playwrightBacked;
    /** Control plane: the HUD inbox is drained DIRECTLY, never through the command queue (see below). */
    private final HudCommandInbox hudInbox;
    private final BlockingQueue<Command<?>> queue = new LinkedBlockingQueue<Command<?>>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final BooleanSupplier wake = new BooleanSupplier() {
        public boolean getAsBoolean() {
            return !queue.isEmpty();
        }
    };

    private BrowserSessionActor(Thread ownerThread, BrowserSession underlying) {
        this.ownerThread = ownerThread;
        this.underlying = underlying;
        this.playwrightBacked = underlying instanceof PlaywrightBrowserSession
                ? (PlaywrightBrowserSession) underlying : null;
        this.hudInbox = playwrightBacked == null ? null : playwrightBacked.hudInbox();
    }

    /**
     * Spawn the owner thread, run {@code factory} ON it (Playwright and every object below it must be
     * created on the thread that will use them), and return once the session exists. A factory failure is
     * rethrown here and the thread ends.
     */
    static BrowserSessionActor start(final Supplier<BrowserSession> factory) {
        final CompletableFuture<BrowserSessionActor> created =
                new CompletableFuture<BrowserSessionActor>();
        Thread thread = new Thread(new Runnable() {
            public void run() {
                BrowserSessionActor actor;
                try {
                    actor = new BrowserSessionActor(Thread.currentThread(), factory.get());
                } catch (RuntimeException ex) {
                    created.completeExceptionally(ex);
                    return;
                }
                created.complete(actor);
                actor.runLoop();
            }
        }, OWNER_THREAD_NAME);
        thread.setDaemon(true);
        thread.start();
        try {
            return created.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting the browser owner thread.", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("Browser session creation failed.", cause);
        }
    }

    private void runLoop() {
        while (running.get() || !queue.isEmpty()) {
            Command<?> command = queue.poll();
            if (command != null) {
                command.execute(underlying);
                continue;
            }
            boolean pumped = playwrightBacked != null
                    && playwrightBacked.pumpEvents(wake, PUMP_SLICE_MILLIS);
            if (!pumped) {
                // No event loop to keep alive (unavailable backend / driver closed): plain bounded wait.
                try {
                    command = queue.poll(PUMP_SLICE_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (command != null) {
                    command.execute(underlying);
                }
            }
        }
        // Fail leftovers readably instead of letting a racing caller wait forever on its future.
        Command<?> leftover;
        while ((leftover = queue.poll()) != null) {
            leftover.fail(new BrowserException("Browser session is closed."));
        }
    }

    /** Run {@code task} on the owner thread and return its result; the calling thread blocks meanwhile. */
    <T> T call(SessionTask<T> task) throws BrowserException {
        if (Thread.currentThread() == ownerThread) {
            return task.run(underlying); // already on the owner (event callback / nested use) — no deadlock
        }
        if (!running.get()) {
            throw new BrowserException("Browser session is closed.");
        }
        Command<T> command = new Command<T>(task);
        queue.add(command);
        try {
            return command.result.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BrowserException("Interrupted while waiting for the browser owner thread.");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof BrowserException) {
                throw (BrowserException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new BrowserException("Browser call failed: " + cause);
        }
    }

    /** Whether this actor fronts the real Playwright session (repair tools, dev domain-key mode). */
    boolean isPlaywrightBacked() {
        return playwrightBacked != null;
    }

    /** Run a task that needs the Playwright-backed session, on the owner thread. */
    <T> T onPlaywrightSession(final PlaywrightSessionTask<T> task) throws BrowserException {
        if (playwrightBacked == null) {
            throw new BrowserException("Playwright backend unavailable.");
        }
        return call(new SessionTask<T>() {
            public T run(BrowserSession session) throws BrowserException {
                return task.run(playwrightBacked);
            }
        });
    }

    /**
     * One web_search_prepare for one priority group, ENTIRELY on the owner thread: the group's engines are the
     * session's per-call state, so they are set, used and cleared on the one thread that owns the session, and the
     * call's own browser work runs there directly ({@link #call} on the owner thread). Live run 4 (2026-11-12): the
     * research browser's tool set holds this actor, not the session, and refused every group search with "This
     * browser backend does not take engines for web_search_prepare" once Google was paused after a challenge.
     */
    @Override
    public <T> T withEngineGroup(final List<String> engineIds, final PlaywrightBrowserSession.BrowserCall<T> call)
            throws BrowserException {
        return onPlaywrightSession(new PlaywrightSessionTask<T>() {
            public T run(PlaywrightBrowserSession session) throws BrowserException {
                return session.withEngineGroup(engineIds, call);
            }
        });
    }

    // ------------------------------------------------------------------ BrowserSession (all via owner)

    public BrowserBackendKind getBackendKind() {
        return underlying.getBackendKind(); // constant getter, touches no Playwright state
    }

    public WebSearchResult search(final String query) throws BrowserException {
        return call(new SessionTask<WebSearchResult>() {
            public WebSearchResult run(BrowserSession s) throws BrowserException {
                return s.search(query);
            }
        });
    }

    public WebSearchResult search(final String query, final java.util.List<String> engineIds)
            throws BrowserException {
        return call(new SessionTask<WebSearchResult>() {
            public WebSearchResult run(BrowserSession s) throws BrowserException {
                return s.search(query, engineIds);
            }
        });
    }

    public BrowserPageSnapshot open(final String url) throws BrowserException {
        return call(new SessionTask<BrowserPageSnapshot>() {
            public BrowserPageSnapshot run(BrowserSession s) throws BrowserException {
                return s.open(url);
            }
        });
    }

    public BrowserPageSnapshot currentPage() throws BrowserException {
        return call(new SessionTask<BrowserPageSnapshot>() {
            public BrowserPageSnapshot run(BrowserSession s) throws BrowserException {
                return s.currentPage();
            }
        });
    }

    public List<BrowserLink> links() throws BrowserException {
        return call(new SessionTask<List<BrowserLink>>() {
            public List<BrowserLink> run(BrowserSession s) throws BrowserException {
                return s.links();
            }
        });
    }

    public BrowserPageSnapshot follow(final String linkId) throws BrowserException {
        return call(new SessionTask<BrowserPageSnapshot>() {
            public BrowserPageSnapshot run(BrowserSession s) throws BrowserException {
                return s.follow(linkId);
            }
        });
    }

    public BrowserPageSnapshot back() throws BrowserException {
        return call(new SessionTask<BrowserPageSnapshot>() {
            public BrowserPageSnapshot run(BrowserSession s) throws BrowserException {
                return s.back();
            }
        });
    }

    @Override
    public List<String> challengeStatus() {
        try {
            return call(new SessionTask<List<String>>() {
                public List<String> run(BrowserSession s) {
                    return s.challengeStatus();
                }
            });
        } catch (BrowserException closed) {
            // A dead actor still tells the truth about WHY: the user's window close stays typed.
            return Collections.singletonList(closed.getMessage() != null
                    && closed.getMessage().contains("BROWSER_CLOSED") ? "BROWSER_CLOSED" : "NONE");
        }
    }

    @Override
    public BrowserPageReadiness probe(final String url) throws BrowserException {
        return call(new SessionTask<BrowserPageReadiness>() {
            public BrowserPageReadiness run(BrowserSession s) throws BrowserException {
                return s.probe(url);
            }
        });
    }

    @Override
    public BrowserPageReadiness probeCurrent() throws BrowserException {
        return call(new SessionTask<BrowserPageReadiness>() {
            public BrowserPageReadiness run(BrowserSession s) throws BrowserException {
                return s.probeCurrent();
            }
        });
    }

    @Override
    public String dismissConsent() throws BrowserException {
        return call(new SessionTask<String>() {
            public String run(BrowserSession s) throws BrowserException {
                return s.dismissConsent();
            }
        });
    }

    @Override
    public String renderHud(final String stateLine) throws BrowserException {
        return call(new SessionTask<String>() {
            public String run(BrowserSession s) throws BrowserException {
                return s.renderHud(stateLine);
            }
        });
    }

    @Override
    public String pollHudCommands() throws BrowserException {
        // CONTROL PLANE: drained directly on the calling (MCP HTTP) thread. Routing this through the
        // command queue would park the user's Skip behind exactly the blocked data call it is meant to
        // interrupt. The inbox is pure Java and thread-safe; no Playwright state is touched.
        if (hudInbox != null) {
            return hudInbox.drain();
        }
        return call(new SessionTask<String>() {
            public String run(BrowserSession s) throws BrowserException {
                return s.pollHudCommands();
            }
        });
    }

    public void close() {
        try {
            call(new SessionTask<Void>() {
                public Void run(BrowserSession s) {
                    s.close();
                    return null;
                }
            });
        } catch (BrowserException alreadyClosed) {
            // Idempotent: a second close (or a close racing shutdown) is a no-op.
        }
        running.set(false); // the loop drains, fails leftovers and ends within one pump slice
    }

    /**
     * EMERGENCY stop from a FOREIGN thread while the owner is stuck inside a data command: the loop is told
     * to end, every command still queued fails readably, and the driver connection is cut — which fails the
     * stuck command on the owner thread. Never blocks on the owner; new calls are refused from here on.
     */
    void terminate() {
        running.set(false);
        Command<?> leftover;
        while ((leftover = queue.poll()) != null) {
            leftover.fail(new BrowserException("Browser session is closed."));
        }
        try {
            if (playwrightBacked != null) {
                playwrightBacked.terminate();
            } else {
                underlying.close();
            }
        } catch (RuntimeException ignored) {
            // best effort — the loop ends as soon as the stuck command returns
        }
    }

    private static final class Command<T> {
        final SessionTask<T> task;
        final CompletableFuture<T> result = new CompletableFuture<T>();

        Command(SessionTask<T> task) {
            this.task = task;
        }

        void execute(BrowserSession session) {
            try {
                result.complete(task.run(session));
            } catch (Throwable ex) {
                result.completeExceptionally(ex);
            }
        }

        void fail(BrowserException reason) {
            result.completeExceptionally(reason);
        }
    }
}
