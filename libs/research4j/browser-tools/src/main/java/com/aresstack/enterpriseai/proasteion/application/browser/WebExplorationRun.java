package com.aresstack.enterpriseai.proasteion.application.browser;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The exploration state of one chat's browser in one turn: which targets were discovered, examined, visited,
 * skipped, failed and marked relevant, by canonical address, plus the latest steps. Fed only by the browser tools,
 * read by any display through {@link #progress()} or the change listener; it knows nothing about the UI.
 * Thread-safe; the listener is called outside the lock.
 */
public final class WebExplorationRun {

    static final int MAX_STEPS = 8;

    private final Set<String> discovered = new HashSet<String>();
    private final Set<String> examined = new HashSet<String>();
    private final Set<String> visited = new HashSet<String>();
    private final Set<String> skipped = new HashSet<String>();
    private final Set<String> failed = new HashSet<String>();
    private final Set<String> relevant = new HashSet<String>();
    private final LinkedList<WebExplorationProgress.Step> steps = new LinkedList<WebExplorationProgress.Step>();
    /** Link id to target of the last link list shown, to name a refused or failed follow. */
    private final java.util.Map<String, String> lastLinks = new java.util.HashMap<String, String>();
    private final java.util.Map<String, String> lastControls = new java.util.HashMap<String, String>();
    /** The controls behind {@link #lastControls}, to judge whether an action changes data (approval). */
    private final java.util.Map<String, com.aresstack.enterpriseai.browser.api.BrowserControl> lastControlDetails =
            new java.util.HashMap<String, com.aresstack.enterpriseai.browser.api.BrowserControl>();
    private int unlisted;
    private String currentPage;
    private String currentTitle = "";
    private boolean active;
    private int running;
    private String challenge = "";
    private Consumer<WebExplorationProgress> listener;

    /** Starts a new turn: forgets the previous turn's counts and reports changes to {@code listener} (may be null). */
    public WebExplorationProgress begin(Consumer<WebExplorationProgress> listener) {
        synchronized (this) {
            discovered.clear();
            unlisted = 0;
            examined.clear();
            visited.clear();
            skipped.clear();
            failed.clear();
            relevant.clear();
            steps.clear();
            lastLinks.clear();
            lastControls.clear();
            lastControlDetails.clear();
            challenge = "";
            currentPage = null;
            currentTitle = "";
            active = false;
            running = 0;
            this.listener = listener;
            return snapshot();
        }
    }

    /** Ends the turn: no further change reports; returns the final state. */
    public WebExplorationProgress end() {
        synchronized (this) {
            listener = null;
            running = 0;
            return snapshot();
        }
    }

    public synchronized WebExplorationProgress progress() {
        return snapshot();
    }

    /** A browser tool starts executing. */
    public void started() {
        change(() -> {
            active = true;
            running++;
        });
    }

    /** The browser tool that {@link #started()} is done (successful or not). */
    public void finished() {
        change(() -> running = Math.max(0, running - 1));
    }

    /** A page was loaded and handed to the model. */
    public void opened(String url, String title, boolean back) {
        change(() -> {
            String page = canonical(url);
            if (page != null) {
                visited.add(page);
                failed.remove(page);
            }
            currentPage = page;
            lastLinks.clear();
            lastControls.clear();
            lastControlDetails.clear();
            currentTitle = title == null ? "" : title;
            step(back ? WebExplorationProgress.StepKind.BACK : WebExplorationProgress.StepKind.OPENED,
                    label(title, url), 0);
        });
    }

    /**
     * A linked document was read without leaving the page: it counts as visited and becomes the page {@code mark}
     * refers to, while the link list of the page stays valid.
     */
    public void documentRead(String url, String name) {
        change(() -> {
            String page = canonical(url);
            if (page != null) {
                visited.add(page);
                failed.remove(page);
            }
            currentPage = page;
            currentTitle = name == null ? "" : name;
            step(WebExplorationProgress.StepKind.OPENED, label(name, url), 0);
        });
    }

    /**
     * A link list was shown to the model: its targets are discovered and examined (the model decides on each one it
     * sees); {@code total} is the number of links the page offers for the request.
     */
    public void linksListed(java.util.Map<String, String> shownById, int total, String filter) {
        final Collection<String> shown = shownById.values();
        change(() -> {
            lastLinks.clear();
            lastLinks.putAll(shownById);
            addAll(discovered, shown);
            // Links of the page the model was not shown (limit) are discovered but carry no address here.
            unlisted += Math.max(0, total - shownById.size());
            addAll(examined, shown);
            step(WebExplorationProgress.StepKind.LINKS, filter == null ? "" : filter, total);
        });
    }

    /**
     * The model operated a control of the current page ({@code description}: what it did, e.g. a search term); when
     * the action loaded a new page, {@link #opened} follows. A page changed in place counts as visited again.
     */
    public void acted(String description) {
        change(() -> step(WebExplorationProgress.StepKind.ACTED, description == null ? "" : description, 0));
    }

    /** A control list was shown to the model: id to label, for describing later actions. */
    public void controlsListed(java.util.Map<String, String> labelsById) {
        change(() -> {
            lastControls.clear();
            lastControlDetails.clear();
            lastControls.putAll(labelsById);
            step(WebExplorationProgress.StepKind.ACTED, labelsById.size()
                    + (labelsById.size() == 1 ? " Bedienelement erkannt" : " Bedienelemente erkannt"), 0);
        });
    }

    /**
     * Controls the runtime looked at on its own (the search hint after a load): id to label for describing later
     * actions, without a progress step, since the model did not ask for a list.
     */
    public synchronized void controlsNoted(java.util.Map<String, String> labelsById) {
        lastControls.clear();
        lastControls.putAll(labelsById);
    }

    /** The controls of the last list shown or noted, replacing earlier ones; call right after it. */
    public synchronized void controlsSeen(
            java.util.List<com.aresstack.enterpriseai.browser.api.BrowserControl> controls) {
        lastControlDetails.clear();
        for (com.aresstack.enterpriseai.browser.api.BrowserControl control : controls) {
            lastControlDetails.put(control.id(), control);
        }
    }

    /** Control {@code id} of the last list shown or noted, or {@code null} when it was not in it. */
    /**
     * The control of the last list that {@code locator} names (its locator, or its label as quoted or plain text), or
     * {@code null} when none or several fit: for judging an action by locator before it runs.
     */
    public synchronized com.aresstack.enterpriseai.browser.api.BrowserControl controlByLocator(String locator) {
        if (locator == null) {
            return null;
        }
        String wanted = locator.trim();
        int open = wanted.indexOf('"');
        int close = wanted.lastIndexOf('"');
        String name = open >= 0 && close > open ? wanted.substring(open + 1, close) : wanted;
        com.aresstack.enterpriseai.browser.api.BrowserControl hit = null;
        for (com.aresstack.enterpriseai.browser.api.BrowserControl control : lastControlDetails.values()) {
            if (control.locator().equalsIgnoreCase(wanted) || control.label().equalsIgnoreCase(name)) {
                if (hit != null) {
                    return null;
                }
                hit = control;
            }
        }
        return hit;
    }

    public synchronized com.aresstack.enterpriseai.browser.api.BrowserControl control(String id) {
        return id == null ? null : lastControlDetails.get(id.trim());
    }

    /** Address of the page currently in front of the model, or {@code null}. */
    public synchronized String currentPage() {
        return currentPage;
    }

    /** The label of control {@code id} in the last control list shown, or {@code null}. */
    public synchronized String controlLabel(String id) {
        return id == null ? null : lastControls.get(id.trim());
    }

    /**
     * The page in front of the model is a challenge only the user can solve ({@code kind} captcha or login), or no
     * longer is one ({@code kind} empty). A pending challenge keeps the browser open after the turn.
     */
    public void challenged(String kind) {
        final String now = kind == null ? "" : kind;
        change(() -> {
            if (!now.isEmpty() && !now.equals(challenge)) {
                step(WebExplorationProgress.StepKind.CHALLENGE, now, 0);
            }
            challenge = now;
        });
    }

    /** Whether a challenge of this turn is still waiting for the user. */
    public synchronized boolean challengePending() {
        return !challenge.isEmpty();
    }

    /** {@code url} was not opened on purpose (policy or user refused it). */
    public void skipped(String url) {
        change(() -> {
            String target = canonical(url);
            if (target != null) {
                skipped.add(target);
            }
            step(WebExplorationProgress.StepKind.SKIPPED, hostOf(url), 0);
        });
    }

    /** Opening or reading {@code url} (or the current page if {@code null}) failed with {@code reason}. */
    public void failed(String url, String reason) {
        change(() -> {
            String target = url == null ? null : canonical(url);
            if (target != null && !visited.contains(target)) {
                failed.add(target);
            }
            step(WebExplorationProgress.StepKind.FAILED, reason, 0);
        });
    }

    /** The model marks the current page as relevant (used for the answer) or not. */
    public boolean markCurrent(boolean isRelevant) {
        final boolean[] marked = {false};
        change(() -> {
            if (currentPage == null) {
                return;
            }
            marked[0] = true;
            if (isRelevant) {
                relevant.add(currentPage);
            } else {
                relevant.remove(currentPage);
            }
            step(isRelevant ? WebExplorationProgress.StepKind.MARKED_RELEVANT
                    : WebExplorationProgress.StepKind.MARKED_IRRELEVANT, label(currentTitle, currentPage), 0);
        });
        return marked[0];
    }

    /** The browser was closed. */
    public void closed() {
        change(() -> {
            currentPage = null;
            currentTitle = "";
            step(WebExplorationProgress.StepKind.CLOSED, "", 0);
        });
    }

    /** The target of link {@code id} in the last link list shown, or {@code null}. */
    public synchronized String linkTarget(String id) {
        return id == null ? null : lastLinks.get(id.trim());
    }

    /** Whether {@code url} was visited in this turn (by canonical address). */
    public synchronized boolean wasVisited(String url) {
        String target = canonical(url);
        return target != null && visited.contains(target);
    }

    private void change(Runnable update) {
        Consumer<WebExplorationProgress> notify;
        WebExplorationProgress now;
        synchronized (this) {
            update.run();
            notify = listener;
            now = snapshot();
        }
        if (notify != null) {
            notify.accept(now);
        }
    }

    private void step(WebExplorationProgress.StepKind kind, String label, int count) {
        steps.add(new WebExplorationProgress.Step(kind, label, count));
        while (steps.size() > MAX_STEPS) {
            steps.removeFirst();
        }
    }

    private WebExplorationProgress snapshot() {
        return new WebExplorationProgress(active, running > 0, discovered.size() + unlisted, examined.size(), visited.size(),
                skipped.size(), failed.size(), relevant.size(), new ArrayList<WebExplorationProgress.Step>(steps),
                challenge);
    }

    private static void addAll(Set<String> into, Collection<String> urls) {
        if (urls == null) {
            return;
        }
        for (String url : urls) {
            String target = canonical(url);
            if (target != null) {
                into.add(target);
            }
        }
    }

    private static String label(String title, String url) {
        return title != null && !title.trim().isEmpty() ? title.trim() : url == null ? "" : url;
    }

    private static String hostOf(String url) {
        try {
            String host = url == null ? null : new URI(url.trim()).getHost();
            return host == null ? String.valueOf(url) : host;
        } catch (URISyntaxException e) {
            return String.valueOf(url);
        }
    }

    /**
     * The address an exploration counts by: lower-case scheme and host, no default port, no fragment, no trailing
     * slash on a non-root path. {@code null} for anything but an absolute http(s) address.
     */
    static String canonical(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
                return null;
            }
            int port = uri.getPort();
            if ("http".equals(scheme) && port == 80 || "https".equals(scheme) && port == 443) {
                port = -1;
            }
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            StringBuilder out = new StringBuilder(scheme).append("://").append(uri.getHost().toLowerCase(Locale.ROOT));
            if (port >= 0) {
                out.append(':').append(port);
            }
            out.append(path);
            if (uri.getRawQuery() != null) {
                out.append('?').append(uri.getRawQuery());
            }
            return out.toString();
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
