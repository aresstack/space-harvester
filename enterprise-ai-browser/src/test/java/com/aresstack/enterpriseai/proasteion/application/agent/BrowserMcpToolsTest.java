package com.aresstack.enterpriseai.proasteion.application.agent;

import com.aresstack.enterpriseai.astu.approval.ApprovalDecision;
import com.aresstack.enterpriseai.astu.approval.ApprovalPromptPort;
import com.aresstack.enterpriseai.astu.approval.ApprovalRules;
import com.aresstack.enterpriseai.astu.approval.ToolApprovalGate;
import com.aresstack.enterpriseai.astu.chat.ChatConversationId;
import com.aresstack.enterpriseai.browser.api.BrowserAction;
import com.aresstack.enterpriseai.browser.api.BrowserActResult;
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
import com.aresstack.enterpriseai.proasteion.katagogion.McpToolCall;
import com.aresstack.enterpriseai.proasteion.katagogion.McpToolContribution;
import com.aresstack.enterpriseai.proasteion.katagogion.McpToolParameter;
import com.aresstack.enterpriseai.proasteion.katagogion.McpToolResult;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BrowserMcpToolsTest {

    private final RecordingPort port = new RecordingPort();
    private final BrowserMcpTools tools = new BrowserMcpTools(port);

    @Test
    public void schemasCarryNoChatIdPortOrProfile() {
        for (McpToolContribution tool : BrowserMcpTools.unbound()) {
            for (McpToolParameter parameter : tool.getParameters()) {
                String name = parameter.getName().toLowerCase();
                assertFalse(tool.getName() + "." + name, name.contains("chat") || name.contains("port")
                        || name.contains("profile") || name.contains("context") || name.contains("session"));
            }
        }
    }

    /** The control snapshot the tools take after every load to look for the site's search. */
    private static final String SEARCH_HINT = "controls " + BrowserMcpTools.SEARCH_HINT_CONTROLS + " null";

    @Test
    public void callsReachOnlyTheBrowserOfTheBoundChat() {
        ChatConversationId a = new ChatConversationId("chat-a");
        ChatConversationId b = new ChatConversationId("chat-b");
        invoke(tools.contributionsFor(a), BrowserMcpTools.NAVIGATE, "url", "https://a.example/");
        invoke(tools.contributionsFor(b), BrowserMcpTools.READ);

        assertEquals(java.util.Arrays.asList("navigate https://a.example/", SEARCH_HINT), port.browser(a).calls);
        assertEquals(Collections.singletonList("read null"), port.browser(b).calls);
    }

    @Test
    public void aLoadedPageWithItsOwnSearchPointsTheModelToTheSearchField() {
        ChatConversationId a = new ChatConversationId("chat-a");
        List<McpToolContribution> bound = tools.contributionsFor(a);
        McpToolResult opened = invoke(bound, BrowserMcpTools.NAVIGATE, "url", "https://intranet.example/");
        assertTrue(opened.getText(), opened.getText().contains("eigenes Suchfeld ctl-1 „Suche“"));
        assertTrue(opened.getText(), opened.getText().contains("statt eine Such-Adresse zu raten"));

        // The hint's snapshot is the one the model acts on, so the action is described by the field's label.
        McpToolResult searched = invoke(bound, BrowserMcpTools.ACT, "control", "ctl-1", "action", "submit",
                "value", "Reisekosten");
        assertTrue(searched.getText(), searched.getText().startsWith("Gesucht: „Reisekosten“"));
    }

    @Test
    public void navigateRejectsNonHttpAddresses() {
        ChatConversationId a = new ChatConversationId("chat-a");
        for (String url : new String[]{"file:///etc/passwd", "javascript:alert(1)", "about:config", "example.org"}) {
            McpToolResult result = invoke(tools.contributionsFor(a), BrowserMcpTools.NAVIGATE, "url", url);
            assertTrue(url, result.isError());
        }
        assertTrue(port.browser(a).calls.isEmpty());
    }

    @Test
    public void tabsListsWithCurrentMarkAndReadIsCut() {
        ChatConversationId a = new ChatConversationId("chat-a");
        McpToolResult list = invoke(tools.contributionsFor(a), BrowserMcpTools.TABS);
        assertTrue(list.getText(), list.getText().contains("* tab-1 | Start | https://start.example/"));

        McpToolResult read = invoke(tools.contributionsFor(a), BrowserMcpTools.READ, "max_chars", "4");
        assertTrue(read.getText(), read.getText().contains("gekürzt"));
    }

    @Test
    public void browserFailureBecomesToolError() {
        ChatConversationId a = new ChatConversationId("chat-a");
        port.browser(a).fail = true;
        McpToolResult result = invoke(tools.contributionsFor(a), BrowserMcpTools.READ);
        assertTrue(result.isError());
        assertEquals("kaputt", result.getText());
    }

    @Test
    public void linksListIdsTextAndAddressAndFilterKeepsMatchesOnly() {
        ChatConversationId a = new ChatConversationId("chat-a");
        McpToolResult all = invoke(tools.contributionsFor(a), BrowserMcpTools.LINKS);
        assertTrue(all.getText(), all.getText().contains("link-1 | Reisekosten | https://intranet.example/reise"));
        assertTrue(all.getText(), all.getText().contains("link-2 | KI [KI-Portal] | https://intranet.example/ki"));
        assertTrue(all.getText(), all.getText().contains("2 von 2 Links"));

        McpToolResult filtered = invoke(tools.contributionsFor(a), BrowserMcpTools.LINKS, "filter", "portal");
        assertTrue(filtered.getText(), filtered.getText().contains("link-1 | KI [KI-Portal]"));
        assertTrue(filtered.getText(), filtered.getText().contains("1 Links passend zu \"portal\""));
        assertEquals("links null " + BrowserMcpTools.DEFAULT_MAX_LINKS + " portal", port.browser(a).calls.get(1));
    }

    @Test
    public void followTakesALinkIdBackAndCloseReachTheBrowser() {
        ChatConversationId a = new ChatConversationId("chat-a");
        assertTrue(invoke(tools.contributionsFor(a), BrowserMcpTools.FOLLOW).isError());
        McpToolResult followed = invoke(tools.contributionsFor(a), BrowserMcpTools.FOLLOW, "link", "link-2");
        assertTrue(followed.getText(), followed.getText().contains("Adresse: https://intranet.example/ki"));
        invoke(tools.contributionsFor(a), BrowserMcpTools.BACK);
        invoke(tools.contributionsFor(a), BrowserMcpTools.CLOSE);

        assertEquals(java.util.Arrays.asList("follow link-2", SEARCH_HINT, "back null", SEARCH_HINT,
                "closeBrowser"), port.browser(a).calls);
    }

    @Test
    public void callsRecordTheTurnsExplorationAndMarkCountsRelevantPages() {
        ChatConversationId a = new ChatConversationId("chat-a");
        java.util.List<com.aresstack.enterpriseai.proasteion.application.browser.WebExplorationProgress> seen =
                new java.util.ArrayList<com.aresstack.enterpriseai.proasteion.application.browser.WebExplorationProgress>();
        tools.beginTurn(a, seen::add);
        assertTrue(invoke(tools.contributionsFor(a), BrowserMcpTools.MARK).isError());
        invoke(tools.contributionsFor(a), BrowserMcpTools.LINKS);
        invoke(tools.contributionsFor(a), BrowserMcpTools.FOLLOW, "link", "link-2");
        invoke(tools.contributionsFor(a), BrowserMcpTools.MARK);
        McpToolResult again = invoke(tools.contributionsFor(a), BrowserMcpTools.LINKS);

        com.aresstack.enterpriseai.proasteion.application.browser.WebExplorationProgress last = tools.endTurn(a);
        assertEquals(1, last.visited());
        assertEquals(2, last.examined());
        assertEquals(1, last.relevant());
        assertTrue(again.getText(), again.getText().contains("https://intranet.example/ki (schon besucht)"));
        assertTrue(seen.get(0).isRunning());
    }

    @Test
    public void dataChangingSubmitAsksTheSharedGateAndDoesNotRunWhenRejected() {
        final List<String> asked = new ArrayList<String>();
        ToolApprovalGate gate = new ToolApprovalGate(ApprovalRules::none, (request, timeout) -> {
            asked.add(request.target().description());
            return ApprovalDecision.of(request, ApprovalDecision.Outcome.REJECTED);
        }, 1000);
        BrowserMcpTools gated = new BrowserMcpTools(port, null, gate);
        ChatConversationId a = new ChatConversationId("chat-gate");
        List<McpToolContribution> bound = gated.contributionsFor(a);
        invoke(bound, BrowserMcpTools.CONTROLS);

        McpToolResult filled = invoke(bound, BrowserMcpTools.ACT, "control", "ctl-3", "action", "fill", "value", "42");
        assertFalse(filled.getText(), filled.isError());
        McpToolResult searched = invoke(bound, BrowserMcpTools.ACT, "control", "ctl-1", "action", "submit",
                "value", "Reise");
        assertFalse(searched.getText(), searched.isError());
        invoke(bound, BrowserMcpTools.CONTROLS);
        McpToolResult sent = invoke(bound, BrowserMcpTools.ACT, "control", "ctl-3", "action", "submit");

        assertTrue(sent.getText(), sent.isError());
        assertTrue(sent.getText(), sent.getText().contains("vom Benutzer abgelehnt"));
        assertEquals(1, asked.size());
        assertTrue(asked.get(0), asked.get(0).contains("„Betrag (EUR)“ absenden (Formular „Neue eRechnung“) auf "
                + "intranet.example"));
        assertFalse(port.browser(a).calls.toString(), port.browser(a).calls.contains("act ctl-3 SUBMIT null"));
        assertTrue(port.browser(a).calls.contains("act ctl-3 FILL 42"));
    }

    @Test
    public void approvedSubmitRunsAndSettingsCanAllowIt() {
        ToolApprovalGate approving = new ToolApprovalGate(ApprovalRules::none,
                (request, timeout) -> ApprovalDecision.of(request, ApprovalDecision.Outcome.APPROVED), 1000);
        ChatConversationId a = new ChatConversationId("chat-ok");
        List<McpToolContribution> bound = new BrowserMcpTools(port, null, approving).contributionsFor(a);
        invoke(bound, BrowserMcpTools.CONTROLS);
        assertFalse(invoke(bound, BrowserMcpTools.ACT, "control", "ctl-3", "action", "submit").isError());
        assertTrue(port.browser(a).calls.contains("act ctl-3 SUBMIT null"));

        ToolApprovalGate allowed = new ToolApprovalGate(() -> ApprovalRules.parse("browser_act:change=ALLOW"),
                ApprovalPromptPort.unavailable(), 1000);
        ChatConversationId b = new ChatConversationId("chat-allowed");
        List<McpToolContribution> free = new BrowserMcpTools(port, null, allowed).contributionsFor(b);
        invoke(free, BrowserMcpTools.CONTROLS);
        assertFalse(invoke(free, BrowserMcpTools.ACT, "control", "ctl-3", "action", "submit").isError());
    }

    @Test
    public void locatorActsByNameAndStillAsksTheGate() {
        final List<String> asked = new ArrayList<String>();
        ToolApprovalGate gate = new ToolApprovalGate(ApprovalRules::none, (request, timeout) -> {
            asked.add(request.target().description());
            return ApprovalDecision.of(request, ApprovalDecision.Outcome.REJECTED);
        }, 1000);
        ChatConversationId a = new ChatConversationId("chat-locator");
        List<McpToolContribution> bound = new BrowserMcpTools(port, null, gate).contributionsFor(a);
        invoke(bound, BrowserMcpTools.CONTROLS);

        McpToolResult filled = invoke(bound, BrowserMcpTools.ACT, "locator", "label=\"Betrag (EUR)\"",
                "action", "fill", "value", "42");
        assertFalse(filled.getText(), filled.isError());
        assertTrue(port.browser(a).calls.contains("actLocated label=\"Betrag (EUR)\" FILL 42"));

        McpToolResult sent = invoke(bound, BrowserMcpTools.ACT, "locator", "role=button name=\"Speichern\"",
                "action", "click");
        assertTrue(sent.getText(), sent.isError());
        assertEquals(1, asked.size());
        assertFalse(port.browser(a).calls.toString(), port.browser(a).calls.toString().contains("Speichern"));

        McpToolResult missing = invoke(bound, BrowserMcpTools.ACT, "action", "click");
        assertTrue(missing.getText(), missing.getText().contains("control oder locator fehlt"));
    }

    @Test
    public void openManyReturnsOneEntryPerAddressAndUsesTheChatsLoad() throws Exception {
        RecordingPort port = new RecordingPort();
        BrowserMcpTools tools = new BrowserMcpTools(port);
        ChatConversationId chat = new ChatConversationId("chat-many");
        tools.setBatchLoad(chat, 3, 250);
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("urls", Arrays.asList("https://a.example/", "https://blocked.example/", "https://down.example/"));
        McpToolResult result = null;
        for (McpToolContribution tool : tools.contributionsFor(chat)) {
            if (tool.getName().equals(BrowserMcpTools.OPEN_MANY)) {
                result = tool.getHandler().invoke(new McpToolCall(BrowserMcpTools.OPEN_MANY, values));
            }
        }

        assertFalse(result.isError());
        String text = result.getText();
        assertTrue(text, text.startsWith("1 von 3 Adressen geladen."));
        assertTrue(text, text.contains("1. Seite A | https://a.example/ | geladen"));
        assertTrue(text, text.contains("2. https://blocked.example/ | übersprungen: gesperrt"));
        assertTrue(text, text.contains("3. https://down.example/ | fehlgeschlagen: weg"));
        assertTrue(port.browser(chat).calls.contains("openMany 3 3 250"));
    }

    @Test
    public void openManyTakesLinkIdsOfTheLastList() throws Exception {
        RecordingPort port = new RecordingPort();
        BrowserMcpTools tools = new BrowserMcpTools(port);
        ChatConversationId chat = new ChatConversationId("chat-ids");
        List<McpToolContribution> bound = tools.contributionsFor(chat);
        invoke(bound, BrowserMcpTools.LINKS);
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("links", Arrays.asList("link-1", "link-2", "link-1"));
        McpToolResult result = null;
        for (McpToolContribution tool : bound) {
            if (tool.getName().equals(BrowserMcpTools.OPEN_MANY)) {
                result = tool.getHandler().invoke(new McpToolCall(BrowserMcpTools.OPEN_MANY, values));
            }
        }
        assertFalse(result.getText(), result.isError());
        assertTrue(port.browser(chat).calls.toString(), port.browser(chat).calls.contains("openMany 3 4 0"));
    }

    private static McpToolResult invoke(List<McpToolContribution> contributions, String name, String... args) {
        Map<String, Object> values = new HashMap<String, Object>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            values.put(args[i], args[i + 1]);
        }
        for (McpToolContribution tool : contributions) {
            if (tool.getName().equals(name)) {
                return tool.getHandler().invoke(new McpToolCall(name, values));
            }
        }
        throw new AssertionError("no tool " + name);
    }

    @Test
    public void screenshotReturnsTheImageAndDescribesTheVisibleControlsAsText() {
        ChatConversationId a = new ChatConversationId("chat-a");
        McpToolResult shot = invoke(tools.contributionsFor(a), BrowserMcpTools.SCREENSHOT);

        assertFalse(shot.getText(), shot.isError());
        assertTrue(shot.hasImage());
        assertEquals("image/jpeg", shot.imageMediaType());
        assertEquals(4, shot.imageBytes().length);
        // Models without image input still learn which control sits where.
        assertTrue(shot.getText(), shot.getText().contains("ctl-1 | search | Alles durchsuchen | oben rechts"));
        assertTrue(shot.getText(), shot.getText().contains("Ids der Bedienelemente eingezeichnet"));
        assertEquals(Collections.singletonList("screenshot null true"), port.browser(a).calls);
    }

    @Test
    public void screenshotPassesRegionAndMarksAndReportsFailures() {
        ChatConversationId a = new ChatConversationId("chat-a");
        List<McpToolContribution> bound = tools.contributionsFor(a);
        McpToolResult shot = invoke(bound, BrowserMcpTools.SCREENSHOT, "control", "ctl-3", "marks", "false");
        assertFalse(shot.getText().contains("eingezeichnet"));
        assertEquals(Collections.singletonList("screenshot ctl-3 false"), port.browser(a).calls);

        port.browser(a).fail = true;
        McpToolResult failed = invoke(bound, BrowserMcpTools.SCREENSHOT);
        assertTrue(failed.isError());
        assertFalse(failed.hasImage());
    }

    @Test
    public void unboundScreenshotIsListedButRefused() {
        boolean listed = false;
        for (McpToolContribution tool : BrowserMcpTools.unbound()) {
            listed |= tool.getName().equals(BrowserMcpTools.SCREENSHOT);
        }
        assertTrue(listed);
        assertTrue(invoke(BrowserMcpTools.unbound(), BrowserMcpTools.SCREENSHOT).isError());
    }

    @Test
    public void controlsAndSiteSearchAreRecordedAsSteps() {
        ChatConversationId a = new ChatConversationId("chat-a");
        List<String> steps = new ArrayList<String>();
        tools.beginTurn(a, progress -> {
            if (!progress.steps().isEmpty()) {
                steps.add(progress.steps().get(progress.steps().size() - 1).label());
            }
        });
        List<McpToolContribution> bound = tools.contributionsFor(a);

        McpToolResult listed = invoke(bound, BrowserMcpTools.CONTROLS);
        assertTrue(listed.getText(), listed.getText().contains("ctl-1 | search | Suche"));
        assertTrue(listed.getText(), listed.getText().contains("Optionen: Deutsch | English"));
        assertTrue(listed.getText(), listed.getText().contains("Formular „Neue eRechnung“:\n"
                + "ctl-3 | input | Betrag (EUR) * | label=\"Betrag (EUR)\" | Mitte"));

        McpToolResult searched = invoke(bound, BrowserMcpTools.ACT, "control", "ctl-1", "action", "submit",
                "value", "Reisekosten");
        assertFalse(searched.getText(), searched.isError());
        assertTrue(searched.getText(), searched.getText().startsWith("Gesucht: „Reisekosten“. Neue Seite geladen."));

        invoke(bound, BrowserMcpTools.CONTROLS);
        McpToolResult chosen = invoke(bound, BrowserMcpTools.ACT, "control", "ctl-2", "action", "select",
                "value", "English");
        assertTrue(chosen.getText(), chosen.getText().contains("Die Seite hat sich geändert"));

        assertEquals(java.util.Arrays.asList("controls 60 null", "act ctl-1 SUBMIT Reisekosten",
                SEARCH_HINT, "controls 60 null", "act ctl-2 SELECT English"), port.browser(a).calls);
        assertTrue(steps.toString(), steps.contains("Gesucht: „Reisekosten“"));
        assertTrue(steps.toString(), steps.contains("„English“ in „Sprache“ gewählt"));
        assertEquals(1, tools.endTurn(a).visited());
    }

    @Test
    public void actRejectsUnknownActionsWithoutTouchingTheBrowser() {
        ChatConversationId a = new ChatConversationId("chat-a");
        McpToolResult result = invoke(tools.contributionsFor(a), BrowserMcpTools.ACT, "control", "ctl-1",
                "action", "hover");
        assertTrue(result.isError());
        assertTrue(port.browser(a).calls.isEmpty());
    }

    @Test
    public void formErrorsAfterAnActionAreShownToTheModel() {
        ChatConversationId a = new ChatConversationId("chat-a");
        List<McpToolContribution> bound = tools.contributionsFor(a);
        invoke(bound, BrowserMcpTools.CONTROLS);
        port.browser(a).probe = new BrowserProbe(false, "", "", "Betrag: Bitte einen Betrag eingeben");
        McpToolResult sent = invoke(bound, BrowserMcpTools.ACT, "control", "ctl-2", "action", "select",
                "value", "English");
        assertTrue(sent.getText(), sent.getText().contains("Das Formular meldet: Betrag: Bitte einen Betrag eingeben"));
    }

    @Test
    public void consentBannerAndChallengesBecomeHintsAndAChallengeKeepsTheBrowserOpen() {
        ChatConversationId a = new ChatConversationId("chat-a");
        tools.beginTurn(a, null);
        List<McpToolContribution> bound = tools.contributionsFor(a);
        port.browser(a).probe = new BrowserProbe(true, "Alle akzeptieren | Ablehnen", "");
        McpToolResult banner = invoke(bound, BrowserMcpTools.NAVIGATE, "url", "https://a.example/");
        assertTrue(banner.getText(), banner.getText().contains("Schaltflächen: Alle akzeptieren | Ablehnen"));
        assertFalse(tools.keepsBrowserOpen(a));

        port.browser(a).probe = new BrowserProbe(false, "", BrowserProbe.LOGIN);
        McpToolResult login = invoke(bound, BrowserMcpTools.NAVIGATE, "url", "https://sso.example/");
        assertTrue(login.getText(), login.getText().contains("Passwörter gibst du nie ein"));
        assertTrue(tools.keepsBrowserOpen(a));
        assertEquals(BrowserProbe.LOGIN, tools.endTurn(a).challenge());

        tools.beginTurn(a, null);
        assertFalse(tools.keepsBrowserOpen(a));
        port.browser(a).probe = new BrowserProbe(false, "", BrowserProbe.CAPTCHA);
        invoke(bound, BrowserMcpTools.READ);
        assertTrue(tools.keepsBrowserOpen(a));
        port.browser(a).probe = BrowserProbe.clear();
        invoke(bound, BrowserMcpTools.READ);
        assertFalse(tools.keepsBrowserOpen(a));
    }

    @Test
    public void linkedDocumentsAreMarkedAndReadThroughTheDocumentPipeline() {
        ChatConversationId a = new ChatConversationId("chat-a");
        BrowserMcpTools withDocuments = new BrowserMcpTools(port, (name, bytes) ->
                name + ": " + new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        withDocuments.beginTurn(a, null);
        List<McpToolContribution> bound = withDocuments.contributionsFor(a);

        McpToolResult read = invoke(bound, BrowserMcpTools.DOCUMENT, "link", "link-1");

        assertFalse(read.getText(), read.isError());
        assertTrue(read.getText(), read.getText().contains("reise.pdf: Reisekostenrichtlinie 2026"));
        assertTrue(BrowserMcpTools.isDocument("https://intranet.example/a/Richtlinie.PDF?x=1"));
        assertFalse(BrowserMcpTools.isDocument("https://intranet.example/pdf/"));
        assertTrue(invoke(bound, BrowserMcpTools.MARK).getText().contains("relevant"));
        assertEquals(1, withDocuments.endTurn(a).relevant());
        assertTrue(invoke(tools.contributionsFor(a), BrowserMcpTools.DOCUMENT, "link", "link-1").isError());
    }

    private static final class RecordingPort implements ChatBrowserPort {

        private final Map<ChatConversationId, RecordingBrowser> browsers =
                new HashMap<ChatConversationId, RecordingBrowser>();

        RecordingBrowser browser(ChatConversationId id) {
            RecordingBrowser browser = browsers.get(id);
            if (browser == null) {
                browser = new RecordingBrowser();
                browsers.put(id, browser);
            }
            return browser;
        }

        @Override
        public ChatBrowser browserOf(ChatConversationId conversation) {
            return browser(conversation);
        }

        @Override
        public void release(ChatConversationId conversation) {
        }

        @Override
        public void deleteProfile(ChatConversationId conversation) {
        }

        @Override
        public void releaseAll() {
        }
    }

    private static final class RecordingBrowser implements ChatBrowser {

        final List<String> calls = new ArrayList<String>();
        boolean fail;
        BrowserProbe probe = BrowserProbe.clear();

        private void check() throws BrowserException {
            if (fail) {
                throw new BrowserException(BrowserException.Kind.FAILED, "kaputt");
            }
        }

        @Override
        public List<BrowserTab> tabs() throws BrowserException {
            check();
            return Collections.singletonList(new BrowserTab("tab-1", "Start", "https://start.example/", true));
        }

        @Override
        public BrowserTab selectTab(String tabId) throws BrowserException {
            check();
            calls.add("select " + tabId);
            return null;
        }

        @Override
        public List<com.aresstack.enterpriseai.browser.api.BrowserBatchPage> openMany(List<String> urls,
                                                                                      int parallel, int delay)
                throws BrowserException {
            check();
            calls.add("openMany " + urls.size() + " " + parallel + " " + delay);
            return Arrays.asList(
                    com.aresstack.enterpriseai.browser.api.BrowserBatchPage.loaded(urls.get(0),
                            new BrowserPage("tab-9", urls.get(0), "Seite A", "Inhalt A"), null),
                    com.aresstack.enterpriseai.browser.api.BrowserBatchPage.skipped(urls.get(1), "gesperrt"),
                    com.aresstack.enterpriseai.browser.api.BrowserBatchPage.failed(urls.get(2), "weg"));
        }

        @Override
        public void setHumanEmulation(boolean keys, boolean mouse) {
            calls.add("human " + keys + " " + mouse);
        }

        @Override
        public BrowserTab newTab(String url) throws BrowserException {
            check();
            calls.add("new " + url);
            return null;
        }

        @Override
        public void closeTab(String tabId) throws BrowserException {
            check();
            calls.add("close " + tabId);
        }

        @Override
        public BrowserPage navigate(String tabId, String url) throws BrowserException {
            check();
            calls.add("navigate " + url);
            return new BrowserPage("tab-1", url, "T", "text");
        }

        @Override
        public BrowserPage read(String tabId) throws BrowserException {
            check();
            calls.add("read " + tabId);
            return new BrowserPage("tab-1", "https://start.example/", "Start", "Lorem ipsum");
        }

        @Override
        public BrowserLinks links(String tabId, int maxLinks, String filter) throws BrowserException {
            check();
            calls.add("links " + tabId + " " + maxLinks + " " + filter);
            BrowserLink ki = new BrowserLink("link-2", "KI", "https://intranet.example/ki", "KI-Portal");
            return filter == null ? new BrowserLinks("tab-1", "https://intranet.example/", "Intranet",
                    java.util.Arrays.asList(new BrowserLink("link-1", "Reisekosten", "https://intranet.example/reise",
                            ""), ki), 2)
                    : new BrowserLinks("tab-1", "https://intranet.example/", "Intranet",
                    java.util.Collections.singletonList(new BrowserLink("link-1", "KI", ki.url(), ki.label())), 1);
        }

        @Override
        public BrowserPage follow(String tabId, String linkId) throws BrowserException {
            check();
            calls.add("follow " + linkId);
            return new BrowserPage("tab-1", "https://intranet.example/ki", "KI", "text");
        }

        @Override
        public BrowserPage back(String tabId) throws BrowserException {
            check();
            calls.add("back " + tabId);
            return new BrowserPage("tab-1", "https://intranet.example/", "Intranet", "text");
        }

        @Override
        public BrowserControls controls(String tabId, int maxControls, String filter) throws BrowserException {
            check();
            calls.add("controls " + maxControls + " " + filter);
            return new BrowserControls("tab-1", "https://intranet.example/", "Intranet", java.util.Arrays.asList(
                    new BrowserControl("ctl-1", "search", "Suche", "", "", "https://intranet.example/suche"),
                    new BrowserControl("ctl-2", "select", "Sprache", "Deutsch", "Deutsch | English", ""),
                    new BrowserControl("ctl-3", "input", "Betrag (EUR)", "", "", "https://intranet.example/rechnung",
                            "Neue eRechnung", true, "label=\"Betrag (EUR)\"", "Mitte")), 3);
        }

        @Override
        public BrowserActResult act(String tabId, String controlId, BrowserAction action, String value)
                throws BrowserException {
            check();
            calls.add("act " + controlId + " " + action + " " + value);
            boolean submit = action == BrowserAction.SUBMIT;
            return new BrowserActResult(new BrowserPage("tab-1", submit ? "https://intranet.example/suche?q=" + value
                    : "https://intranet.example/", submit ? "Treffer" : "Intranet", "text"), submit, !submit);
        }

        @Override
        public BrowserActResult actLocated(String tabId, String locator, BrowserAction action, String value)
                throws BrowserException {
            check();
            calls.add("actLocated " + locator + " " + action + " " + value);
            return new BrowserActResult(new BrowserPage("tab-1", "https://intranet.example/", "Intranet", "text"),
                    false, true);
        }

        @Override
        public BrowserDocument download(String tabId, String linkId) throws BrowserException {
            check();
            calls.add("download " + linkId);
            return new BrowserDocument("https://intranet.example/reise.pdf", "reise.pdf", "application/pdf",
                    "Reisekostenrichtlinie 2026".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public BrowserScreenshot screenshot(String tabId, String controlId, boolean marks) throws BrowserException {
            check();
            calls.add("screenshot " + controlId + " " + marks);
            return new BrowserScreenshot("tab-1", "https://intranet.example/", "Intranet", "image/jpeg",
                    new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2}, marks, java.util.Arrays.asList(
                    new BrowserControl("ctl-1", "search", "Alles durchsuchen", "", "", "", "", false, "",
                            "oben rechts")));
        }

        @Override
        public BrowserProbe probe(String tabId) {
            return probe;
        }

        @Override
        public void closeBrowser() {
            calls.add("closeBrowser");
        }

        @Override
        public boolean isRunning() {
            return false;
        }
    }
}
