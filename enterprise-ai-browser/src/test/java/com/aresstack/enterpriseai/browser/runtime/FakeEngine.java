package com.aresstack.enterpriseai.browser.runtime;

import com.aresstack.enterpriseai.browser.api.BrowserAction;
import com.aresstack.enterpriseai.browser.api.BrowserException;
import com.aresstack.enterpriseai.browser.api.BrowserProbe;
import com.aresstack.enterpriseai.browser.api.engine.BrowserConnection;
import com.aresstack.enterpriseai.browser.api.engine.BrowserEngine;
import com.aresstack.enterpriseai.browser.api.engine.BrowserInstance;
import com.aresstack.enterpriseai.browser.api.engine.RawActOutcome;
import com.aresstack.enterpriseai.browser.api.engine.RawControl;
import com.aresstack.enterpriseai.browser.api.engine.RawDownload;
import com.aresstack.enterpriseai.browser.api.engine.RawLink;
import com.aresstack.enterpriseai.browser.api.engine.RawPage;
import com.aresstack.enterpriseai.browser.api.engine.RawScreenshot;
import com.aresstack.enterpriseai.browser.api.engine.RawTab;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Fake browser engine: one fake process per start, one opening tab like a normal Firefox start. */
final class FakeEngine implements BrowserEngine {

    final List<Path> starts = new CopyOnWriteArrayList<Path>();
    final List<FakeProcess> processes = new CopyOnWriteArrayList<FakeProcess>();
    volatile long startDelayMillis;
    /** Requested url to the url the fake server redirects to. */
    final Map<String, String> redirects = new java.util.concurrent.ConcurrentHashMap<String, String>();
    /** Every url handed to a connection's navigate, in order. */
    final List<String> navigations = new CopyOnWriteArrayList<String>();
    /** Page url to the anchors the fake page shows. */
    final Map<String, List<RawLink>> anchors = new java.util.concurrent.ConcurrentHashMap<String, List<RawLink>>();

    /** Page url to the controls the fake page shows. */
    final Map<String, List<RawControl>> controls = new java.util.concurrent.ConcurrentHashMap<String, List<RawControl>>();
    /** Control ref to the url its action loads (a form submit); refs without entry change the page in place. */
    final Map<String, String> actionTargets = new java.util.concurrent.ConcurrentHashMap<String, String>();
    /** Page url to what a probe finds there; other pages are clear. */
    final Map<String, BrowserProbe> probes = new java.util.concurrent.ConcurrentHashMap<String, BrowserProbe>();
    /** Urls whose load fails in navigateAll. */
    final java.util.Set<String> failingLoads = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Every navigateAll call as its list of urls. */
    final List<List<String>> waves = new CopyOnWriteArrayList<List<String>>();
    /** Every url fetched inside a page, in order; {@link #redirects} apply. */
    final List<String> fetches = new CopyOnWriteArrayList<String>();
    /** Every screenshot as "marks clipRef maxBytes", in order. */
    final List<String> screenshots = new CopyOnWriteArrayList<String>();
    /** Control refs outside the visible part of the page: never reported as visible in a screenshot. */
    final java.util.Set<String> offscreen = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Every action as "ref action value", in order. */
    final List<String> actions = new CopyOnWriteArrayList<String>();

    @Override
    public BrowserInstance start(Path profileDirectory) throws BrowserException {
        starts.add(profileDirectory);
        if (startDelayMillis > 0) {
            try {
                Thread.sleep(startDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        FakeProcess process = new FakeProcess(this, processes.size());
        processes.add(process);
        return process;
    }

    FakeProcess last() {
        return processes.get(processes.size() - 1);
    }

    static final class FakeProcess implements BrowserInstance {

        final FakeEngine engine;
        final int number;
        final Map<String, String[]> tabs = new LinkedHashMap<String, String[]>();
        /** Per tab the number of documents loaded so far. */
        final Map<String, Integer> documents = new java.util.HashMap<String, Integer>();
        final AtomicInteger connects = new AtomicInteger();
        final List<String> activated = new CopyOnWriteArrayList<String>();
        volatile boolean alive = true;
        volatile FakeConnection connection;
        private int nextContext = 1;

        FakeProcess(FakeEngine engine, int number) {
            this.engine = engine;
            this.number = number;
            createTab();
        }

        synchronized String createTab() {
            String id = "ctx-" + number + "-" + nextContext++;
            tabs.put(id, new String[]{"about:blank", ""});
            return id;
        }

        /** The user closes the browser window. */
        void closeByUser() {
            alive = false;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public BrowserConnection connect() throws BrowserException {
            if (!alive) {
                throw new BrowserException(BrowserException.Kind.BROWSER_CLOSED, "gone");
            }
            connects.incrementAndGet();
            connection = new FakeConnection(this);
            return connection;
        }

        @Override
        public void stop() {
            alive = false;
        }
    }

    static final class FakeConnection implements BrowserConnection {

        final FakeProcess process;
        volatile boolean open = true;

        FakeConnection(FakeProcess process) {
            this.process = process;
        }

        private void check() throws BrowserException {
            if (!process.alive) {
                open = false;
                throw new BrowserException(BrowserException.Kind.BROWSER_CLOSED, "BROWSER_CLOSED");
            }
            if (!open) {
                throw new BrowserException(BrowserException.Kind.CONNECTION_LOST, "socket closed");
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public List<RawTab> tabs() throws BrowserException {
            check();
            synchronized (process) {
                List<RawTab> list = new ArrayList<RawTab>();
                for (Map.Entry<String, String[]> tab : process.tabs.entrySet()) {
                    list.add(new RawTab(tab.getKey(), tab.getValue()[0], tab.getValue()[1]));
                }
                return list;
            }
        }

        @Override
        public String createTab() throws BrowserException {
            check();
            return process.createTab();
        }

        @Override
        public void activate(String contextId) throws BrowserException {
            check();
            process.activated.add(contextId);
        }

        @Override
        public void close(String contextId) throws BrowserException {
            check();
            synchronized (process) {
                process.tabs.remove(contextId);
            }
        }

        @Override
        public void navigate(String contextId, String url) throws BrowserException {
            check();
            process.engine.navigations.add(url);
            String target = process.engine.redirects.containsKey(url) ? process.engine.redirects.get(url) : url;
            synchronized (process) {
                process.documents.merge(contextId, 1, Integer::sum);
                process.tabs.put(contextId, new String[]{target, "Title of " + target});
            }
        }

        volatile String human = "off";

        @Override
        public void setHumanEmulation(boolean keys, boolean mouse) {
            human = keys + "/" + mouse;
        }

        @Override
        public Map<String, String> navigateAll(Map<String, String> urlByContext) throws BrowserException {
            check();
            process.engine.waves.add(new ArrayList<String>(urlByContext.values()));
            Map<String, String> failures = new java.util.LinkedHashMap<String, String>();
            for (Map.Entry<String, String> entry : urlByContext.entrySet()) {
                if (process.engine.failingLoads.contains(entry.getValue())) {
                    failures.put(entry.getKey(), "NS_ERROR_UNKNOWN_HOST");
                } else {
                    navigate(entry.getKey(), entry.getValue());
                }
            }
            return failures;
        }

        @Override
        public List<RawLink> links(String contextId) throws BrowserException {
            check();
            synchronized (process) {
                List<RawLink> links = process.engine.anchors.get(process.tabs.get(contextId)[0]);
                return links == null ? new ArrayList<RawLink>() : links;
            }
        }

        @Override
        public List<RawControl> controls(String contextId) throws BrowserException {
            check();
            synchronized (process) {
                List<RawControl> list = process.engine.controls.get(process.tabs.get(contextId)[0]);
                return list == null ? new ArrayList<RawControl>() : list;
            }
        }

        @Override
        public RawActOutcome act(String contextId, String ref, BrowserAction action, String value)
                throws BrowserException {
            check();
            process.engine.actions.add(ref + " " + action + " " + value);
            String target = process.engine.actionTargets.get(ref);
            if (target == null) {
                return new RawActOutcome(false, true);
            }
            navigate(contextId, target);
            return new RawActOutcome(true, false);
        }

        @Override
        public RawScreenshot screenshot(String contextId, Map<String, String> marks, String clipRef, int maxBytes)
                throws BrowserException {
            check();
            process.engine.screenshots.add(marks + " " + clipRef + " " + maxBytes);
            List<String> visible = new ArrayList<String>();
            for (String ref : marks.keySet()) {
                if (!process.engine.offscreen.contains(ref)) {
                    visible.add(ref);
                }
            }
            return new RawScreenshot("image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, 7}, visible);
        }

        @Override
        public RawDownload fetch(String contextId, String url, int maxBytes) throws BrowserException {
            check();
            process.engine.fetches.add(url);
            String target = process.engine.redirects.containsKey(url) ? process.engine.redirects.get(url) : url;
            return new RawDownload(target, "application/pdf", ("%PDF " + target).getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public BrowserProbe probe(String contextId) throws BrowserException {
            check();
            synchronized (process) {
                BrowserProbe probe = process.engine.probes.get(process.tabs.get(contextId)[0]);
                return probe == null ? BrowserProbe.clear() : probe;
            }
        }

        @Override
        public String documentToken(String contextId) throws BrowserException {
            check();
            synchronized (process) {
                return contextId + "#" + process.documents.getOrDefault(contextId, 0);
            }
        }

        /** The user reloads the page: same address, new document. */
        void userReloads(String contextId) {
            synchronized (process) {
                process.documents.merge(contextId, 1, Integer::sum);
            }
        }

        /** The user clicks something in the visible browser. */
        void userNavigates(String contextId, String url) {
            synchronized (process) {
                process.documents.merge(contextId, 1, Integer::sum);
                process.tabs.put(contextId, new String[]{url, "Title of " + url});
            }
        }

        @Override
        public RawPage read(String contextId) throws BrowserException {
            check();
            synchronized (process) {
                String[] tab = process.tabs.get(contextId);
                return new RawPage(tab[0], tab[1], "Text of " + tab[0]);
            }
        }

        @Override
        public void disconnect() {
            open = false;
        }
    }
}
