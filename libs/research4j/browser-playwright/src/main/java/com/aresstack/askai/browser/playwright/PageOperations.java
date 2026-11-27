package com.aresstack.askai.browser.playwright;

import com.aresstack.enterpriseai.browser.api.BrowserAction;
import com.aresstack.enterpriseai.browser.api.BrowserException;
import com.aresstack.enterpriseai.browser.api.BrowserProbe;
import com.aresstack.enterpriseai.browser.api.engine.RawActOutcome;
import com.aresstack.enterpriseai.browser.api.engine.RawControl;
import com.aresstack.enterpriseai.browser.api.engine.RawDownload;
import com.aresstack.enterpriseai.browser.api.engine.RawLink;
import com.aresstack.enterpriseai.browser.api.engine.RawPage;
import com.aresstack.enterpriseai.browser.api.engine.RawScreenshot;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static com.aresstack.askai.browser.playwright.ChatPageScripts.*;

/**
 * The page operations of the chat browser, carried over from the Enterprise AI Client ({@code BidiBrowserConnection}):
 * reading, links, controls, real input with settling, screenshots with marks, fetching, the document token and
 * navigation with the waiting rules (settle, SSO redirects, parallel loads, Human Emulation). They hold no tab state:
 * every operation works on the {@link Page} it is given, so the chat connection and the research driver use the same
 * code. The caller owns the page's thread (lock or owner thread); a {@link Guard} tells whether the browser is still
 * there and turns Playwright errors into the chat contract's {@link BrowserException}.
 */
final class PageOperations {

    /** A short hold between press and release, so handlers that look at the press see it. */
    static final int HOLD_MILLIS = 40;
    /** JPEG qualities tried in turn until the picture fits. */
    static final List<Integer> SHOT_QUALITIES = Collections.unmodifiableList(Arrays.asList(75, 55, 35, 20));
    private static final Gson GSON = new Gson();

    /** Whether the browser is still usable, and what a Playwright failure means for the caller. */
    interface Guard {
        /** Fails when the browser or the connection is gone. */
        void check() throws BrowserException;

        /** The contract's failure for {@code e}; a gone process beats protocol errors. */
        BrowserException classify(PlaywrightException e);
    }

    /** Resolves a tab id of the caller to its page; unknown or closed tabs fail. */
    interface Tabs {
        Page page(String contextId) throws BrowserException;
    }

    /** The last pointer position in one tab, start of the next mouse path; begins where Human Emulation puts it. */
    static final class Pointer {
        private double[] at;

        double[] at(HumanMotion human) {
            if (at == null) {
                at = human.startPoint();
            }
            return at;
        }

        void moveTo(double x, double y) {
            at = new double[]{x, y};
        }
    }

    private final Guard guard;
    private final long navigationTimeoutMillis;

    PageOperations(Guard guard, long navigationTimeoutMillis) {
        this.guard = guard;
        this.navigationTimeoutMillis = navigationTimeoutMillis;
    }

    /**
     * What the browser reaches instead of a page: a PDF shown in its viewer or a download. The research session
     * reads such documents ({@code web_open}); the chat hands them over here.
     */
    interface Documents {
        /** Pages only: a document fails like any other page that cannot be read. */
        Documents NONE = new Documents() {
            @Override
            public boolean downloadStarted(Page page, String url, PlaywrightException e) {
                return false;
            }

            @Override
            public boolean shown(Page page, com.microsoft.playwright.Response response) {
                return false;
            }
        };

        /** Navigating to {@code url} only started a download; true when it was taken as a document. */
        boolean downloadStarted(Page page, String url, PlaywrightException e) throws BrowserException;

        /** The response of the load; true when it is a document shown in the browser's viewer and was taken. */
        boolean shown(Page page, com.microsoft.playwright.Response response) throws BrowserException;
    }

    /**
     * Loads {@code url} and waits until the page is settled; a load that a script or form redirect (SAML/SSO bounce)
     * replaced waits for the final page instead of failing. A document instead of a page goes to {@code documents}.
     */
    void navigate(Page page, String url, HumanMotion human, Pointer pointer, Documents documents)
            throws BrowserException {
        String before = tokenOrNull(page);
        com.microsoft.playwright.Response response;
        try {
            response = page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD)
                    .setTimeout(navigationTimeoutMillis));
        } catch (PlaywrightException e) {
            if (documents.downloadStarted(page, url, e)) {
                return; // the tab keeps its page; the document was taken
            }
            if (!isAbortedByRedirect(e)) {
                throw guard.classify(e);
            }
            // A script or form redirect (SAML/SSO bounce) replaced the requested load: wait for the final
            // page; a stop without replacement still fails.
            guard.check();
            settleRedirect(page, before, originOf(url), e);
            return;
        }
        if (documents.shown(page, response)) {
            return; // the viewer shows a document: no page to settle or scroll through
        }
        // Load is not ready: intranets and single page apps render their content after the load event.
        settle(page, null, LOAD_SETTLE_MILLIS);
        humanize(page, human, pointer);
    }

    /**
     * After a load with Human Emulation on: scrolls through the page in a few uneven steps (lazy content appears),
     * lets the pointer wander over the text when mouse movements are on, scrolls back to the top and pauses as a
     * reader would. Only inside the tab; failures never fail the load.
     */
    void humanize(Page page, HumanMotion human, Pointer pointer) {
        if (!human.keys() && !human.mouse()) {
            return;
        }
        try {
            JsonArray size = JsonParser.parseString(evaluate(page, PAGE_SIZE_SCRIPT)).getAsJsonArray();
            int height = size.get(0).getAsInt();
            int viewport = size.get(1).getAsInt();
            int text = size.get(2).getAsInt();
            for (int step : human.scrollSteps(height, viewport)) {
                evaluate(page, "window.scrollBy(0, " + step + "), ''");
                sleep(human.scrollPause());
            }
            if (human.mouse()) {
                double[] at = pointer.at(human);
                move(page, human.wander(at[0], at[1]));
            }
            if (human.keys()) {
                evaluate(page, "window.scrollTo(0, 0), ''");
                sleep(human.readingPause(text));
            }
        } catch (BrowserException | RuntimeException e) {
            // Emulation is decoration; the page is loaded either way.
        }
    }

    /**
     * Starts all loads at once and waits until each tab shows a new, complete and quiet document. Answers the tabs
     * that failed with their reason; a gone browser fails the whole call.
     */
    Map<String, String> navigateAll(Tabs tabs, Map<String, String> urlByContext, HumanMotion human,
                                    Function<String, Pointer> pointers) throws BrowserException {
        Map<String, String> failures = new LinkedHashMap<>();
        Map<String, String> before = new HashMap<>();
        for (Map.Entry<String, String> entry : urlByContext.entrySet()) {
            String contextId = entry.getKey();
            before.put(contextId, tokenOrNull(tabs, contextId));
            try {
                // Only start the load, so all tabs load at the same time; the waiting below decides.
                tabs.page(contextId).evaluate("u => { window.location.assign(u); }", entry.getValue());
            } catch (PlaywrightException e) {
                BrowserException failure = guard.classify(e);
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
                    state = JsonParser.parseString(evaluate(tabs.page(contextId), STATE_SCRIPT)).getAsJsonArray();
                } catch (BrowserException | RuntimeException e) {
                    // Between two documents there is nothing to evaluate in; only a gone browser ends the wait.
                    guard.check();
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
                try {
                    humanize(tabs.page(contextId), human, pointers.apply(contextId));
                } catch (BrowserException e) {
                    // Emulation is decoration; a tab that went away meanwhile is still loaded as reported.
                }
            }
        }
        return failures;
    }

    /** The decision of {@link #navigateAll} for one tab: a new document arrived, is complete and quiet. */
    static boolean isLoadSettled(boolean newDocument, boolean complete, long quietMillis) {
        return newDocument && complete && quietMillis >= QUIET_MILLIS;
    }

    /**
     * Waits after an aborted load until a replacement navigation arrived: the tab left {@code returnOrigin} (SSO
     * provider) or showed at least two new documents (launch page plus target), and is then quiet and complete back
     * on {@code returnOrigin}. A tab that left and stays away (login page) is taken after
     * {@link ChatPageScripts#REDIRECT_SETTLE_MILLIS}; one that never saw a replacement fails with the original abort.
     */
    private void settleRedirect(Page page, String before, String returnOrigin, PlaywrightException abort)
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
                state = JsonParser.parseString(evaluate(page, STATE_SCRIPT)).getAsJsonArray();
            } catch (BrowserException | RuntimeException e) {
                guard.check();
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
            throw guard.classify(abort);
        }
    }

    /** The decision of {@link #settleRedirect}: a replacement arrived and the page is quiet back on the origin. */
    static boolean isRedirectSettled(boolean left, int newDocuments, boolean complete, long quietMillis,
                                     boolean onReturnOrigin) {
        return (left || newDocuments >= 2) && complete && quietMillis >= QUIET_MILLIS && onReturnOrigin;
    }

    /** Address, title and visible text of the page. */
    RawPage read(Page page) throws BrowserException {
        String json = evaluate(page, READ_SCRIPT);
        try {
            return readResult(json);
        } catch (RuntimeException e) {
            throw unreadable(e);
        }
    }

    /** The links of the page. */
    List<RawLink> links(Page page) throws BrowserException {
        String json = evaluate(page, LINKS_SCRIPT);
        try {
            return linksResult(json);
        } catch (RuntimeException e) {
            throw unreadable(e);
        }
    }

    /**
     * The answer of {@link ChatPageScripts#READ_SCRIPT}. The one reading of a page: the chat's browser_read and the
     * research driver's page state both parse it, whichever world the script ran in (main world, add-on content
     * script).
     */
    static RawPage readResult(String json) {
        JsonArray values = JsonParser.parseString(json).getAsJsonArray();
        return new RawPage(values.get(0).getAsString(), values.get(1).getAsString(), values.get(2).getAsString());
    }

    /** The answer of {@link ChatPageScripts#LINKS_SCRIPT}; the one link extraction of chat and research. */
    static List<RawLink> linksResult(String json) {
        JsonArray values = JsonParser.parseString(json).getAsJsonArray();
        List<RawLink> links = new ArrayList<>(values.size());
        for (JsonElement value : values) {
            JsonArray link = value.getAsJsonArray();
            links.add(new RawLink(link.get(0).getAsString(), link.get(1).getAsString(), link.get(2).getAsString(),
                    link.get(3).getAsString(), link.get(4).getAsString()));
        }
        return links;
    }

    private static BrowserException unreadable(RuntimeException e) {
        return new BrowserException(BrowserException.Kind.FAILED, "Seite konnte nicht gelesen werden", e);
    }

    /** The operable controls of the page (fields, buttons, lists), each with its reference for {@link #act}. */
    List<RawControl> controls(Page page) throws BrowserException {
        JsonArray values = parse(evaluate(page, CONTROLS_SCRIPT));
        List<RawControl> controls = new ArrayList<>(values.size());
        for (JsonElement value : values) {
            JsonArray c = value.getAsJsonArray();
            controls.add(new RawControl(c.get(0).getAsString(), c.get(1).getAsString(), c.get(2).getAsString(),
                    c.get(3).getAsString(), c.get(4).getAsString(), c.get(5).getAsString(), c.get(6).getAsString(),
                    c.get(7).getAsBoolean(), c.get(8).getAsString(), c.get(9).getAsString()));
        }
        return controls;
    }

    /**
     * Clicks, fills, submits or selects the control {@code ref} as real input where possible and waits until the
     * page settled; answers whether a new document arrived and whether the page changed.
     */
    RawActOutcome act(Page page, String ref, BrowserAction action, String value, HumanMotion human, Pointer pointer)
            throws BrowserException {
        String before = evaluate(page, DOCUMENT_TOKEN_SCRIPT);
        if (action != BrowserAction.SELECT && actReally(page, ref, action, value, before, human, pointer)) {
            return settle(page, before, SETTLE_TIMEOUT_MILLIS);
        }
        String script = ACT_FUNCTION + "(" + GSON.toJson(ref) + ", "
                + GSON.toJson(action.name().toLowerCase(Locale.ROOT)) + ", " + GSON.toJson(value) + ")";
        String answer;
        try {
            answer = evaluate(page, script);
        } catch (BrowserException | RuntimeException e) {
            // A submit can replace the document before the evaluation answers; whether the action worked shows
            // in the settled state below. A browser that is gone still fails there.
            guard.check();
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
            return selectCustom(page, ref, value, before, human, pointer);
        }
        if ("nofield".equals(answer)) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                    "Diese Aktion passt nicht zum Bedienelement (fill nur für Eingabefelder, select nur für "
                            + "Auswahllisten)");
        }
        if ("nooption".equals(answer)) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Keine passende Option: " + value);
        }
        return settle(page, before, SETTLE_TIMEOUT_MILLIS);
    }

    /**
     * Chooses {@code value} in a list box that is not a native select (ARIA combobox, menu button): opens it like a
     * person, then clicks the visible option whose text matches best.
     */
    private RawActOutcome selectCustom(Page page, String ref, String value, String before, HumanMotion human,
                                       Pointer pointer) throws BrowserException {
        if (!actReally(page, ref, BrowserAction.SELECT, value, before, human, pointer)) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                    "Die Auswahlliste lässt sich nicht öffnen; browser_controls erneut aufrufen");
        }
        settle(page, before, OPTION_WAIT_MILLIS);
        String option = evaluate(page, OPTION_FUNCTION + "(" + GSON.toJson(ref) + ", " + GSON.toJson(value) + ")");
        if (option.isEmpty()) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Keine passende Option: " + value);
        }
        if (!actReally(page, option, BrowserAction.CLICK, null, before, human, pointer)) {
            evaluate(page, ACT_FUNCTION + "(" + GSON.toJson(option) + ", \"click\", null)");
        }
        return settle(page, before, SETTLE_TIMEOUT_MILLIS);
    }

    /**
     * Performs a click, fill or submit as real input, like a person: a pointer click on the element's centre, then
     * typed keys (select all, delete, the value, Enter for submit). Answers {@code false} when real input is not
     * possible here (element off screen), so the scripted action runs instead; fails when the element is gone,
     * disabled or covered.
     */
    private boolean actReally(Page page, String ref, BrowserAction action, String value, String before,
                              HumanMotion human, Pointer pointer) throws BrowserException {
        JsonArray prepared = parse(evaluate(page, PREPARE_FUNCTION + "(" + GSON.toJson(ref) + ")"));
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
        try {
            double[] from = pointer.at(human);
            move(page, human.path(from[0], from[1], x, y));
            page.mouse().down();
            sleep(human.holdMillis(HOLD_MILLIS));
            page.mouse().up();
            pointer.moveTo(x, y);
            if (typing && value.length() <= MAX_TYPED_CHARS) {
                typeReplacing(page, value, human);
            }
            if (typing && !value.equals(evaluate(page, VALUE_FUNCTION + "(" + GSON.toJson(ref) + ")"))) {
                // The field rejected or reformatted the keys (masks, long text); set the value as a script would,
                // then still send Enter as a real key.
                evaluate(page, ACT_FUNCTION + "(" + GSON.toJson(ref) + ", \"fill\", " + GSON.toJson(value) + ")");
            }
            if (field && action == BrowserAction.SUBMIT) {
                page.keyboard().press("Enter");
            }
        } catch (PlaywrightException e) {
            // A submit may replace the document while the input is answered; then the settled state shows the
            // effect. Any other failure means the input may not have happened, so the scripted action runs instead.
            guard.check();
            return documentReplaced(page, before);
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

    /** Whether the page shows another document than {@code before}, or is between two documents right now. */
    private boolean documentReplaced(Page page, String before) {
        try {
            return !before.equals(evaluate(page, DOCUMENT_TOKEN_SCRIPT));
        } catch (BrowserException | RuntimeException e) {
            return true;
        }
    }

    /**
     * Waits until a new document is complete and quiet, or the old one had no DOM change for
     * {@link ChatPageScripts#QUIET_MILLIS}; at most {@code timeoutMillis}, after which the page is taken as it is.
     * {@code before} is the document token before an action, {@code null} after a navigation.
     */
    private RawActOutcome settle(Page page, String before, long timeoutMillis) throws BrowserException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        String last = null;
        long quietSince = System.currentTimeMillis();
        boolean navigated = false;
        boolean changed = false;
        while (System.currentTimeMillis() < deadline) {
            sleep(POLL_MILLIS);
            JsonArray state;
            try {
                state = JsonParser.parseString(evaluate(page, STATE_SCRIPT)).getAsJsonArray();
            } catch (BrowserException | RuntimeException e) {
                guard.check();
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

    /**
     * A JPEG of the visible page or of a cut-out around {@code clipRef}, with the controls in {@code marks} labelled;
     * the quality steps down until the picture fits {@code maxBytes}.
     */
    RawScreenshot screenshot(Page page, Map<String, String> marks, String clipRef, int maxBytes)
            throws BrowserException {
        String prepared = evaluate(page, SHOT_PREPARE_FUNCTION + "("
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
                byte[] image = page.screenshot(options);
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
            throw guard.classify(e);
        } finally {
            try {
                evaluate(page, SHOT_CLEANUP_SCRIPT);
            } catch (BrowserException | RuntimeException ignored) {
                // The layer is pointer-transparent and goes with the document anyway.
            }
        }
    }

    /** Loads {@code url} from inside the page, with the page's sign-in, up to {@code maxBytes}. */
    RawDownload fetch(Page page, String url, int maxBytes) throws BrowserException {
        JsonArray values = parse(evaluate(page, FETCH_FUNCTION + "(" + GSON.toJson(url) + ", " + maxBytes + ")"));
        if (!"ok".equals(values.get(0).getAsString())) {
            throw new BrowserException(BrowserException.Kind.FAILED,
                    "Dokument konnte nicht geladen werden: " + values.get(1).getAsString());
        }
        return new RawDownload(values.get(1).getAsString(), values.get(2).getAsString(),
                java.util.Base64.getDecoder().decode(values.get(3).getAsString()));
    }

    /** What the page says about itself for the access rules (sign-in form, origin, title). */
    BrowserProbe probe(Page page, java.util.function.BooleanSupplier challengeShown) throws BrowserException {
        JsonArray values = parse(evaluate(page, PROBE_SCRIPT));
        String challenge = values.get(2).getAsString();
        if (!BrowserProbe.CAPTCHA.equals(challenge) && challengeShown.getAsBoolean()) {
            challenge = BrowserProbe.CAPTCHA; // a challenge widget the research's detection sees
        }
        return new BrowserProbe(values.get(0).getAsBoolean(), values.get(1).getAsString(), challenge,
                values.get(3).getAsString());
    }

    /** A token that changes with every new document in the page, to tell whether the page was replaced. */
    String documentToken(Page page) throws BrowserException {
        return evaluate(page, DOCUMENT_TOKEN_SCRIPT);
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

    private String tokenOrNull(Page page) {
        try {
            return evaluate(page, DOCUMENT_TOKEN_SCRIPT);
        } catch (BrowserException | RuntimeException e) {
            return null;
        }
    }

    private String tokenOrNull(Tabs tabs, String contextId) {
        try {
            return tokenOrNull(tabs.page(contextId));
        } catch (BrowserException e) {
            return null;
        }
    }

    private String evaluate(Page page, String script) throws BrowserException {
        try {
            Object value = page.evaluate(script);
            return value instanceof String ? (String) value : "";
        } catch (PlaywrightException e) {
            throw guard.classify(e);
        }
    }

    private static JsonArray parse(String json) throws BrowserException {
        try {
            return JsonParser.parseString(json).getAsJsonArray();
        } catch (RuntimeException e) {
            throw unreadable(e);
        }
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
}
