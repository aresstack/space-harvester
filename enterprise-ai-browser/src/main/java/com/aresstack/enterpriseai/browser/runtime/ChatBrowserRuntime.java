package com.aresstack.enterpriseai.browser.runtime;

import com.aresstack.enterpriseai.astu.chat.ChatConversationId;
import com.aresstack.enterpriseai.browser.api.BrowserAccessGuard;
import com.aresstack.enterpriseai.browser.api.BrowserAction;
import com.aresstack.enterpriseai.browser.api.BrowserActResult;
import com.aresstack.enterpriseai.browser.api.BrowserBatchPage;
import com.aresstack.enterpriseai.browser.api.BrowserControl;
import com.aresstack.enterpriseai.browser.api.BrowserControls;
import com.aresstack.enterpriseai.browser.api.BrowserDocument;
import com.aresstack.enterpriseai.browser.api.BrowserException;
import com.aresstack.enterpriseai.browser.api.BrowserLink;
import com.aresstack.enterpriseai.browser.api.BrowserLinks;
import com.aresstack.enterpriseai.browser.api.BrowserPage;
import com.aresstack.enterpriseai.browser.api.BrowserProbe;
import com.aresstack.enterpriseai.browser.api.BrowserScreenshot;
import com.aresstack.enterpriseai.browser.api.BrowserTab;
import com.aresstack.enterpriseai.browser.api.ChatBrowser;
import com.aresstack.enterpriseai.browser.api.ChatBrowserPort;
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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * One browser per chat over a {@link BrowserEngine}.
 *
 * <ul>
 *     <li>Lazy: the process starts on the first operation of the chat, not when the chat list opens.</li>
 *     <li>Healthy connection: reused as is, no new process and no new session.</li>
 *     <li>Lost connection, process alive: reconnect to the same process; tab count and focus stay.</li>
 *     <li>Process gone (closed by the user, crashed): normal start on the same profile.</li>
 *     <li>All operations of a chat are serialized, so concurrent first calls start exactly one browser;
 *         different chats never share a lock, a process or a tab.</li>
 *     <li>Reading operations are retried once after recovery; tab creation and closing are not.</li>
 *     <li>Every navigation passes the {@link BrowserAccessGuard} before it reaches the engine, and the final
 *         address after redirects passes it again; a blocked redirect target leaves the tab on about:blank.
 *         The guard is called while holding only the per-chat slot monitor (it may wait for the user), so
 *         other chats and the UI are never blocked by it. Blocked calls are never retried.</li>
 *     <li>Links are snapshots per tab: {@link ChatBrowser#links} numbers the anchors of the current page, and every
 *         navigation of the tab (navigate, follow, back, closing, a restart, a page change or reload by the user)
 *         invalidates them. {@link ChatBrowser#follow} only opens addresses of that snapshot, through the guard.</li>
 *     <li>{@link ChatBrowser#back} opens the page the tab showed before the last navigation through this runtime,
 *         through the guard before loading; the browser's own history is not replayed.</li>
 *     <li>Controls are snapshots per tab as well: {@link ChatBrowser#controls} numbers fields, buttons and menus of
 *         the current document; loading a new document invalidates them, a single page app may keep them as long as
 *         the element stays. {@link ChatBrowser#act} admits a form's declared target before submitting and the
 *         address reached afterwards again; an action that loads a new page counts as a navigation for back.</li>
 *     <li>{@link ChatBrowser#closeBrowser} ends process and connection but keeps the slot: the next operation starts
 *         the browser again on the same profile.</li>
 * </ul>
 */
public final class ChatBrowserRuntime implements ChatBrowserPort {

    private final BrowserEngine engine;
    private final BrowserProfileStore profiles;
    private final BrowserAccessGuard guard;
    private static final int MAX_LINK_TEXT = 160;
    private static final int MAX_HISTORY = 50;

    private final ConcurrentMap<ChatConversationId, Slot> slots = new ConcurrentHashMap<ChatConversationId, Slot>();

    public ChatBrowserRuntime(BrowserEngine engine, BrowserProfileStore profiles) {
        this(engine, profiles, BrowserAccessGuard.allowAll());
    }

    public ChatBrowserRuntime(BrowserEngine engine, BrowserProfileStore profiles, BrowserAccessGuard guard) {
        if (engine == null || profiles == null || guard == null) {
            throw new IllegalArgumentException("engine, profiles and guard must not be null");
        }
        this.engine = engine;
        this.profiles = profiles;
        this.guard = guard;
    }

    @Override
    public ChatBrowser browserOf(ChatConversationId conversation) {
        if (conversation == null) {
            throw new IllegalArgumentException("conversation must not be null");
        }
        Slot slot = slots.get(conversation);
        if (slot == null) {
            Slot created = new Slot(conversation);
            slot = slots.putIfAbsent(conversation, created);
            if (slot == null) {
                slot = created;
            }
        }
        return slot;
    }

    @Override
    public void release(ChatConversationId conversation) {
        Slot slot = conversation == null ? null : slots.remove(conversation);
        if (slot != null) {
            slot.shutdown();
        }
    }

    @Override
    public void deleteProfile(ChatConversationId conversation) throws BrowserException {
        release(conversation);
        profiles.delete(conversation);
    }

    @Override
    public void releaseAll() {
        for (ChatConversationId conversation : new ArrayList<ChatConversationId>(slots.keySet())) {
            release(conversation);
        }
    }

    private interface Operation<T> {
        T run(BrowserConnection connection) throws BrowserException;
    }

    /** The browser of one chat; every method holds the slot's monitor. */
    private final class Slot implements ChatBrowser {

        private final ChatConversationId conversation;
        private BrowserInstance instance;
        private BrowserConnection connection;
        /** Raw context id to chat-local tab id; valid as long as the process lives. */
        private final Map<String, String> tabIds = new LinkedHashMap<String, String>();
        private int nextTab = 1;
        private String currentRaw;
        private boolean closed;
        /** Link id to address of the last link list of any tab; never invalidated, every target passes the guard. */
        private final Map<String, String> lastLinks = new HashMap<String, String>();
        private boolean humanKeys;
        private boolean humanMouse;
        /** Raw context id to the tab's current link snapshot. */
        private final Map<String, LinkSnapshot> snapshots = new HashMap<String, LinkSnapshot>();
        /** Raw context id to the tab's current control snapshot. */
        private final Map<String, ControlSnapshot> controlSnapshots = new HashMap<String, ControlSnapshot>();
        /** Raw context id to the pages the tab showed before, newest first (targets of back). */
        private final Map<String, java.util.Deque<String>> history = new HashMap<String, java.util.Deque<String>>();

        Slot(ChatConversationId conversation) {
            this.conversation = conversation;
        }

        @Override
        public synchronized List<BrowserTab> tabs() throws BrowserException {
            return call(this::listTabs, true);
        }

        @Override
        public synchronized BrowserTab selectTab(final String tabId) throws BrowserException {
            return call(connection -> {
                String raw = rawOf(connection, tabId);
                connection.activate(raw);
                currentRaw = raw;
                return tabOf(connection, raw);
            }, true);
        }

        @Override
        public synchronized BrowserTab newTab(final String url) throws BrowserException {
            if (url != null) {
                guard.admit(conversation, url);
            }
            return call(connection -> {
                String raw = connection.createTab();
                idOf(raw);
                currentRaw = raw;
                if (url != null) {
                    connection.navigate(raw, url);
                    checkFinal(connection, raw, connection.read(raw).url());
                }
                return tabOf(connection, raw);
            }, false);
        }

        @Override
        public synchronized void closeTab(final String tabId) throws BrowserException {
            call(connection -> {
                String raw = rawOf(connection, tabId);
                connection.close(raw);
                tabIds.remove(raw);
                snapshots.remove(raw);
                controlSnapshots.remove(raw);
                history.remove(raw);
                if (raw.equals(currentRaw)) {
                    currentRaw = null;
                }
                return null;
            }, false);
        }

        @Override
        public synchronized BrowserPage navigate(final String tabId, final String url) throws BrowserException {
            guard.admit(conversation, url);
            return call(connection -> {
                String raw = tabId == null ? currentOrNew(connection) : rawOf(connection, tabId);
                snapshots.remove(raw);
                remember(connection, raw);
                connection.navigate(raw, url);
                BrowserPage page = page(connection, raw);
                checkFinal(connection, raw, page.url());
                return page;
            }, true);
        }

        @Override
        public synchronized BrowserPage read(final String tabId) throws BrowserException {
            return call(connection -> {
                String raw = tabId == null ? currentOrFail(connection) : rawOf(connection, tabId);
                return page(connection, raw);
            }, true);
        }

        @Override
        public synchronized List<BrowserBatchPage> openMany(final List<String> urls, final int parallel,
                                                        final int maxDelayMillis)
                throws BrowserException {
            if (urls == null) {
                throw new IllegalArgumentException("urls must not be null");
            }
            int wave = Math.max(1, Math.min(MAX_PARALLEL_TABS, parallel));
            final int delay = Math.max(0, Math.min(MAX_TAB_DELAY_MILLIS, maxDelayMillis));
            BrowserBatchPage[] results = new BrowserBatchPage[urls.size()];
            Map<String, BrowserException> hosts = new HashMap<String, BrowserException>();
            Set<String> seen = new HashSet<String>();
            List<Integer> admitted = new ArrayList<Integer>();
            for (int i = 0; i < urls.size(); i++) {
                String url = urls.get(i) == null ? "" : urls.get(i).trim();
                if (usableUrl(url) == null) {
                    results[i] = BrowserBatchPage.skipped(url, "Keine http(s)-Adresse");
                } else if (!seen.add(withoutFragment(url))) {
                    results[i] = BrowserBatchPage.skipped(url, "Doppelt in der Liste");
                } else {
                    BrowserException refused = admitOnce(hosts, url);
                    if (refused == null) {
                        admitted.add(i);
                    } else {
                        results[i] = outcomeOf(url, refused);
                    }
                }
            }
            for (int from = 0; from < admitted.size(); from += wave) {
                final List<Integer> slice = admitted.subList(from, Math.min(admitted.size(), from + wave));
                try {
                    call(connection -> {
                        loadWave(connection, urls, slice, results, hosts, delay);
                        return null;
                    }, false);
                } catch (BrowserException e) {
                    if (e.kind() == BrowserException.Kind.BROWSER_CLOSED && closed) {
                        throw e;
                    }
                }
                for (int index : slice) {
                    if (results[index] == null) {
                        results[index] = BrowserBatchPage.failed(urls.get(index).trim(),
                                "Verbindung zum Browser verloren");
                    }
                }
            }
            return java.util.Arrays.asList(results);
        }

        /** Loads one wave in fresh tabs, reads and closes them; the user's current tab is restored. */
        private void loadWave(BrowserConnection connection, List<String> urls, List<Integer> slice,
                              BrowserBatchPage[] results, Map<String, BrowserException> hosts,
                              int maxDelayMillis) throws BrowserException {
            String current = currentRaw;
            Map<String, String> urlByRaw = new LinkedHashMap<String, String>();
            Map<String, Integer> indexByRaw = new HashMap<String, Integer>();
            try {
                for (int index : slice) {
                    pauseBeforeTab(maxDelayMillis);
                    String raw = connection.createTab();
                    idOf(raw);
                    urlByRaw.put(raw, urls.get(index).trim());
                    indexByRaw.put(raw, index);
                }
                Map<String, String> failures = connection.navigateAll(urlByRaw);
                for (Map.Entry<String, String> entry : urlByRaw.entrySet()) {
                    String raw = entry.getKey();
                    String url = entry.getValue();
                    int index = indexByRaw.get(raw);
                    if (failures.containsKey(raw)) {
                        results[index] = BrowserBatchPage.failed(url, failures.get(raw));
                        continue;
                    }
                    try {
                        BrowserPage page = page(connection, raw);
                        BrowserException refused = admitOnce(hosts, page.url());
                        if (refused != null) {
                            connection.navigate(raw, "about:blank");
                            results[index] = outcomeOf(url, refused);
                            continue;
                        }
                        results[index] = BrowserBatchPage.loaded(url, page, documentOf(connection, raw, page.url()));
                    } catch (BrowserException e) {
                        if (!e.isRecoverable()) {
                            results[index] = outcomeOf(url, e);
                        } else {
                            throw e;
                        }
                    }
                }
            } finally {
                for (String raw : urlByRaw.keySet()) {
                    try {
                        connection.close(raw);
                    } catch (BrowserException ignored) {
                        // The tab may be gone already; the next sync forgets it.
                    }
                    tabIds.remove(raw);
                }
                currentRaw = current;
            }
        }

        /** Waits a random time from 0 to {@code maxDelayMillis} before a new tab is opened. */
        private void pauseBeforeTab(int maxDelayMillis) throws BrowserException {
            if (maxDelayMillis <= 0) {
                return;
            }
            try {
                Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextInt(maxDelayMillis + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BrowserException(BrowserException.Kind.FAILED, "Öffnen der Tabs unterbrochen", e);
            }
        }

        /** The document behind {@code url} when its path names one, fetched in its own tab; else {@code null}. */
        private BrowserDocument documentOf(BrowserConnection connection, String raw, String url) {
            if (!isDocumentPath(url)) {
                return null;
            }
            try {
                RawDownload download = connection.fetch(raw, url, BrowserDocument.MAX_BYTES);
                return new BrowserDocument(download.finalUrl(), fileNameOf(download.finalUrl()), download.contentType(),
                        download.content());
            } catch (BrowserException e) {
                // The viewer's text is still there; the document stays unread.
                return null;
            }
        }

        /** Admits the host of {@code url} once per batch; {@code null} when admitted, else the refusal. */
        private BrowserException admitOnce(Map<String, BrowserException> hosts, String url) {
            String host;
            try {
                host = new URI(url).getHost();
            } catch (URISyntaxException e) {
                host = null;
            }
            String key = host == null ? url : host.toLowerCase(Locale.ROOT);
            if (hosts.containsKey(key)) {
                return hosts.get(key);
            }
            BrowserException refused = null;
            try {
                guard.admit(conversation, url);
            } catch (BrowserException e) {
                refused = e;
            }
            hosts.put(key, refused);
            return refused;
        }

        @Override
        public synchronized BrowserLinks links(final String tabId, final int maxLinks, final String filter)
                throws BrowserException {
            final String needle = filter == null || filter.trim().isEmpty() ? null
                    : filter.trim().toLowerCase(Locale.ROOT);
            final int limit = Math.max(1, Math.min(BrowserLinks.MAX_LINKS, maxLinks));
            return call(connection -> {
                String raw = tabId == null ? currentOrFail(connection) : rawOf(connection, tabId);
                RawTab tab = rawTab(connection, raw);
                String document = connection.documentToken(raw);
                List<RawLink> anchors = connection.links(raw);
                String page = withoutFragment(tab.url());
                Map<String, String[]> usable = new LinkedHashMap<String, String[]>();
                for (RawLink anchor : anchors) {
                    String url = usableUrl(anchor.href());
                    if (url == null || withoutFragment(url).equals(page)) {
                        continue;
                    }
                    String text = clip(anchor.text());
                    String label = clip(anchor.title().isEmpty() ? anchor.ariaLabel() : anchor.title());
                    if (needle != null && !(text + " " + label + " " + url).toLowerCase(Locale.ROOT).contains(needle)) {
                        continue;
                    }
                    String[] known = usable.get(url);
                    if (known == null) {
                        usable.put(url, new String[]{text, label.equals(text) ? "" : label, anchor.region()});
                    } else if (known[0].isEmpty()) {
                        known[0] = text;
                    }
                }
                LinkSnapshot snapshot = new LinkSnapshot(page, document);
                List<BrowserLink> links = new ArrayList<BrowserLink>(Math.min(limit, usable.size()));
                for (Map.Entry<String, String[]> entry : usable.entrySet()) {
                    if (links.size() >= limit) {
                        break;
                    }
                    String id = "link-" + (links.size() + 1);
                    snapshot.urls.put(id, entry.getKey());
                    links.add(new BrowserLink(id, entry.getValue()[0], entry.getKey(), entry.getValue()[1],
                            entry.getValue()[2]));
                }
                snapshots.put(raw, snapshot);
                lastLinks.clear();
                lastLinks.putAll(snapshot.urls);
                return new BrowserLinks(idOf(raw), tab.url(), tab.title(), links, usable.size());
            }, true);
        }

        /** The address of {@code linkId} in the tab's still valid link snapshot, else {@code INVALID_REQUEST}. */
        private String snapshotUrl(BrowserConnection connection, String raw, String linkId) throws BrowserException {
            LinkSnapshot snapshot = snapshots.get(raw);
            if (snapshot != null && (!withoutFragment(rawTab(connection, raw).url()).equals(snapshot.pageUrl)
                    || !connection.documentToken(raw).equals(snapshot.document))) {
                snapshots.remove(raw);
                snapshot = null;
            }
            if (snapshot == null) {
                // The tab moved on since the list (follow, back, a click): the id still names the address the model
                // was shown in the last list; it passes the guard like any navigation.
                String remembered = linkId == null ? null : lastLinks.get(linkId.trim());
                if (remembered != null) {
                    return remembered;
                }
                throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                        "Unbekannte Link-Id " + linkId + "; zuerst browser_links aufrufen");
            }
            String url = linkId == null ? null : snapshot.urls.get(linkId.trim());
            if (url == null) {
                throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Unbekannte Link-Id " + linkId
                        + "; Link-Ids gelten nur für die zuletzt mit browser_links gelieferte Liste");
            }
            return url;
        }

        @Override
        public synchronized BrowserPage follow(final String tabId, final String linkId) throws BrowserException {
            return call(connection -> {
                String raw = tabId == null ? currentOrFail(connection) : rawOf(connection, tabId);
                String url = snapshotUrl(connection, raw, linkId);
                guard.admit(conversation, url);
                snapshots.remove(raw);
                remember(connection, raw);
                connection.navigate(raw, url);
                BrowserPage page = page(connection, raw);
                checkFinal(connection, raw, page.url());
                return page;
            }, false);
        }

        @Override
        public synchronized BrowserDocument download(final String tabId, final String linkId)
                throws BrowserException {
            return call(connection -> {
                String raw = tabId == null ? currentOrFail(connection) : rawOf(connection, tabId);
                String url = snapshotUrl(connection, raw, linkId);
                guard.admit(conversation, url);
                RawDownload download = connection.fetch(raw, url, BrowserDocument.MAX_BYTES);
                String reached = download.finalUrl().isEmpty() ? url : download.finalUrl();
                // A redirect to a refused host is dropped before anything reaches the caller.
                guard.admit(conversation, reached);
                return new BrowserDocument(reached, fileNameOf(reached), download.contentType(), download.content());
            }, true);
        }

        @Override
        public synchronized BrowserPage back(final String tabId) throws BrowserException {
            return call(connection -> {
                String raw = tabId == null ? currentOrFail(connection) : rawOf(connection, tabId);
                java.util.Deque<String> past = history.get(raw);
                if (past == null || past.isEmpty()) {
                    throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                            "Keine vorherige Seite in diesem Tab");
                }
                // The target is known before loading, so it passes the guard first like every other navigation;
                // the browser's own history (which may contain pages the user opened) is never replayed unchecked.
                String url = past.peek();
                guard.admit(conversation, url);
                past.pop();
                snapshots.remove(raw);
                connection.navigate(raw, url);
                BrowserPage page = page(connection, raw);
                checkFinal(connection, raw, page.url());
                return page;
            }, false);
        }

        @Override
        public synchronized BrowserControls controls(final String tabId, final int maxControls, final String filter)
                throws BrowserException {
            final String needle = filter == null || filter.trim().isEmpty() ? null
                    : filter.trim().toLowerCase(Locale.ROOT);
            final int limit = Math.max(1, Math.min(BrowserControls.MAX_CONTROLS, maxControls));
            return call(connection -> {
                String raw = tabId == null ? currentOrFail(connection) : rawOf(connection, tabId);
                RawTab tab = rawTab(connection, raw);
                String document = connection.documentToken(raw);
                List<RawControl> search = new ArrayList<RawControl>();
                List<RawControl> rest = new ArrayList<RawControl>();
                for (RawControl control : connection.controls(raw)) {
                    String label = clip(control.label());
                    if (needle != null && !(label + " " + control.kind() + " " + control.value() + " " + control.form())
                            .toLowerCase(Locale.ROOT).contains(needle)) {
                        continue;
                    }
                    ("search".equals(control.kind()) ? search : rest).add(control);
                }
                search.addAll(rest);
                ControlSnapshot snapshot = new ControlSnapshot(document);
                List<BrowserControl> controls = new ArrayList<BrowserControl>(Math.min(limit, search.size()));
                for (RawControl control : search) {
                    if (controls.size() >= limit) {
                        break;
                    }
                    String id = "ctl-" + (controls.size() + 1);
                    String target = usableUrl(control.target());
                    snapshot.controls.put(id, new RawControl(control.ref(), control.kind(), control.label(),
                            control.value(), control.options(), target == null ? "" : target, control.form(),
                            control.required(), control.locator(), control.position()));
                    controls.add(new BrowserControl(id, control.kind(), clip(control.label()), clip(control.value()),
                            control.options(), target, clip(control.form()), control.required(), control.locator(),
                            control.position()));
                }
                controlSnapshots.put(raw, snapshot);
                return new BrowserControls(idOf(raw), tab.url(), tab.title(), controls, search.size());
            }, true);
        }

        @Override
        public synchronized BrowserActResult act(final String tabId, final String controlId,
                                                 final BrowserAction action, final String value)
                throws BrowserException {
            if (action == null) {
                throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Aktion fehlt");
            }
            if ((action == BrowserAction.FILL || action == BrowserAction.SELECT) && value == null) {
                throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                        action == BrowserAction.FILL ? "fill braucht einen Wert" : "select braucht eine Option");
            }
            return call(connection -> {
                String raw = tabId == null ? currentOrFail(connection) : rawOf(connection, tabId);
                ControlSnapshot snapshot = controlSnapshots.get(raw);
                if (snapshot == null) {
                    throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                            "Keine gültige Liste der Bedienelemente für diesen Tab; zuerst browser_controls aufrufen");
                }
                if (!connection.documentToken(raw).equals(snapshot.document)) {
                    controlSnapshots.remove(raw);
                    throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                            "Seit browser_controls wurde eine neue Seite geladen; die Ids gelten nicht mehr. "
                                    + "browser_controls erneut aufrufen");
                }
                RawControl control = controlId == null ? null : snapshot.controls.get(controlId.trim());
                if (control == null) {
                    throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Unbekannte Id " + controlId
                            + "; Ids gelten nur für die zuletzt mit browser_controls gelieferte Liste");
                }
                return perform(connection, raw, control, action, value);
            }, false);
        }

        @Override
        public synchronized BrowserActResult actLocated(final String tabId, final String locator,
                                                        final BrowserAction action, final String value)
                throws BrowserException {
            if (action == null) {
                throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Aktion fehlt");
            }
            if ((action == BrowserAction.FILL || action == BrowserAction.SELECT) && value == null) {
                throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                        action == BrowserAction.FILL ? "fill braucht einen Wert" : "select braucht eine Option");
            }
            return call(connection -> {
                String raw = tabId == null ? currentOrFail(connection) : rawOf(connection, tabId);
                RawControl control = ControlLocator.find(connection.controls(raw), locator);
                return perform(connection, raw, control, action, value);
            }, false);
        }

        /**
         * Acts on {@code control} behind the access guard. When the element is gone because a single page app
         * rendered it anew, it is found again once by its locator on the current page.
         */
        private BrowserActResult perform(BrowserConnection connection, String raw, RawControl control,
                                         BrowserAction action, String value) throws BrowserException {
            // A form that declares where it submits to is vetted before anything is sent there; script driven
            // targets are only known afterwards and pass the guard as the final address.
            if (!control.target().isEmpty() && (action == BrowserAction.SUBMIT
                    || (action == BrowserAction.CLICK && "button".equals(control.kind())))) {
                guard.admit(conversation, control.target());
            }
            String before = rawTab(connection, raw).url();
            RawActOutcome outcome;
            try {
                outcome = connection.act(raw, control.ref(), action, value);
            } catch (BrowserException e) {
                if (e.kind() != BrowserException.Kind.INVALID_REQUEST || control.locator().isEmpty()) {
                    throw e;
                }
                RawControl again;
                try {
                    again = ControlLocator.find(connection.controls(raw), control.locator());
                } catch (BrowserException notFound) {
                    throw e;
                }
                if (again.ref().equals(control.ref())) {
                    throw e;
                }
                if (!again.target().isEmpty() && (action == BrowserAction.SUBMIT
                        || (action == BrowserAction.CLICK && "button".equals(again.kind())))) {
                    guard.admit(conversation, again.target());
                }
                outcome = connection.act(raw, again.ref(), action, value);
            }
            // Same document or a new one: the page's links may have changed either way.
            snapshots.remove(raw);
            BrowserPage page = page(connection, raw);
            if (outcome.navigated()) {
                controlSnapshots.remove(raw);
            }
            if (outcome.navigated() || !withoutFragment(page.url()).equals(withoutFragment(before))) {
                push(raw, before);
                checkFinal(connection, raw, page.url());
            }
            return new BrowserActResult(page, outcome.navigated(), outcome.changed());
        }

        @Override
        public synchronized BrowserScreenshot screenshot(final String tabId, final String controlId,
                                                         final boolean marks) throws BrowserException {
            final String wanted = controlId == null || controlId.trim().isEmpty() ? null : controlId.trim();
            boolean current = call(connection -> {
                String raw = tabId == null ? currentOrFail(connection) : rawOf(connection, tabId);
                ControlSnapshot snapshot = controlSnapshots.get(raw);
                return snapshot != null && snapshot.document.equals(connection.documentToken(raw));
            }, true);
            if (!current && wanted != null) {
                throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                        "Keine gültige Liste der Bedienelemente für diesen Tab; zuerst browser_controls aufrufen");
            }
            if (!current && marks) {
                // Marks need ids: a fresh snapshot of all controls, the same one browser_controls would return.
                controls(tabId, BrowserControls.MAX_CONTROLS, null);
            }
            return call(connection -> {
                String raw = tabId == null ? currentOrFail(connection) : rawOf(connection, tabId);
                RawTab tab = rawTab(connection, raw);
                // The page may have been opened by the user; a picture of it goes to the model like its text.
                if (usableUrl(tab.url()) != null) {
                    guard.admit(conversation, tab.url());
                }
                ControlSnapshot snapshot = controlSnapshots.get(raw);
                if (snapshot != null && !snapshot.document.equals(connection.documentToken(raw))) {
                    controlSnapshots.remove(raw);
                    snapshot = null;
                }
                String clipRef = null;
                if (wanted != null) {
                    RawControl control = snapshot == null ? null : snapshot.controls.get(wanted);
                    if (control == null) {
                        throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Unbekannte Id " + wanted
                                + "; Ids gelten nur für die zuletzt mit browser_controls gelieferte Liste");
                    }
                    clipRef = control.ref();
                }
                List<String> ids = snapshot == null ? new ArrayList<String>() : snapshot.orderedIds();
                Map<String, String> labels = new LinkedHashMap<String, String>();
                Map<String, String> idsByRef = new HashMap<String, String>();
                for (String id : ids) {
                    RawControl control = snapshot.controls.get(id);
                    labels.put(control.ref(), marks ? id : "");
                    idsByRef.put(control.ref(), id);
                }
                RawScreenshot shot = connection.screenshot(raw, labels, clipRef, BrowserScreenshot.MAX_BYTES);
                Set<String> seen = new HashSet<String>(shot.visibleRefs());
                List<BrowserControl> visible = new ArrayList<BrowserControl>();
                for (String id : ids) {
                    RawControl control = snapshot.controls.get(id);
                    if (seen.contains(control.ref())) {
                        visible.add(new BrowserControl(id, control.kind(), clip(control.label()),
                                clip(control.value()), control.options(),
                                control.target().isEmpty() ? null : control.target(), clip(control.form()),
                                control.required(), control.locator(), control.position()));
                    }
                }
                return new BrowserScreenshot(idOf(raw), tab.url(), tab.title(), shot.mediaType(), shot.image(),
                        marks && !visible.isEmpty(), visible);
            }, true);
        }

        @Override
        public synchronized BrowserProbe probe(final String tabId) throws BrowserException {
            return call(connection -> connection.probe(tabId == null ? currentOrFail(connection)
                    : rawOf(connection, tabId)), true);
        }

        @Override
        public synchronized void closeBrowser() {
            dropConnection();
            stopInstance();
        }

        @Override
        public synchronized void setHumanEmulation(boolean keys, boolean mouse) {
            humanKeys = keys;
            humanMouse = mouse;
            if (connection != null) {
                connection.setHumanEmulation(keys, mouse);
            }
        }

        @Override
        public synchronized boolean isRunning() {
            return instance != null && instance.isAlive();
        }

        /** Remembers the page the tab shows now as the target of a later {@link #back}. */
        private void remember(BrowserConnection connection, String raw) throws BrowserException {
            push(raw, rawTab(connection, raw).url());
        }

        private void push(String raw, String url) {
            if (usableUrl(url) == null) {
                return;
            }
            java.util.Deque<String> past = history.get(raw);
            if (past == null) {
                past = new java.util.ArrayDeque<String>();
                history.put(raw, past);
            }
            if (!url.equals(past.peek())) {
                past.push(url);
            }
            while (past.size() > MAX_HISTORY) {
                past.removeLast();
            }
        }

        private RawTab rawTab(BrowserConnection connection, String raw) throws BrowserException {
            for (RawTab tab : connection.tabs()) {
                if (tab.contextId().equals(raw)) {
                    return tab;
                }
            }
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Der Tab ist nicht mehr offen");
        }

        /** Admits the address reached after redirects; a refused one leaves the tab on about:blank. */
        private void checkFinal(BrowserConnection connection, String raw, String finalUrl) throws BrowserException {
            try {
                guard.admit(conversation, finalUrl);
            } catch (BrowserException e) {
                if (e.kind() == BrowserException.Kind.BLOCKED) {
                    connection.navigate(raw, "about:blank");
                }
                throw e;
            }
        }

        synchronized void shutdown() {
            closed = true;
            dropConnection();
            stopInstance();
        }

        private <T> T call(Operation<T> operation, boolean repeatable) throws BrowserException {
            if (closed) {
                throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Der Browser dieses Chats ist beendet");
            }
            ensureConnected();
            try {
                return operation.run(connection);
            } catch (BrowserException first) {
                if (!first.isRecoverable()) {
                    throw first;
                }
                recover(first);
                if (!repeatable) {
                    throw new BrowserException(first.kind(), "Verbindung zum Browser verloren; die Aktion wurde nicht "
                            + "wiederholt, weil sie schon gewirkt haben kann. Bitte Zustand mit browser_tabs prüfen.",
                            first);
                }
                ensureConnected();
                try {
                    return operation.run(connection);
                } catch (BrowserException second) {
                    if (second.isRecoverable()) {
                        recover(second);
                    }
                    throw second;
                }
            }
        }

        private void ensureConnected() throws BrowserException {
            if (connection != null && connection.isOpen()) {
                return;
            }
            dropConnection();
            if (instance != null && instance.isAlive()) {
                try {
                    connection = instance.connect();
                    connection.setHumanEmulation(humanKeys, humanMouse);
                    return;
                } catch (BrowserException e) {
                    if (!e.isRecoverable()) {
                        throw e;
                    }
                    // Reconnect failed although the process seemed alive: fall through to a restart.
                }
            }
            stopInstance();
            instance = engine.start(profiles.profileOf(conversation));
            try {
                connection = instance.connect();
                connection.setHumanEmulation(humanKeys, humanMouse);
            } catch (BrowserException e) {
                stopInstance();
                throw e;
            }
        }

        private void recover(BrowserException failure) {
            dropConnection();
            if (failure.kind() == BrowserException.Kind.BROWSER_CLOSED) {
                stopInstance();
            }
        }

        private void dropConnection() {
            if (connection != null) {
                connection.disconnect();
                connection = null;
            }
        }

        private void stopInstance() {
            if (instance != null) {
                instance.stop();
                instance = null;
            }
            // New process, new raw context ids: the old tab ids no longer mean anything.
            tabIds.clear();
            snapshots.clear();
            controlSnapshots.clear();
            history.clear();
            currentRaw = null;
        }

        private List<BrowserTab> listTabs(BrowserConnection connection) throws BrowserException {
            List<RawTab> raw = sync(connection);
            List<BrowserTab> tabs = new ArrayList<BrowserTab>(raw.size());
            for (RawTab tab : raw) {
                tabs.add(new BrowserTab(idOf(tab.contextId()), tab.title(), tab.url(),
                        tab.contextId().equals(currentRaw)));
            }
            return tabs;
        }

        /** Reads the tree, forgets closed tabs, numbers new ones and settles the current tab. */
        private List<RawTab> sync(BrowserConnection connection) throws BrowserException {
            List<RawTab> raw = connection.tabs();
            Set<String> present = new HashSet<String>();
            for (RawTab tab : raw) {
                present.add(tab.contextId());
                idOf(tab.contextId());
            }
            tabIds.keySet().retainAll(present);
            if (currentRaw == null || !present.contains(currentRaw)) {
                currentRaw = raw.isEmpty() ? null : raw.get(0).contextId();
            }
            return raw;
        }

        private String idOf(String raw) {
            String id = tabIds.get(raw);
            if (id == null) {
                id = "tab-" + nextTab++;
                tabIds.put(raw, id);
            }
            return id;
        }

        private String rawOf(BrowserConnection connection, String tabId) throws BrowserException {
            sync(connection);
            for (Map.Entry<String, String> entry : tabIds.entrySet()) {
                if (entry.getValue().equals(tabId)) {
                    return entry.getKey();
                }
            }
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                    "Unbekannter Tab " + tabId + "; aktuelle Tabs liefert browser_tabs");
        }

        private String currentOrFail(BrowserConnection connection) throws BrowserException {
            sync(connection);
            if (currentRaw == null) {
                throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                        "Kein Tab offen; zuerst browser_navigate aufrufen");
            }
            return currentRaw;
        }

        /** The current tab; only a browser without any tab gets a new one. */
        private String currentOrNew(BrowserConnection connection) throws BrowserException {
            sync(connection);
            if (currentRaw == null) {
                currentRaw = connection.createTab();
                idOf(currentRaw);
            }
            return currentRaw;
        }

        private BrowserTab tabOf(BrowserConnection connection, String raw) throws BrowserException {
            for (BrowserTab tab : listTabs(connection)) {
                if (tab.id().equals(tabIds.get(raw))) {
                    return tab;
                }
            }
            return new BrowserTab(idOf(raw), "", "", raw.equals(currentRaw));
        }

        private BrowserPage page(BrowserConnection connection, String raw) throws BrowserException {
            RawPage page = connection.read(raw);
            return new BrowserPage(idOf(raw), page.url(), page.title(), page.text());
        }
    }

    /** The link ids of one page of one tab. */
    private static final class LinkSnapshot {

        final String pageUrl;
        final String document;
        final Map<String, String> urls = new HashMap<String, String>();

        LinkSnapshot(String pageUrl, String document) {
            this.pageUrl = pageUrl;
            this.document = document;
        }
    }

    /** The controls of one document of one tab: snapshot id to the engine's control (target already vetted as url). */
    private static final class ControlSnapshot {

        final String document;
        final Map<String, RawControl> controls = new HashMap<String, RawControl>();

        ControlSnapshot(String document) {
            this.document = document;
        }

        /** The ids in snapshot order ({@code ctl-1}, {@code ctl-2}, ...). */
        List<String> orderedIds() {
            List<String> ids = new ArrayList<String>(controls.keySet());
            java.util.Collections.sort(ids, (a, b) -> Integer.compare(number(a), number(b)));
            return ids;
        }

        private static int number(String id) {
            try {
                return Integer.parseInt(id.substring(id.lastIndexOf('-') + 1));
            } catch (RuntimeException e) {
                return Integer.MAX_VALUE;
            }
        }
    }

    /** The address if it is an absolute http(s) address with a host, else {@code null} (mailto:, javascript:, ...). */
    static String usableUrl(String href) {
        if (href == null || href.trim().isEmpty()) {
            return null;
        }
        try {
            URI uri = new URI(href.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
                return null;
            }
            return uri.toString();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static final List<String> DOCUMENT_ENDINGS = java.util.Arrays.asList(".pdf", ".doc", ".docx", ".xls",
            ".xlsx", ".ppt", ".pptx", ".odt", ".ods", ".odp", ".rtf");

    /** Whether the path of {@code url} names a document (PDF, Office file). */
    static boolean isDocumentPath(String url) {
        try {
            String path = new URI(url).getPath();
            String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
            for (String ending : DOCUMENT_ENDINGS) {
                if (lower.endsWith(ending)) {
                    return true;
                }
            }
        } catch (URISyntaxException ignored) {
            // Not a document address.
        }
        return false;
    }

    private static BrowserBatchPage outcomeOf(String url, BrowserException e) {
        return e.kind() == BrowserException.Kind.BLOCKED ? BrowserBatchPage.skipped(url, e.getMessage())
                : BrowserBatchPage.failed(url, e.getMessage());
    }

    /** The last path segment of {@code url}, decoded, or {@code dokument}. */
    static String fileNameOf(String url) {
        try {
            String path = new URI(url).getPath();
            if (path != null) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (!name.isEmpty()) {
                    return name;
                }
            }
        } catch (URISyntaxException ignored) {
            // Fall through to the default name.
        }
        return "dokument";
    }

    static String withoutFragment(String url) {
        if (url == null) {
            return "";
        }
        int hash = url.indexOf('#');
        return hash < 0 ? url : url.substring(0, hash);
    }

    private static String clip(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_LINK_TEXT ? flat : flat.substring(0, MAX_LINK_TEXT - 1) + "…";
    }
}
