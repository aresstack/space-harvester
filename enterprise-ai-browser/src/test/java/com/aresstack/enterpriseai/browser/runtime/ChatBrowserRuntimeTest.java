package com.aresstack.enterpriseai.browser.runtime;

import com.aresstack.enterpriseai.astu.chat.ChatConversationId;
import com.aresstack.enterpriseai.browser.api.BrowserAccessGuard;
import com.aresstack.enterpriseai.browser.api.BrowserAction;
import com.aresstack.enterpriseai.browser.api.BrowserActResult;
import com.aresstack.enterpriseai.browser.api.BrowserControl;
import com.aresstack.enterpriseai.browser.api.BrowserControls;
import com.aresstack.enterpriseai.browser.api.BrowserDocument;
import com.aresstack.enterpriseai.browser.api.BrowserException;
import com.aresstack.enterpriseai.browser.api.BrowserScreenshot;
import com.aresstack.enterpriseai.browser.api.BrowserLinks;
import com.aresstack.enterpriseai.browser.api.BrowserPage;
import com.aresstack.enterpriseai.browser.api.BrowserProbe;
import com.aresstack.enterpriseai.browser.api.BrowserTab;
import com.aresstack.enterpriseai.browser.api.ChatBrowser;
import com.aresstack.enterpriseai.browser.api.engine.RawControl;
import com.aresstack.enterpriseai.browser.api.engine.RawLink;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChatBrowserRuntimeTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private FakeEngine engine;
    private ChatBrowserRuntime runtime;
    private final ChatConversationId chatA = new ChatConversationId("chat-a");
    private final ChatConversationId chatB = new ChatConversationId("chat-b");

    @Before
    public void setUp() {
        engine = new FakeEngine();
        runtime = new ChatBrowserRuntime(engine, new BrowserProfileStore(folder.getRoot().toPath()));
    }

    @Test
    public void startsLazilyOnFirstOperation() throws Exception {
        ChatBrowser browser = runtime.browserOf(chatA);
        assertTrue(engine.starts.isEmpty());

        browser.navigate(null, "https://example.org/");

        assertEquals(1, engine.starts.size());
        assertEquals(folder.getRoot().toPath().resolve("chat-a"), engine.starts.get(0));
    }

    @Test
    public void healthyConnectionIsReusedWithoutNewProcessOrTab() throws Exception {
        ChatBrowser browser = runtime.browserOf(chatA);
        browser.navigate(null, "https://example.org/");
        browser.read(null);
        browser.tabs();

        assertEquals(1, engine.starts.size());
        assertEquals(1, engine.last().connects.get());
        assertEquals(1, engine.last().tabs.size());
    }

    @Test
    public void lostConnectionReconnectsToSameProcessKeepingTabsAndFocus() throws Exception {
        ChatBrowser browser = runtime.browserOf(chatA);
        browser.navigate(null, "https://one.example/");
        BrowserTab second = browser.newTab("https://two.example/");
        engine.last().connection.open = false;

        BrowserPage page = browser.read(null);

        assertEquals(1, engine.starts.size());
        assertEquals(2, engine.last().connects.get());
        assertEquals(2, engine.last().tabs.size());
        assertEquals(second.id(), page.tabId());
        assertEquals("https://two.example/", page.url());
    }

    @Test
    public void browserClosedByUserRestartsOnSameProfile() throws Exception {
        ChatBrowser browser = runtime.browserOf(chatA);
        browser.navigate(null, "https://one.example/");
        engine.last().closeByUser();

        BrowserPage page = browser.navigate(null, "https://again.example/");

        assertEquals(2, engine.starts.size());
        assertEquals(engine.starts.get(0), engine.starts.get(1));
        assertEquals("https://again.example/", page.url());
    }

    @Test
    public void newTabIsNotRepeatedWhenTheCallItselfFails() throws Exception {
        ChatBrowser browser = runtime.browserOf(chatA);
        browser.tabs();
        FakeEngine.FakeProcess process = engine.last();
        // The runtime still trusts the connection; the call then hits a dead browser.
        process.alive = false;
        try {
            browser.newTab(null);
            fail("expected failure");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.BROWSER_CLOSED, expected.kind());
        }
        assertEquals(1, engine.starts.size());
        // The next reading call recovers.
        assertEquals(1, browser.tabs().size());
        assertEquals(2, engine.starts.size());
    }

    @Test
    public void concurrentFirstCallsStartExactlyOneBrowser() throws Exception {
        engine.startDelayMillis = 100;
        final ChatBrowser browser = runtime.browserOf(chatA);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        final CountDownLatch go = new CountDownLatch(1);
        try {
            Future<?>[] calls = new Future<?>[8];
            for (int i = 0; i < calls.length; i++) {
                calls[i] = pool.submit(new Callable<List<BrowserTab>>() {
                    @Override
                    public List<BrowserTab> call() throws Exception {
                        go.await();
                        return runtime.browserOf(chatA).tabs();
                    }
                });
            }
            go.countDown();
            for (Future<?> call : calls) {
                call.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, engine.starts.size());
        assertEquals(1, browser.tabs().size());
    }

    @Test
    public void twoChatsHaveSeparateProfilesProcessesAndTabs() throws Exception {
        runtime.browserOf(chatA).navigate(null, "https://a.example/");
        runtime.browserOf(chatB).navigate(null, "https://b.example/");

        assertEquals(2, engine.starts.size());
        assertNotEquals(engine.starts.get(0), engine.starts.get(1));
        List<BrowserTab> tabsOfA = runtime.browserOf(chatA).tabs();
        assertEquals(1, tabsOfA.size());
        assertEquals("https://a.example/", tabsOfA.get(0).url());
    }

    @Test
    public void tabIdsAreChatLocalAndForeignIdsAreRejected() throws Exception {
        ChatBrowser a = runtime.browserOf(chatA);
        a.newTab("https://a2.example/");
        ChatBrowser b = runtime.browserOf(chatB);
        b.tabs();
        try {
            b.read("ctx-0-1");
            fail("raw id of another chat must not resolve");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.INVALID_REQUEST, expected.kind());
        }
        for (BrowserTab tab : a.tabs()) {
            assertTrue(tab.id().startsWith("tab-"));
        }
    }

    @Test
    public void selectTabChangesCurrentTab() throws Exception {
        ChatBrowser browser = runtime.browserOf(chatA);
        List<BrowserTab> initial = browser.tabs();
        browser.newTab("https://second.example/");
        browser.selectTab(initial.get(0).id());

        assertEquals(initial.get(0).id(), browser.read(null).tabId());
    }

    @Test
    public void releaseKeepsProfileAndDeleteRemovesIt() throws Exception {
        runtime.browserOf(chatA).tabs();
        Path profile = engine.starts.get(0);
        Files.write(profile.resolve("cookies.sqlite"), new byte[]{1});

        runtime.release(chatA);
        assertFalse(engine.last().alive);
        assertTrue(Files.exists(profile.resolve("cookies.sqlite")));

        runtime.browserOf(chatA).tabs();
        assertEquals(profile, engine.starts.get(1));

        runtime.deleteProfile(chatA);
        assertFalse(Files.exists(profile));
    }

    @Test
    public void unsafeChatIdIsRejected() {
        try {
            runtime.browserOf(new ChatConversationId("../evil")).tabs();
            fail("expected rejection");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.INVALID_REQUEST, expected.kind());
        }
        assertTrue(engine.starts.isEmpty());
    }

    @Test
    public void openManyLoadsInWavesAdmitsEachHostOnceAndClosesItsTabs() throws Exception {
        final List<String> admitted = new java.util.concurrent.CopyOnWriteArrayList<String>();
        BrowserAccessGuard guard = (chat, url) -> {
            admitted.add(url);
            if (url.contains("blocked.example")) {
                throw new BrowserException(BrowserException.Kind.BLOCKED, "gesperrt");
            }
        };
        ChatBrowser browser = new ChatBrowserRuntime(engine, new BrowserProfileStore(folder.getRoot().toPath()),
                guard).browserOf(chatA);
        browser.navigate(null, "https://start.example/");
        engine.failingLoads.add("https://down.example/");
        admitted.clear();

        List<com.aresstack.enterpriseai.browser.api.BrowserBatchPage> pages = browser.openMany(Arrays.asList(
                "https://a.example/1", "https://a.example/2", "https://a.example/1#top", "ftp://a.example/",
                "https://blocked.example/x", "https://blocked.example/y", "https://down.example/",
                "https://docs.example/plan.pdf"), 2, 0);

        assertEquals(8, pages.size());
        com.aresstack.enterpriseai.browser.api.BrowserBatchPage.Status loaded =
                com.aresstack.enterpriseai.browser.api.BrowserBatchPage.Status.LOADED;
        com.aresstack.enterpriseai.browser.api.BrowserBatchPage.Status skipped =
                com.aresstack.enterpriseai.browser.api.BrowserBatchPage.Status.SKIPPED;
        assertEquals(loaded, pages.get(0).status());
        assertEquals("Text of https://a.example/1", pages.get(0).page().text());
        assertEquals(loaded, pages.get(1).status());
        assertEquals(skipped, pages.get(2).status());
        assertEquals(skipped, pages.get(3).status());
        assertEquals(skipped, pages.get(4).status());
        assertEquals(skipped, pages.get(5).status());
        assertEquals(com.aresstack.enterpriseai.browser.api.BrowserBatchPage.Status.FAILED, pages.get(6).status());
        assertEquals(loaded, pages.get(7).status());
        assertTrue(pages.get(7).document() != null);
        // One admission per host before loading (a, blocked, down, docs), then the final addresses of loaded pages
        // reuse the host decision.
        assertEquals(Arrays.asList("https://a.example/1", "https://blocked.example/x", "https://down.example/",
                "https://docs.example/plan.pdf"), admitted);
        for (List<String> wave : engine.waves) {
            assertTrue(wave.size() <= 2);
        }
        assertEquals(2, engine.waves.size());
        assertEquals(1, engine.last().tabs.size());
        assertEquals("https://start.example/", browser.read(null).url());
    }

    @Test
    public void humanEmulationReachesTheConnectionAlsoAfterAReconnect() throws Exception {
        ChatBrowser browser = runtime.browserOf(chatA);
        browser.setHumanEmulation(true, false);
        browser.navigate(null, "https://example.org/");
        assertEquals("true/false", engine.last().connection.human);
        engine.last().connection.open = false;
        browser.read(null);
        assertEquals("true/false", engine.last().connection.human);
        browser.setHumanEmulation(false, true);
        assertEquals("false/true", engine.last().connection.human);
    }

    private ChatBrowserRuntime guarded() {
        BrowserAccessGuard guard = (chat, url) -> {
            if (url != null && url.contains("blocked.example")) {
                throw new BrowserException(BrowserException.Kind.BLOCKED, "gesperrt");
            }
        };
        return new ChatBrowserRuntime(engine, new BrowserProfileStore(folder.getRoot().toPath()), guard);
    }

    @Test
    public void blockedUrlNeverReachesTheEngine() throws Exception {
        ChatBrowser browser = guarded().browserOf(chatA);
        try {
            browser.navigate(null, "https://blocked.example/");
            fail("expected BLOCKED");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.BLOCKED, expected.kind());
            assertFalse(expected.isRecoverable());
        }
        try {
            browser.newTab("https://blocked.example/");
            fail("expected BLOCKED");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.BLOCKED, expected.kind());
        }
        assertTrue(engine.navigations.isEmpty());
    }

    @Test
    public void redirectToBlockedHostEndsOnAboutBlank() throws Exception {
        engine.redirects.put("https://ok.example/", "https://blocked.example/landing");
        ChatBrowser browser = guarded().browserOf(chatA);
        try {
            browser.navigate(null, "https://ok.example/");
            fail("expected BLOCKED");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.BLOCKED, expected.kind());
        }
        assertEquals("about:blank", browser.read(null).url());
        assertEquals(2, engine.navigations.size());
    }

    @Test
    public void allowedUrlPassesTheGuard() throws Exception {
        ChatBrowser browser = guarded().browserOf(chatA);

        BrowserPage page = browser.navigate(null, "https://ok.example/");

        assertEquals("https://ok.example/", page.url());
    }

    private static final String HOME = "https://intranet.example/";

    private void homeWithLinks() {
        engine.anchors.put(HOME, Arrays.asList(
                new RawLink("Reisekosten", "https://intranet.example/reise", "", ""),
                new RawLink("", "https://intranet.example/reise", "Reisekosten", ""),
                new RawLink("Nach oben", "https://intranet.example/#top", "", ""),
                new RawLink("Mail", "mailto:it@intranet.example", "", ""),
                new RawLink("Skript", "javascript:void(0)", "", ""),
                new RawLink("  KI   im\n Haus ", "https://intranet.example/ki", "", "KI-Portal"),
                new RawLink("Extern", "https://blocked.example/x", "", "")));
    }

    @Test
    public void linksAreRealAbsoluteDeduplicatedAnchorsWithSnapshotIds() throws Exception {
        homeWithLinks();
        ChatBrowser browser = runtime.browserOf(chatA);
        browser.navigate(null, HOME);

        BrowserLinks links = browser.links(null, 100, null);

        assertEquals(3, links.total());
        assertEquals("link-1", links.links().get(0).id());
        assertEquals("Reisekosten", links.links().get(0).text());
        assertEquals("https://intranet.example/reise", links.links().get(0).url());
        assertEquals("KI im Haus", links.links().get(1).text());
        assertEquals("KI-Portal", links.links().get(1).label());
        assertEquals(HOME, links.pageUrl());
    }

    @Test
    public void maxLinksLimitsTheListButNotTheTotal() throws Exception {
        homeWithLinks();
        ChatBrowser browser = runtime.browserOf(chatA);
        browser.navigate(null, HOME);

        BrowserLinks links = browser.links(null, 1, null);

        assertEquals(1, links.links().size());
        assertEquals(3, links.total());
        try {
            browser.follow(null, "link-2");
            fail("link-2 was not handed out");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.INVALID_REQUEST, expected.kind());
        }
    }

    @Test
    public void followAfterFollowOpensTheAddressOfTheLastList() throws Exception {
        homeWithLinks();
        ChatBrowser browser = runtime.browserOf(chatA);
        browser.navigate(null, HOME);
        browser.links(null, 100, null);

        BrowserPage page = browser.follow(null, "link-2");

        assertEquals("https://intranet.example/ki", page.url());
        // The tab left the list's page; the id still names what the model was shown.
        assertEquals("https://intranet.example/reise", browser.follow(null, "link-1").url());
        try {
            browser.follow(null, "link-99");
            fail("unknown ids still fail");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.INVALID_REQUEST, expected.kind());
        }
    }

    @Test
    public void followAfterNavigateUsesTheLastList() throws Exception {
        homeWithLinks();
        ChatBrowser browser = runtime.browserOf(chatA);
        browser.navigate(null, HOME);
        browser.links(null, 100, null);
        browser.navigate(null, "https://other.example/");

        assertEquals("https://intranet.example/reise", browser.follow(null, "link-1").url());
    }

    @Test
    public void followAfterAUserNavigationUsesTheLastList() throws Exception {
        homeWithLinks();
        ChatBrowser browser = runtime.browserOf(chatA);
        browser.navigate(null, HOME);
        browser.links(null, 100, null);
        String raw = engine.last().tabs.keySet().iterator().next();
        engine.last().connection.userNavigates(raw, "https://intranet.example/elsewhere");

        assertEquals("https://intranet.example/reise", browser.follow(null, "link-1").url());
    }

    @Test
    public void followPassesTheGuardAndABlockedLinkIsNeverOpened() throws Exception {
        homeWithLinks();
        ChatBrowser browser = guarded().browserOf(chatA);
        browser.navigate(null, HOME);
        BrowserLinks links = browser.links(null, 100, null);
        assertEquals("https://blocked.example/x", links.links().get(2).url());

        try {
            browser.follow(null, "link-3");
            fail("expected BLOCKED");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.BLOCKED, expected.kind());
        }
        assertEquals(Arrays.asList(HOME), engine.navigations);
    }

    @Test
    public void followRedirectToBlockedHostEndsOnAboutBlank() throws Exception {
        homeWithLinks();
        engine.redirects.put("https://intranet.example/reise", "https://blocked.example/landing");
        ChatBrowser browser = guarded().browserOf(chatA);
        browser.navigate(null, HOME);
        browser.links(null, 100, null);

        try {
            browser.follow(null, "link-1");
            fail("expected BLOCKED");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.BLOCKED, expected.kind());
        }
        assertEquals("about:blank", browser.read(null).url());
    }

    @Test
    public void backReturnsToThePreviousPage() throws Exception {
        homeWithLinks();
        ChatBrowser browser = runtime.browserOf(chatA);
        browser.navigate(null, HOME);
        browser.links(null, 100, null);
        browser.follow(null, "link-1");

        BrowserPage page = browser.back(null);

        assertEquals(HOME, page.url());
        assertEquals("link-1", browser.links(null, 100, null).links().get(0).id());
    }

    @Test
    public void closeBrowserStopsTheProcessAndTheNextCallRestartsOnTheSameProfile() throws Exception {
        ChatBrowser browser = runtime.browserOf(chatA);
        browser.navigate(null, "https://example.org/");
        assertTrue(browser.isRunning());

        browser.closeBrowser();

        assertFalse(browser.isRunning());
        assertFalse(engine.last().alive);
        browser.navigate(null, "https://example.org/");
        assertEquals(2, engine.starts.size());
        assertEquals(engine.starts.get(0), engine.starts.get(1));
        assertTrue(browser.isRunning());
    }

    @Test
    public void closeBrowserWithoutRunningBrowserDoesNothing() {
        ChatBrowser browser = runtime.browserOf(chatA);

        browser.closeBrowser();

        assertFalse(browser.isRunning());
        assertTrue(engine.starts.isEmpty());
    }

    @Test
    public void filterIsAppliedBeforeTheLimit() throws Exception {
        java.util.List<RawLink> many = new java.util.ArrayList<RawLink>();
        for (int i = 0; i < 400; i++) {
            many.add(new RawLink("Seite " + i, "https://intranet.example/p" + i, "", ""));
        }
        many.add(new RawLink("Reisekostenabrechnung", "https://intranet.example/reise", "", ""));
        engine.anchors.put(HOME, many);
        ChatBrowser browser = runtime.browserOf(chatA);
        browser.navigate(null, HOME);

        BrowserLinks links = browser.links(null, 10, "REISE");

        assertEquals(1, links.total());
        assertEquals("https://intranet.example/reise", links.links().get(0).url());
        assertEquals("https://intranet.example/reise", browser.follow(null, "link-1").url());
    }

    @Test
    public void followAfterAReloadUsesTheLastList() throws Exception {
        homeWithLinks();
        ChatBrowser browser = runtime.browserOf(chatA);
        browser.navigate(null, HOME);
        browser.links(null, 100, null);
        engine.last().connection.userReloads(engine.last().tabs.keySet().iterator().next());

        assertEquals("https://intranet.example/reise", browser.follow(null, "link-1").url());
    }

    @Test
    public void backTargetPassesTheGuardBeforeItIsLoaded() throws Exception {
        java.util.List<String> admitted = new java.util.concurrent.CopyOnWriteArrayList<String>();
        java.util.concurrent.atomic.AtomicBoolean blockStart = new java.util.concurrent.atomic.AtomicBoolean();
        ChatBrowser browser = new ChatBrowserRuntime(engine, new BrowserProfileStore(folder.getRoot().toPath()),
                (chat, url) -> {
                    admitted.add(url);
                    if (blockStart.get() && url.startsWith("https://start.example")) {
                        throw new BrowserException(BrowserException.Kind.BLOCKED, "gesperrt");
                    }
                }).browserOf(chatA);
        browser.navigate(null, "https://start.example/");
        browser.navigate(null, "https://next.example/");
        blockStart.set(true);

        try {
            browser.back(null);
            fail("expected BLOCKED");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.BLOCKED, expected.kind());
        }
        assertEquals(Arrays.asList("https://start.example/", "https://next.example/"), engine.navigations);
        assertEquals("https://next.example/", browser.read(null).url());
    }

    @Test
    public void backWithoutPreviousPageIsRejected() throws Exception {
        ChatBrowser browser = runtime.browserOf(chatA);
        browser.navigate(null, "https://example.org/");

        try {
            browser.back(null);
            fail("expected INVALID_REQUEST");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.INVALID_REQUEST, expected.kind());
        }
    }

    private static final String PORTAL = "https://intranet.example/portal";

    private void portalWithControls() {
        engine.controls.put(PORTAL, Arrays.asList(
                new RawControl("r-menu", "toggle", "Menü", "zu", "", ""),
                new RawControl("r-search", "search", "Suche", "", "", "https://intranet.example/suche"),
                new RawControl("r-lang", "select", "Sprache", "Deutsch", "Deutsch | English", ""),
                new RawControl("r-ext", "button", "Extern senden", "", "", "https://blocked.example/post")));
        engine.actionTargets.put("r-search", "https://intranet.example/suche?q=Reise");
    }

    @Test
    public void controlsListSearchFieldsFirstAndFilter() throws Exception {
        portalWithControls();
        ChatBrowser browser = runtime.browserOf(chatA);
        browser.navigate(null, PORTAL);

        BrowserControls all = browser.controls(null, 100, null);
        assertEquals(4, all.total());
        assertEquals("ctl-1", all.controls().get(0).id());
        assertEquals("search", all.controls().get(0).kind());
        assertEquals("https://intranet.example/suche", all.controls().get(0).target());
        assertEquals("toggle", all.controls().get(1).kind());

        BrowserControls filtered = browser.controls(null, 100, "sprache");
        assertEquals(1, filtered.controls().size());
        assertEquals("Deutsch | English", filtered.controls().get(0).options());
    }

    @Test
    public void controlsCarryTheirFormLocatorAndPositionAndTheFormNameFilters() throws Exception {
        engine.controls.put(PORTAL, Arrays.asList(
                new RawControl("r-menu", "toggle", "Menü", "zu", "", ""),
                new RawControl("r-amount", "input", "Betrag", "", "", "", "Neue eRechnung", true,
                        "label=\"Betrag\"", "Mitte links")));
        ChatBrowser browser = runtime.browserOf(chatA);
        browser.navigate(null, PORTAL);

        BrowserControls mask = browser.controls(null, 100, "erechnung");
        assertEquals(1, mask.controls().size());
        BrowserControl amount = mask.controls().get(0);
        assertEquals("Neue eRechnung", amount.form());
        assertTrue(amount.required());
        assertEquals("label=\"Betrag\"", amount.locator());
        assertEquals("Mitte links", amount.position());
    }

    @Test
    public void submitLoadsTheResultPageAndBackReturns() throws Exception {
        portalWithControls();
        ChatBrowser browser = runtime.browserOf(chatA);
        browser.navigate(null, PORTAL);
        browser.controls(null, 100, null);

        BrowserActResult result = browser.act(null, "ctl-1", BrowserAction.SUBMIT, "Reise");

        assertTrue(result.navigated());
        assertEquals("https://intranet.example/suche?q=Reise", result.page().url());
        assertEquals(Arrays.asList("r-search SUBMIT Reise"), engine.actions);
        try {
            browser.act(null, "ctl-1", BrowserAction.SUBMIT, "Reise");
            fail("expected INVALID_REQUEST after the new document");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.INVALID_REQUEST, expected.kind());
        }
        assertEquals(PORTAL, browser.back(null).url());
    }

    @Test
    public void inPlaceChangeKeepsTheControlIds() throws Exception {
        portalWithControls();
        ChatBrowser browser = runtime.browserOf(chatA);
        browser.navigate(null, PORTAL);
        browser.controls(null, 100, null);

        BrowserActResult opened = browser.act(null, "ctl-2", BrowserAction.CLICK, null);
        assertFalse(opened.navigated());
        assertTrue(opened.changed());
        browser.act(null, "ctl-3", BrowserAction.SELECT, "English");

        assertEquals(Arrays.asList("r-menu CLICK null", "r-lang SELECT English"), engine.actions);
    }

    @Test
    public void screenshotMarksTheVisibleControlsWithTheirSnapshotIds() throws Exception {
        portalWithControls();
        engine.offscreen.add("r-ext");
        ChatBrowser browser = runtime.browserOf(chatA);
        browser.navigate(null, PORTAL);

        // Without a control list the runtime takes one first, so the marks are the ids browser_act accepts.
        BrowserScreenshot shot = browser.screenshot(null, null, true);

        assertEquals("image/jpeg", shot.mediaType());
        assertTrue(shot.marked());
        assertEquals(PORTAL, shot.pageUrl());
        assertEquals(Arrays.asList("ctl-1", "ctl-2", "ctl-3"), idsOf(shot.visible()));
        assertEquals("search", shot.visible().get(0).kind());
        assertEquals(Arrays.asList("{r-search=ctl-1, r-menu=ctl-2, r-lang=ctl-3, r-ext=ctl-4} null "
                + BrowserScreenshot.MAX_BYTES), engine.screenshots);
        browser.act(null, "ctl-1", BrowserAction.SUBMIT, "Reise");
        assertEquals(Arrays.asList("r-search SUBMIT Reise"), engine.actions);
    }

    @Test
    public void screenshotOfAControlRegionNeedsACurrentControlList() throws Exception {
        portalWithControls();
        ChatBrowser browser = runtime.browserOf(chatA);
        browser.navigate(null, PORTAL);
        try {
            browser.screenshot(null, "ctl-1", true);
            fail("expected INVALID_REQUEST without control list");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.INVALID_REQUEST, expected.kind());
        }
        browser.controls(null, 100, null);

        BrowserScreenshot region = browser.screenshot(null, "ctl-3", false);

        assertFalse(region.marked());
        assertEquals("{r-search=, r-menu=, r-lang=, r-ext=} r-lang " + BrowserScreenshot.MAX_BYTES,
                engine.screenshots.get(0));
        try {
            browser.screenshot(null, "ctl-9", false);
            fail("expected INVALID_REQUEST for an unknown id");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.INVALID_REQUEST, expected.kind());
        }
    }

    @Test
    public void screenshotOfAPageOnABlockedHostIsRefused() throws Exception {
        ChatBrowser browser = guarded().browserOf(chatA);
        browser.navigate(null, PORTAL);
        FakeEngine.FakeProcess process = engine.last();
        synchronized (process) {
            // The user opened a refused page by hand; its picture must not reach the model.
            String tab = process.tabs.keySet().iterator().next();
            process.tabs.put(tab, new String[]{"https://blocked.example/geheim", "Geheim"});
        }
        try {
            browser.screenshot(null, null, false);
            fail("expected BLOCKED");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.BLOCKED, expected.kind());
        }
        assertTrue(engine.screenshots.isEmpty());
    }

    private static List<String> idsOf(List<BrowserControl> controls) {
        List<String> ids = new ArrayList<String>();
        for (BrowserControl control : controls) {
            ids.add(control.id());
        }
        return ids;
    }

    @Test
    public void declaredFormTargetPassesTheGuardBeforeSubmitting() throws Exception {
        portalWithControls();
        ChatBrowser browser = guarded().browserOf(chatA);
        browser.navigate(null, PORTAL);
        browser.controls(null, 100, null);

        try {
            browser.act(null, "ctl-4", BrowserAction.CLICK, null);
            fail("expected BLOCKED");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.BLOCKED, expected.kind());
        }
        assertTrue(engine.actions.isEmpty());
    }

    @Test
    public void actionLandingOnABlockedHostEndsOnAboutBlank() throws Exception {
        portalWithControls();
        engine.actionTargets.put("r-menu", "https://blocked.example/landing");
        ChatBrowser browser = guarded().browserOf(chatA);
        browser.navigate(null, PORTAL);
        browser.controls(null, 100, null);

        try {
            browser.act(null, "ctl-2", BrowserAction.CLICK, null);
            fail("expected BLOCKED");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.BLOCKED, expected.kind());
        }
        assertEquals("about:blank", browser.read(null).url());
    }

    @Test
    public void actWithoutSnapshotOrValueIsRefused() throws Exception {
        portalWithControls();
        ChatBrowser browser = runtime.browserOf(chatA);
        browser.navigate(null, PORTAL);
        try {
            browser.act(null, "ctl-1", BrowserAction.CLICK, null);
            fail("expected INVALID_REQUEST without snapshot");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.INVALID_REQUEST, expected.kind());
        }
        browser.controls(null, 100, null);
        try {
            browser.act(null, "ctl-1", BrowserAction.FILL, null);
            fail("expected INVALID_REQUEST without value");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.INVALID_REQUEST, expected.kind());
        }
        assertTrue(engine.actions.isEmpty());
    }

    @Test
    public void probeReportsTheCurrentPageOfTheTab() throws Exception {
        engine.probes.put("https://login.example/", new BrowserProbe(false, "", BrowserProbe.LOGIN));
        ChatBrowser browser = runtime.browserOf(chatA);
        browser.navigate(null, "https://example.org/");
        assertFalse(browser.probe(null).hasChallenge());

        browser.navigate(null, "https://login.example/");
        assertEquals(BrowserProbe.LOGIN, browser.probe(null).challenge());
    }

    @Test
    public void linksCarryTheirRegionAndDocumentsDownloadThroughTheGuard() throws Exception {
        engine.anchors.put(PORTAL, Arrays.asList(
                new RawLink("Start", "https://intranet.example/", "", "", "nav"),
                new RawLink("Richtlinie", "https://intranet.example/richtlinie.pdf", "", "", "main"),
                new RawLink("Extern", "https://intranet.example/extern.pdf", "", "", "main")));
        engine.redirects.put("https://intranet.example/extern.pdf", "https://blocked.example/x.pdf");
        ChatBrowser browser = guarded().browserOf(chatA);
        browser.navigate(null, PORTAL);
        BrowserLinks links = browser.links(null, 100, null);
        assertEquals("nav", links.links().get(0).region());
        assertEquals("main", links.links().get(1).region());

        BrowserDocument document = browser.download(null, "link-2");
        assertEquals("richtlinie.pdf", document.fileName());
        assertEquals("application/pdf", document.contentType());
        assertEquals(PORTAL, browser.read(null).url());
        try {
            browser.download(null, "link-3");
            fail("expected BLOCKED after the redirect");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.BLOCKED, expected.kind());
        }
        assertEquals(Arrays.asList("https://intranet.example/richtlinie.pdf", "https://intranet.example/extern.pdf"),
                engine.fetches);
    }
}
