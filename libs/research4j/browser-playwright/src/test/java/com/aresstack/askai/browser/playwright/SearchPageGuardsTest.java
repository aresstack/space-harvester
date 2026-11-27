package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the guard policy: consent clicks only UNAMBIGUOUS controls, located by Playwright locators (no
 * first-button-in-container guessing — that could hit "Settings" or "Only necessary"), and the
 * challenge detection knows the required texts and selectors. The lists come from the settings
 * (single default origin: LegacyBrowserSearchDefaults) — the guards hold no constants of their own.
 */
public class SearchPageGuardsTest {

    private final LegacyBrowserSearchSettings defaults = LegacyBrowserSearchDefaults.create();

    @Test
    public void consentTargetsHaveNoFirstButtonGuessingAndOnlyPositiveTexts() {
        for (SearchPageGuards.ConsentTarget target : SearchPageGuards.consentTargets(defaults.consent)) {
            assertFalse("no dangerous first-button heuristic: " + target.selector,
                    target.selector.contains(":first-of-type"));
        }
        for (String selector : defaults.consent.positiveButtonSelectors) {
            assertFalse("selector must be accept-specific: " + selector,
                    selector.contains(":first-of-type"));
        }
        for (String required : new String[]{"accept all", "allow all", "i agree",
                "alle akzeptieren", "alle annehmen", "alle zulassen", "zustimmen"}) {
            assertTrue("positive text missing: " + required,
                    defaults.consent.positiveButtonTexts.contains(required));
        }
    }

    @Test
    public void consentResolutionPrefersRejectThenNecessaryThenAcceptThenClose() {
        List<String> actions = new ArrayList<String>();
        for (SearchPageGuards.ConsentTarget target : SearchPageGuards.consentTargets(defaults.consent)) {
            if (actions.isEmpty() || !actions.get(actions.size() - 1).equals(target.action)) {
                actions.add(target.action);
            }
        }
        assertEquals("minimal consent first, accept only as a fallback, close last",
                Arrays.asList("REJECT_ALL", "ONLY_NECESSARY", "ACCEPT_ALL", "CLOSE"), actions);
        SearchPageGuards.ConsentTarget first = SearchPageGuards.consentTargets(defaults.consent).get(0);
        assertEquals("#onetrust-reject-all-handler", first.selector);
        assertEquals(null, first.text);
    }

    @Test
    public void consentTextsMatchLikeTheOldInPageRule() {
        Pattern reject = textTarget("REJECT_ALL").text;
        assertTrue(reject.matcher("Reject all").find());
        assertTrue("leading whitespace and longer labels still match", reject.matcher("  Reject all cookies").find());
        assertTrue(reject.matcher("ALLE ABLEHNEN").find());
        assertFalse("never a phrase in the middle of a label", reject.matcher("Settings: reject all").find());
        assertFalse(reject.matcher("Settings").find());
        Pattern accept = textTarget("ACCEPT_ALL").text;
        assertTrue(accept.matcher("Accept all").find());
        assertFalse("accept never matches a reject control", accept.matcher("Reject all").find());
    }

    @Test
    public void consentTextsAlsoFindInputButtonsByTheirValue() {
        boolean found = false;
        for (SearchPageGuards.ConsentTarget target : SearchPageGuards.consentTargets(defaults.consent)) {
            if ("REJECT_ALL".equals(target.action) && target.text == null
                    && target.selector.contains("input[type='submit'][value^='reject all' i]")) {
                found = true;
            }
        }
        assertTrue("input buttons carry their label in value, which hasText does not see", found);
    }

    @Test
    public void regexLiteralsAreReadTheSameByJavaAndJavaScript() {
        assertEquals("i agree \\(cookies\\)", SearchPageGuards.regexLiteral("i agree (cookies)"));
        assertFalse("no \\Q...\\E quoting, the browser's engine would not understand it",
                SearchPageGuards.regexLiteral("a.b").contains("\\Q"));
        assertTrue(Pattern.compile(SearchPageGuards.regexLiteral("a.b+c")).matcher("a.b+c").matches());
        assertFalse(Pattern.compile(SearchPageGuards.regexLiteral("a.b")).matcher("axb").matches());
    }

    private SearchPageGuards.ConsentTarget textTarget(String action) {
        for (SearchPageGuards.ConsentTarget target : SearchPageGuards.consentTargets(defaults.consent)) {
            if (action.equals(target.action) && target.text != null) {
                return target;
            }
        }
        throw new AssertionError("no text target for " + action);
    }

    @Test
    public void consentReportDetectsARejectOnlyBanner() {
        String report = SearchPageGuards.consentReportScript(defaults.consent);
        // A banner offering only a "Reject all" control must still be flagged (so it gets resolved before read).
        assertTrue("report detects reject controls",
                report.contains("onetrust-reject-all-handler") && report.contains("reject all"));
        assertTrue(report.contains("candidate"));
    }

    @Test
    public void challengeScriptDetectsTheRequiredTextsAndSelectors() {
        String script = SearchPageGuards.challengeDetectScript(defaults.captcha);
        for (String text : new String[]{"noch ein letzter schritt", "one last step",
                "verify you are human", "unusual traffic", "complete the challenge",
                "bestätigen sie, dass sie ein mensch sind"}) {
            assertTrue("challenge text missing: " + text,
                    defaults.captcha.challengeTexts.contains(text));
        }
        // The bare word names a widget or a topic, not a challenge page: "FriendlyCaptcha" in INNOQ's footer
        // contact form parked a readable article as a CAPTCHA.
        assertFalse("the bare word 'captcha' is no challenge phrase",
                defaults.captcha.challengeTexts.contains("captcha"));
        for (String selector : new String[]{"iframe[src*='captcha']", "iframe[title*='challenge']",
                "#challenge-form", "[class*='captcha']", "[id*='captcha']"}) {
            assertTrue("challenge selector missing: " + selector,
                    defaults.captcha.challengeSelectors.contains(selector));
        }
        // Evidence-bearing, visibility-gated vocabulary.
        assertTrue("must distinguish a visible challenge", script.contains("'visible:'"));
        assertTrue("must distinguish a hidden artifact", script.contains("'hidden:'"));
        assertTrue("must distinguish an off-screen phrase", script.contains("'hidden:text:'"));
    }

    @Test
    public void challengePhrasesCountOnlyWhenRenderedInsideTheViewport() {
        String script = SearchPageGuards.challengeDetectScript(defaults.captcha);
        // The phrase rule shares the widget rule's visibility contract: the body-wide match only pre-filters,
        // the blocking verdict needs the phrase in text whose own box intersects the viewport.
        assertTrue(script.contains("createTreeWalker"));
        assertTrue(script.contains("getBoundingClientRect"));
        assertTrue("an off-screen phrase is reported, not blocking",
                script.indexOf("'hidden:text:'") > script.indexOf("'visible:text:'"));
        assertFalse("no blocking verdict straight from the whole body text",
                script.contains("if (body.includes(t)) return 'visible:text:'"));
        // Review PR #33: the box of the WHOLE text node is not the phrase's box (a long node that starts on screen
        // and carries the phrase below the fold). Only a range over exactly the phrase's characters is measured.
        assertFalse("the whole text node must not be measured", script.contains("selectNodeContents"));
        // Second review PR #33: innerText splits block elements with newlines ("verify\nyou are human"); the
        // pre-filter must collapse whitespace or the spaced walk is never reached.
        assertTrue(script.contains("const body = norm(bodyText)"));
        assertTrue(script.contains("replace(/\\s+/g, ' ')"));
        assertTrue(script.contains(".map(norm)"));
        assertTrue(script.contains("setStart(") && script.contains("setEnd(") && script.contains("getClientRects"));
    }

    @Test
    public void aVisibleWidgetInAnOrdinaryFormOfAReadablePageIsEmbeddedNotBlocking() {
        String script = SearchPageGuards.challengeDetectScript(defaults.captcha);
        // Review PR #33: a visible captcha in a contact form protects the form, it does not block the page.
        assertTrue(script.contains("closest('form')"));
        assertTrue("challenge forms (Google /sorry/ #captcha-form, Cloudflare challenge-form) still block",
                script.contains("/challenge|captcha/i"));
        assertTrue(script.contains(">= " + SearchPageGuards.EMBEDDED_MIN_OUTSIDE_CHARS));
        assertTrue("an embedded widget is reported, never blocking",
                script.contains("'embedded:'") && !script.contains("'visible:embedded"));
    }

    @Test
    public void challengeScriptGatesOnRealVisibilityNotMerePresence() {
        String script = SearchPageGuards.challengeDetectScript(defaults.captcha);
        // A proper visibility gate (layout + computed style + viewport box), NOT the old clientHeight OR-fallback
        // that false-positived on hidden contact-form recaptchas (the reactree bug).
        assertTrue(script.contains("getComputedStyle"));
        assertTrue(script.contains("getBoundingClientRect"));
        assertTrue(script.contains("offsetParent"));
        assertFalse("the el.clientHeight>0 OR-fallback (false-positives on hidden widgets) must be gone",
                script.contains("clientHeight > 0"));
    }
}
