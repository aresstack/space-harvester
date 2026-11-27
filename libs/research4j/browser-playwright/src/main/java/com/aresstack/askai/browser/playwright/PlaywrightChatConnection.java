package com.aresstack.askai.browser.playwright;

import com.aresstack.enterpriseai.browser.api.BrowserAction;
import com.aresstack.enterpriseai.browser.api.BrowserException;
import com.aresstack.enterpriseai.browser.api.BrowserProbe;
import com.aresstack.enterpriseai.browser.api.engine.BrowserConnection;
import com.aresstack.enterpriseai.browser.api.engine.RawActOutcome;
import com.aresstack.enterpriseai.browser.api.engine.RawControl;
import com.aresstack.enterpriseai.browser.api.engine.RawDownload;
import com.aresstack.enterpriseai.browser.api.engine.RawLink;
import com.aresstack.enterpriseai.browser.api.engine.RawPage;
import com.aresstack.enterpriseai.browser.api.engine.RawScreenshot;
import com.aresstack.enterpriseai.browser.api.engine.RawTab;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.Clip;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitUntilState;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.aresstack.askai.browser.playwright.ChatPageScripts.*;

/**
 * The engine connection of the chat browser on Playwright: the Firefox adapter of the Enterprise AI Client
 * ({@code BidiBrowserConnection}) carried over to Playwright, with the same page scripts, the same waiting rules
 * (settle, SSO redirects, parallel loads) and the same real input (pointer click on the centre, typed keys, Enter).
 * Tabs are the pages of the persistent context; their ids are local to this browser. Playwright is not thread-safe,
 * so every call holds the lock of its browser instance.
 */
final class PlaywrightChatConnection implements BrowserConnection {

    /** A short hold between press and release, so handlers that look at the press see it. */
    static final int HOLD_MILLIS = 40;
    /** JPEG qualities tried in turn until the picture fits. */
    static final List<Integer> SHOT_QUALITIES = Collections.unmodifiableList(Arrays.asList(75, 55, 35, 20));
    private static final Gson GSON = new Gson();

    private final PlaywrightChatEngine.Instance instance;
    private final BrowserContext context;
    private final long navigationTimeoutMillis;
    private final Map<Page, String> ids = new IdentityHashMap<>();
    private final Map<String, Page> pages = new HashMap<>();
    private int nextId;
    private volatile boolean disconnected;
    /** Human variation of input, scrolling and reading; off unless the chat switched it on. */
    private volatile HumanMotion human = HumanMotion.off();
    /** Last pointer position per tab, start of the next mouse path. */
    private final Map<String, double[]> pointers = new ConcurrentHashMap<>();

    PlaywrightChatConnection(PlaywrightChatEngine.Instance instance, BrowserContext context,
                             long navigationTimeoutMillis) {
        this.instance = instance;
        this.context = context;
        this.navigationTimeoutMillis = navigationTimeoutMillis;
    }

    @Override
    public boolean isOpen() {
        return !disconnected && instance.isAlive();
    }

    @Override
    public List<RawTab> tabs() throws BrowserException {
        synchronized (instance) {
            check();
            try {
                List<RawTab> tabs = new ArrayList<>();
                for (Page page : context.pages()) {
                    String id = idOf(page);
                    tabs.add(new RawTab(id, page.url(), title(id)));
                }
                return tabs;
            } catch (PlaywrightException e) {
                throw classify(e);
            }
        }
    }

    @Override
    public String createTab() throws BrowserException {
        synchronized (instance) {
            check();
            try {
                return idOf(context.newPage());
            } catch (PlaywrightException e) {
                throw classify(e);
            }
        }
    }

    @Override
    public void activate(String contextId) throws BrowserException {
        synchronized (instance) {
            check();
            try {
                page(contextId).bringToFront();
            } catch (PlaywrightException e) {
                throw classify(e);
            }
        }
    }

    @Override
    public void close(String contextId) throws BrowserException {
        synchronized (instance) {
            check();
            try {
                Page page = page(contextId);
                page.close();
                pages.remove(contextId);
                ids.remove(page);
                pointers.remove(contextId);
            } catch (PlaywrightException e) {
                throw classify(e);
            }
        }
    }

    @Override
    public void navigate(String contextId, String url) throws BrowserException {
        synchronized (instance) {
            check();
            String before = tokenOrNull(contextId);
            try {
                page(contextId).navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD)
                        .setTimeout(navigationTimeoutMillis));
            } catch (PlaywrightException e) {
                if (!isAbortedByRedirect(e)) {
                    throw classify(e);
                }
                // A script or form redirect (SAML/SSO bounce) replaced the requested load: wait for the final
                // page; a stop without replacement still fails.
                check();
                settleRedirect(contextId, before, originOf(url), e);
                return;
            }
            // Load is not ready: intranets and single page apps render their content after the load event.
            settle(contextId, null, LOAD_SETTLE_MILLIS);
            humanize(contextId);
        }
    }

    @Override
    public void setHumanEmulation(boolean keys, boolean mouse) {
        HumanMotion now = human;
        if (now.keys() != keys || now.mouse() != mouse) {
            human = new HumanMotion(new Random(), keys, mouse);
        }
    }

    /**
     * After a load with Human Emulation on: scrolls through the page in a few uneven steps (lazy content appears),
     * lets the pointer wander over the text when mouse movements are on, scrolls back to the top and pauses as a
     * reader would. Only inside the tab; failures never fail the load.
     */
    private void humanize(String contextId) {
        HumanMotion now = human;
        if (!now.keys() && !now.mouse()) {
            return;
        }
        try {
            JsonArray size = JsonParser.parseString(evaluate(contextId, PAGE_SIZE_SCRIPT)).getAsJsonArray();
            int height = size.get(0).getAsInt();
            int viewport = size.get(1).getAsInt();
            int text = size.get(2).getAsInt();
            for (int step : now.scrollSteps(height, viewport)) {
                evaluate(contextId, "window.scrollBy(0, " + step + "), ''");
                sleep(now.scrollPause());
            }
            if (now.mouse()) {
                double[] at = pointerOf(contextId, now);
                move(page(contextId), now.wander(at[0], at[1]));
            }
            if (now.keys()) {
                evaluate(contextId, "window.scrollTo(0, 0), ''");
                sleep(now.readingPause(text));
            }
        } catch (BrowserException | RuntimeException e) {
            // Emulation is decoration; the page is loaded either way.
        }
    }

    private double[] pointerOf(String contextId, HumanMotion now) {
        return pointers.computeIfAbsent(contextId, id -> now.startPoint());
    }

    private static void move(Page page, List<HumanMotion.Step> steps) throws BrowserException {
        for (HumanMotion.Step step : steps) {
            page.mouse().move(step.x(), step.y());
            sleep(step.pauseMillis());
        }
    }

    private static void sleep(long millis) throws BrowserException {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrowserException(BrowserException.Kind.FAILED, "Warten unterbrochen", e);
        }
    }

    @Override
    public Map<String, String> navigateAll(Map<String, String> urlByContext) throws BrowserException {
        synchronized (instance) {
            check();
            Map<String, String> failures = new LinkedHashMap<>();
            Map<String, String> before = new HashMap<>();
            for (Map.Entry<String, String> entry : urlByContext.entrySet()) {
                String contextId = entry.getKey();
                before.put(contextId, tokenOrNull(contextId));
                try {
                    // Only start the load, so all tabs load at the same time; the waiting below decides.
                    page(contextId).evaluate("u => { window.location.assign(u); }", entry.getValue());
                } catch (PlaywrightException e) {
                    BrowserException failure = classify(e);
                    if (failure.kind() == BrowserException.Kind.BROWSER_CLOSED
                            || failure.kind() == BrowserException.Kind.CONNECTION_LOST) {
                        throw failure;
                    }
                    if (!isAbortedByRedirect(e)) {
                        failures.put(contextId, failure.getMessage());
                    }
                }
            }
            Map<String, String> last = new HashMap<>();
            Map<String, Long> quietSince = new HashMap<>();
            Set<String> pending = new LinkedHashSet<>(urlByContext.keySet());
            pending.removeAll(failures.keySet());
            Set<String> replaced = new HashSet<>();
            long deadline = System.currentTimeMillis() + REDIRECT_SETTLE_MILLIS;
            while (!pending.isEmpty() && System.currentTimeMillis() < deadline) {
                sleep(POLL_MILLIS);
                for (Iterator<String> it = pending.iterator(); it.hasNext(); ) {
                    String contextId = it.next();
                    JsonArray state;
                    try {
                        state = JsonParser.parseString(evaluate(contextId, STATE_SCRIPT)).getAsJsonArray();
                    } catch (BrowserException | RuntimeException e) {
                        // Between two documents there is nothing to evaluate in; only a gone browser ends the wait.
                        check();
                        continue;
                    }
                    if (!state.get(0).getAsString().equals(before.get(contextId))) {
                        replaced.add(contextId);
                    }
                    String now = state.toString();
                    long time = System.currentTimeMillis();
                    if (!now.equals(last.get(contextId))) {
                        last.put(contextId, now);
                        quietSince.put(contextId, time);
                    } else if (isLoadSettled(replaced.contains(contextId),
                            "complete".equals(state.get(1).getAsString()), time - quietSince.get(contextId))) {
                        it.remove();
                    }
                }
            }
            for (String contextId : pending) {
                if (!replaced.contains(contextId)) {
                    failures.put(contextId, "Seite hat nicht rechtzeitig geladen");
                }
            }
            for (String contextId : urlByContext.keySet()) {
                if (!failures.containsKey(contextId)) {
                    humanize(contextId);
                }
            }
            return failures;
        }
    }

    /** The decision of {@link #navigateAll} for one tab: a new document arrived, is complete and quiet. */
    static boolean isLoadSettled(boolean newDocument, boolean complete, long quietMillis) {
        return newDocument && complete && quietMillis >= QUIET_MILLIS;
    }

    private String tokenOrNull(String contextId) {
        try {
            return evaluate(contextId, DOCUMENT_TOKEN_SCRIPT);
        } catch (BrowserException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Waits after an aborted load until a replacement navigation arrived: the tab left {@code returnOrigin} (SSO
     * provider) or showed at least two new documents (launch page plus target), and is then quiet and complete back
     * on {@code returnOrigin}. A tab that left and stays away (login page) is taken after
     * {@link ChatPageScripts#REDIRECT_SETTLE_MILLIS}; one that never saw a replacement fails with the original abort.
     */
    private void settleRedirect(String contextId, String before, String returnOrigin, PlaywrightException abort)
            throws BrowserException {
        long deadline = System.currentTimeMillis() + REDIRECT_SETTLE_MILLIS;
        Set<String> documents = new HashSet<>();
        boolean left = false;
        String last = null;
        long quietSince = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            sleep(POLL_MILLIS);
            JsonArray state;
            try {
                state = JsonParser.parseString(evaluate(contextId, STATE_SCRIPT)).getAsJsonArray();
            } catch (BrowserException | RuntimeException e) {
                check();
                continue;
            }
            String token = state.get(0).getAsString();
            String origin = state.get(3).getAsString();
            if (!token.equals(before)) {
                documents.add(token);
            }
            left |= returnOrigin != null && !returnOrigin.equals(origin);
            String now = state.toString();
            if (!now.equals(last)) {
                last = now;
                quietSince = System.currentTimeMillis();
            } else if (isRedirectSettled(left, documents.size(), "complete".equals(state.get(1).getAsString()),
                    System.currentTimeMillis() - quietSince, returnOrigin == null || returnOrigin.equals(origin))) {
                return;
            }
        }
        if (!left && documents.size() < 2) {
            throw classify(abort);
        }
    }

    /** The decision of {@link #settleRedirect}: a replacement arrived and the page is quiet back on the origin. */
    static boolean isRedirectSettled(boolean left, int newDocuments, boolean complete, long quietMillis,
                                     boolean onReturnOrigin) {
        return (left || newDocuments >= 2) && complete && quietMillis >= QUIET_MILLIS && onReturnOrigin;
    }

    @Override
    public RawPage read(String contextId) throws BrowserException {
        synchronized (instance) {
            check();
            JsonArray values = parse(evaluate(contextId, READ_SCRIPT));
            return new RawPage(values.get(0).getAsString(), values.get(1).getAsString(), values.get(2).getAsString());
        }
    }

    @Override
    public List<RawLink> links(String contextId) throws BrowserException {
        synchronized (instance) {
            check();
            JsonArray values = parse(evaluate(contextId, LINKS_SCRIPT));
            List<RawLink> links = new ArrayList<>(values.size());
            for (JsonElement value : values) {
                JsonArray link = value.getAsJsonArray();
                links.add(new RawLink(link.get(0).getAsString(), link.get(1).getAsString(), link.get(2).getAsString(),
                        link.get(3).getAsString(), link.get(4).getAsString()));
            }
            return links;
        }
    }

    @Override
    public List<RawControl> controls(String contextId) throws BrowserException {
        synchronized (instance) {
            check();
            JsonArray values = parse(evaluate(contextId, CONTROLS_SCRIPT));
            List<RawControl> controls = new ArrayList<>(values.size());
            for (JsonElement value : values) {
                JsonArray c = value.getAsJsonArray();
                controls.add(new RawControl(c.get(0).getAsString(), c.get(1).getAsString(), c.get(2).getAsString(),
                        c.get(3).getAsString(), c.get(4).getAsString(), c.get(5).getAsString(), c.get(6).getAsString(),
                        c.get(7).getAsBoolean(), c.get(8).getAsString(), c.get(9).getAsString()));
            }
            return controls;
        }
    }

    @Override
    public RawActOutcome act(String contextId, String ref, BrowserAction action, String value)
            throws BrowserException {
        synchronized (instance) {
            check();
            String before = evaluate(contextId, DOCUMENT_TOKEN_SCRIPT);
            if (action != BrowserAction.SELECT && actReally(contextId, ref, action, value, before)) {
                return settle(contextId, before, SETTLE_TIMEOUT_MILLIS);
            }
            String script = ACT_FUNCTION + "(" + GSON.toJson(ref) + ", "
                    + GSON.toJson(action.name().toLowerCase(Locale.ROOT)) + ", " + GSON.toJson(value) + ")";
            String answer;
            try {
                answer = evaluate(contextId, script);
            } catch (BrowserException | RuntimeException e) {
                // A submit can replace the document before the evaluation answers; whether the action worked shows
                // in the settled state below. A browser that is gone still fails there.
                check();
                answer = "ok";
            }
            if ("missing".equals(answer)) {
                throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                        "Das Bedienelement ist nicht mehr auf der Seite; browser_controls erneut aufrufen");
            }
            if ("disabled".equals(answer)) {
                throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Das Bedienelement ist deaktiviert");
            }
            if ("nofield".equals(answer) && action == BrowserAction.SELECT) {
                return selectCustom(contextId, ref, value, before);
            }
            if ("nofield".equals(answer)) {
                throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                        "Diese Aktion passt nicht zum Bedienelement (fill nur für Eingabefelder, select nur für "
                                + "Auswahllisten)");
            }
            if ("nooption".equals(answer)) {
                throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Keine passende Option: " + value);
            }
            return settle(contextId, before, SETTLE_TIMEOUT_MILLIS);
        }
    }

    /**
     * Chooses {@code value} in a list box that is not a native select (ARIA combobox, menu button): opens it like a
     * person, then clicks the visible option whose text matches best.
     */
    private RawActOutcome selectCustom(String contextId, String ref, String value, String before)
            throws BrowserException {
        if (!actReally(contextId, ref, BrowserAction.SELECT, value, before)) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                    "Die Auswahlliste lässt sich nicht öffnen; browser_controls erneut aufrufen");
        }
        settle(contextId, before, OPTION_WAIT_MILLIS);
        String option = evaluate(contextId, OPTION_FUNCTION + "(" + GSON.toJson(ref) + ", " + GSON.toJson(value) + ")");
        if (option.isEmpty()) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Keine passende Option: " + value);
        }
        if (!actReally(contextId, option, BrowserAction.CLICK, null, before)) {
            evaluate(contextId, ACT_FUNCTION + "(" + GSON.toJson(option) + ", \"click\", null)");
        }
        return settle(contextId, before, SETTLE_TIMEOUT_MILLIS);
    }

    /**
     * Performs a click, fill or submit as real input, like a person: a pointer click on the element's centre, then
     * typed keys (select all, delete, the value, Enter for submit). Answers {@code false} when real input is not
     * possible here (element off screen), so the scripted action runs instead; fails when the element is gone,
     * disabled or covered.
     */
    private boolean actReally(String contextId, String ref, BrowserAction action, String value, String before)
            throws BrowserException {
        JsonArray prepared = parse(evaluate(contextId, PREPARE_FUNCTION + "(" + GSON.toJson(ref) + ")"));
        String status = prepared.get(0).getAsString();
        if ("missing".equals(status)) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                    "Das Bedienelement ist nicht mehr auf der Seite; browser_controls erneut aufrufen");
        }
        if ("disabled".equals(status)) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Das Bedienelement ist deaktiviert");
        }
        if ("covered".equals(status)) {
            String cover = prepared.get(4).getAsString();
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Das Bedienelement ist verdeckt"
                    + (cover.isEmpty() ? "" : " von „" + cover + "“")
                    + "; zuerst das überdeckende Element schließen (z. B. Banner), dann browser_controls erneut");
        }
        if (!"ok".equals(status)) {
            return false;
        }
        boolean field = prepared.get(3).getAsBoolean();
        if (action == BrowserAction.FILL && !field) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                    "Diese Aktion passt nicht zum Bedienelement (fill nur für Eingabefelder, select nur für "
                            + "Auswahllisten)");
        }
        long x = Math.round(prepared.get(1).getAsDouble());
        long y = Math.round(prepared.get(2).getAsDouble());
        boolean typing = field && action != BrowserAction.CLICK && value != null;
        Page page = page(contextId);
        try {
            HumanMotion now = human;
            double[] from = pointerOf(contextId, now);
            move(page, now.path(from[0], from[1], x, y));
            page.mouse().down();
            sleep(now.holdMillis(HOLD_MILLIS));
            page.mouse().up();
            pointers.put(contextId, new double[]{x, y});
            if (typing && value.length() <= MAX_TYPED_CHARS) {
                typeReplacing(page, value, now);
            }
            if (typing && !value.equals(evaluate(contextId, VALUE_FUNCTION + "(" + GSON.toJson(ref) + ")"))) {
                // The field rejected or reformatted the keys (masks, long text); set the value as a script would,
                // then still send Enter as a real key.
                evaluate(contextId, ACT_FUNCTION + "(" + GSON.toJson(ref) + ", \"fill\", " + GSON.toJson(value) + ")");
            }
            if (field && action == BrowserAction.SUBMIT) {
                page.keyboard().press("Enter");
            }
        } catch (PlaywrightException e) {
            // A submit may replace the document while the input is answered; then the settled state shows the
            // effect. Any other failure means the input may not have happened, so the scripted action runs instead.
            check();
            return documentReplaced(contextId, before);
        }
        return true;
    }

    /** Selects the focused field's content, deletes it and types {@code text} key by key with the human pauses. */
    private static void typeReplacing(Page page, String text, HumanMotion human) throws BrowserException {
        page.keyboard().press("ControlOrMeta+A");
        page.keyboard().press("Backspace");
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            page.keyboard().type(new String(Character.toChars(codePoint)));
            sleep(human.keyPause());
            i += Character.charCount(codePoint);
        }
    }

    /** Whether the tab shows another document than {@code before}, or is between two documents right now. */
    private boolean documentReplaced(String contextId, String before) {
        try {
            return !before.equals(evaluate(contextId, DOCUMENT_TOKEN_SCRIPT));
        } catch (BrowserException | RuntimeException e) {
            return true;
        }
    }

    /**
     * Waits until a new document is complete and quiet, or the old one had no DOM change for
     * {@link ChatPageScripts#QUIET_MILLIS}; at most {@code timeoutMillis}, after which the page is taken as it is.
     * {@code before} is the document token before an action, {@code null} after a navigation.
     */
    private RawActOutcome settle(String contextId, String before, long timeoutMillis) throws BrowserException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        String last = null;
        long quietSince = System.currentTimeMillis();
        boolean navigated = false;
        boolean changed = false;
        while (System.currentTimeMillis() < deadline) {
            sleep(POLL_MILLIS);
            JsonArray state;
            try {
                state = JsonParser.parseString(evaluate(contextId, STATE_SCRIPT)).getAsJsonArray();
            } catch (BrowserException | RuntimeException e) {
                check();
                continue;
            }
            String token = state.get(0).getAsString();
            boolean complete = "complete".equals(state.get(1).getAsString());
            navigated |= before != null && !token.equals(before);
            changed |= state.get(2).getAsLong() > 0;
            String now = state.toString();
            if (!now.equals(last)) {
                last = now;
                quietSince = System.currentTimeMillis();
            } else if (complete && System.currentTimeMillis() - quietSince >= QUIET_MILLIS) {
                break;
            }
        }
        return new RawActOutcome(navigated, changed);
    }

    /** {@code scheme://host[:port]} of {@code url} as {@code location.origin} reports it, or {@code null}. */
    static String originOf(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            boolean usual = port < 0 || ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
            return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (usual ? "" : ":" + port);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Whether {@code e} only says that the requested load was replaced by another one: a page that redirects by
     * script or form post before it finished loading (Chromium {@code net::ERR_ABORTED}, Firefox
     * {@code NS_BINDING_ABORTED}, Playwright "interrupted by another navigation").
     */
    static boolean isAbortedByRedirect(RuntimeException e) {
        String message = e.getMessage();
        return message != null && (message.contains("NS_BINDING_ABORTED") || message.contains("net::ERR_ABORTED")
                || message.contains("interrupted by another navigation"));
    }

    @Override
    public String search(String contextId, String query, List<String> engines) throws BrowserException {
        synchronized (instance) {
            check();
            Page page = page(contextId);
            // The search of the research browser (engine catalog, SERP extraction, consent, challenge) on this tab.
            // Which addresses the chat may open stays with the chat tools' access guard; the search engines are
            // the host's configured ones.
            Playwright4jDriver driver = Playwright4jDriver.onChatTab(context, page,
                    (int) Math.min(Integer.MAX_VALUE, navigationTimeoutMillis), instance.searchSettings.consent,
                    instance.searchSettings.captcha, instance.camoufox);
            driver.setRenderedCapture(new RenderedPageDocumentCapture(instance.searchSettings.analysis));
            PlaywrightBrowserSession session = new PlaywrightBrowserSession(driver,
                    com.aresstack.askai.browser.UrlSafetyPolicy.allowingPrivateNetworks(),
                    com.aresstack.askai.browser.BrowserLimits.defaults(), instance.testSearchUrlTemplate, null,
                    instance.searchSettings);
            if (instance.testSearchUrlTemplate != null) {
                session.setDomainKeyResolver(new com.aresstack.askai.browser.domain.HostPortDomainKeyResolver());
            }
            try {
                return BrowserToolSet.renderSearch(session.search(query, engines), session.challengeStatus());
            } catch (com.aresstack.askai.browser.BrowserException e) {
                throw new BrowserException(BrowserException.Kind.FAILED, "Websuche fehlgeschlagen: " + e.getMessage(),
                        e);
            } catch (PlaywrightException e) {
                throw classify(e);
            }
        }
    }

    @Override
    public RawScreenshot screenshot(String contextId, Map<String, String> marks, String clipRef, int maxBytes)
            throws BrowserException {
        synchronized (instance) {
            check();
            String prepared = evaluate(contextId, SHOT_PREPARE_FUNCTION + "("
                    + GSON.toJson(marks == null ? new LinkedHashMap<String, String>() : marks) + ", "
                    + GSON.toJson(clipRef) + ", " + SHOT_CLIP_MARGIN + ")");
            if ("missing".equals(prepared)) {
                throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                        "Das Bedienelement ist nicht mehr auf der Seite; browser_controls erneut aufrufen");
            }
            JsonArray values = parse(prepared);
            List<String> visible = new ArrayList<>();
            for (JsonElement ref : values.get(0).getAsJsonArray()) {
                visible.add(ref.getAsString());
            }
            Clip clip = null;
            if (!values.get(1).isJsonNull()) {
                JsonArray box = values.get(1).getAsJsonArray();
                clip = new Clip(box.get(0).getAsInt(), box.get(1).getAsInt(), box.get(2).getAsInt(),
                        box.get(3).getAsInt());
            }
            try {
                // JPEG keeps a page picture small; a busy page on a high-density screen gets lower quality steps.
                for (int quality : SHOT_QUALITIES) {
                    Page.ScreenshotOptions options = new Page.ScreenshotOptions().setType(ScreenshotType.JPEG)
                            .setQuality(quality);
                    if (clip != null) {
                        options.setClip(clip);
                    }
                    byte[] image = page(contextId).screenshot(options);
                    if (image == null || image.length == 0) {
                        throw new BrowserException(BrowserException.Kind.FAILED, "Der Browser lieferte kein Bild");
                    }
                    if (image.length <= maxBytes) {
                        return new RawScreenshot("image/jpeg", image, visible);
                    }
                }
                throw new BrowserException(BrowserException.Kind.FAILED, "Bildschirmfoto zu groß (über "
                        + maxBytes / 1024 + " KB); einen Ausschnitt um ein Bedienelement anfordern");
            } catch (PlaywrightException e) {
                throw classify(e);
            } finally {
                try {
                    evaluate(contextId, SHOT_CLEANUP_SCRIPT);
                } catch (BrowserException | RuntimeException ignored) {
                    // The layer is pointer-transparent and goes with the document anyway.
                }
            }
        }
    }

    @Override
    public RawDownload fetch(String contextId, String url, int maxBytes) throws BrowserException {
        synchronized (instance) {
            check();
            JsonArray values = parse(evaluate(contextId, FETCH_FUNCTION + "(" + GSON.toJson(url) + ", " + maxBytes
                    + ")"));
            if (!"ok".equals(values.get(0).getAsString())) {
                throw new BrowserException(BrowserException.Kind.FAILED,
                        "Dokument konnte nicht geladen werden: " + values.get(1).getAsString());
            }
            return new RawDownload(values.get(1).getAsString(), values.get(2).getAsString(),
                    java.util.Base64.getDecoder().decode(values.get(3).getAsString()));
        }
    }

    @Override
    public BrowserProbe probe(String contextId) throws BrowserException {
        synchronized (instance) {
            check();
            JsonArray values = parse(evaluate(contextId, PROBE_SCRIPT));
            return new BrowserProbe(values.get(0).getAsBoolean(), values.get(1).getAsString(),
                    values.get(2).getAsString(), values.get(3).getAsString());
        }
    }

    @Override
    public String documentToken(String contextId) throws BrowserException {
        synchronized (instance) {
            check();
            return evaluate(contextId, DOCUMENT_TOKEN_SCRIPT);
        }
    }

    @Override
    public void disconnect() {
        // The browser keeps running; only this connection ends.
        disconnected = true;
    }

    private String title(String contextId) {
        try {
            return page(contextId).title();
        } catch (BrowserException | RuntimeException e) {
            // A tab that is still loading or shows an error page has no readable title; the list stays usable.
            return "";
        }
    }

    private String idOf(Page page) {
        String id = ids.get(page);
        if (id == null) {
            id = "tab-" + (++nextId);
            ids.put(page, id);
            pages.put(id, page);
        }
        return id;
    }

    private Page page(String contextId) throws BrowserException {
        Page page = pages.get(contextId);
        if (page == null || page.isClosed()) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Unbekannter Tab: " + contextId);
        }
        return page;
    }

    private String evaluate(String contextId, String script) throws BrowserException {
        try {
            Object value = page(contextId).evaluate(script);
            return value instanceof String ? (String) value : "";
        } catch (PlaywrightException e) {
            throw classify(e);
        }
    }

    private JsonArray parse(String json) throws BrowserException {
        try {
            return JsonParser.parseString(json).getAsJsonArray();
        } catch (RuntimeException e) {
            throw new BrowserException(BrowserException.Kind.FAILED, "Seite konnte nicht gelesen werden", e);
        }
    }

    private void check() throws BrowserException {
        if (!instance.isAlive()) {
            throw new BrowserException(BrowserException.Kind.BROWSER_CLOSED, "Der Browser wurde geschlossen");
        }
        if (disconnected) {
            throw new BrowserException(BrowserException.Kind.CONNECTION_LOST, "Verbindung zum Browser beendet");
        }
    }

    /** Process gone beats protocol errors. */
    private BrowserException classify(PlaywrightException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().split("\n")[0];
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("target page, context or browser has been closed") || lower.contains("browser has been closed")
                || lower.contains("target closed") && context.pages().isEmpty()) {
            instance.markClosed();
        }
        if (!instance.isAlive()) {
            return new BrowserException(BrowserException.Kind.BROWSER_CLOSED, "Der Browser wurde geschlossen", e);
        }
        if (lower.contains("timeout")) {
            return new BrowserException(BrowserException.Kind.TIMEOUT, "Der Browser hat nicht rechtzeitig geantwortet",
                    e);
        }
        if (lower.contains("invalid url") || lower.contains("cannot navigate")) {
            return new BrowserException(BrowserException.Kind.INVALID_REQUEST, message, e);
        }
        return new BrowserException(BrowserException.Kind.FAILED, message, e);
    }
}
