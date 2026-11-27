package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserLimits;
import com.aresstack.askai.browser.BrowserPageReadiness;
import com.aresstack.askai.browser.BrowserSession;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCodec;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * The challenge detection's visibility contract in a REAL browser (environment-gated like
 * {@link HumanInputChallengeFixtureLiveTest}; Chrome by default, {@code ASKAI_TEST_BROWSER_CHANNEL=camoufox} runs
 * the same probes through the research add-on's isolated world): what the user does not see on the first screen
 * never parks a page, a real interstitial always does.
 *
 * <ul>
 *   <li>INNOQ regression (run "Java 21 LTS release", 2026-10-31): a readable article whose footer contact form
 *       carries a Friendly Captcha widget ("FriendlyCaptcha") was parked as a CAPTCHA. It must read as READABLE —
 *       with the current defaults AND for a user who keeps the bare word "captcha" in a custom list.</li>
 *   <li>Counter-checks that must still block: Bing "one last step", a Cloudflare Turnstile challenge, a phrase-only
 *       German challenge whose words are split by inline markup, a phrase split across block
 *       elements ({@code innerText} "verify\nyou are human"), a captcha in the page's own
 *       {@code #captcha-form} (Google /sorry/ shape), a captcha form with nothing readable besides it.</li>
 *   <li>Review PR #33: a challenge phrase at the END of one long text node that starts on the first screen is a
 *       hidden artifact (the phrase's own box decides, not the node's); a VISIBLE captcha in an ordinary contact
 *       form of a readable page is reported as {@code embedded:}, never blocking.</li>
 *   <li>A results page ABOUT CAPTCHAs is no challenge; a challenge phrase quoted only below the fold is a hidden
 *       artifact.</li>
 * </ul>
 *
 * The opt-in {@code ASKAI_LIVE_INNOQ=1} additionally probes the real INNOQ page.
 */
public class ChallengeVisibilityFixtureLiveTest {

    private static final String INNOQ_URL = "https://www.innoq.com/de/podcast/135-java-21/";

    @Test
    public void onlyAChallengeOnTheFirstScreenParksAPage() throws Exception {
        String channel = System.getenv().getOrDefault("ASKAI_TEST_BROWSER_CHANNEL", "chrome");
        PlaywrightReadiness readiness = new PlaywrightCapabilityProbe().probe(channel);
        assumeTrue("SKIPPED (environment-gated live test): " + readiness.render(), readiness.isReady());

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (String fixture : new String[]{"innoq-footer-friendly-captcha", "bing-one-last-step",
                "cloudflare-turnstile", "text-only-de", "text-only-mixed-inline", "text-only-block-split", "serp-about-captchas",
                "phrase-below-the-fold", "long-text-node-phrase-below", "contact-form-visible-captcha",
                "google-sorry-captcha-form", "captcha-gate-form"}) {
            server.createContext("/" + fixture, page(resource("challenge/" + fixture + ".html")));
        }
        server.createContext("/blank", page("<!doctype html><html><body></body></html>"));
        server.createContext("/cdn-cgi/", page("<!doctype html><html><body><input type=checkbox></body></html>"));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            BrowserSession session = PlaywrightSessionFactory.create(channel, true, true,
                    base + "/find?q={query}", BrowserLimits.defaults(), LegacyBrowserSearchDefaults.create());
            try {
                BrowserPageReadiness innoq = probe(session, base + "/innoq-footer-friendly-captcha");
                assertFalse("INNOQ: the footer-form widget below the fold must not block: " + innoq.challengeMarker,
                        innoq.challengeVisible);
                assertTrue("INNOQ: the widget is still reported as an artifact: " + innoq.challengeMarker,
                        innoq.challengeMarker.startsWith("hidden:"));

                BrowserPageReadiness bing = probe(session, base + "/bing-one-last-step");
                assertTrue("Bing 'one last step' must block: " + bing.challengeMarker, bing.challengeVisible);

                BrowserPageReadiness cloudflare = probe(session, base + "/cloudflare-turnstile");
                assertTrue("Cloudflare Turnstile must block: " + cloudflare.challengeMarker,
                        cloudflare.challengeVisible);

                BrowserPageReadiness textOnly = probe(session, base + "/text-only-de");
                assertTrue("a phrase-only challenge on the first screen must block: " + textOnly.challengeMarker,
                        textOnly.challengeVisible
                                && textOnly.challengeMarker.equals("visible:text:bestätigen sie, dass sie ein mensch sind"));

                BrowserPageReadiness mixed = probe(session, base + "/text-only-mixed-inline");
                assertTrue("whitespace between inline elements plus a split word must still block: "
                        + mixed.challengeMarker, mixed.challengeVisible
                        && mixed.challengeMarker.equals("visible:text:verify you are human"));

                BrowserPageReadiness blockSplit = probe(session, base + "/text-only-block-split");
                assertTrue("review #33 (2): a phrase split across block elements on the first screen must block: "
                        + blockSplit.challengeMarker, blockSplit.challengeVisible
                        && blockSplit.challengeMarker.equals("visible:text:verify you are human"));

                BrowserPageReadiness belowFold = probe(session, base + "/phrase-below-the-fold");
                assertFalse("a challenge phrase only below the fold must not block: " + belowFold.challengeMarker,
                        belowFold.challengeVisible);
                assertTrue(belowFold.challengeMarker,
                        belowFold.challengeMarker.equals("hidden:text:verify you are human"));

                BrowserPageReadiness longNode = probe(session, base + "/long-text-node-phrase-below");
                assertFalse("review #33: a long text node that starts on screen but carries the phrase below the "
                        + "fold must not block: " + longNode.challengeMarker, longNode.challengeVisible);
                assertTrue(longNode.challengeMarker,
                        longNode.challengeMarker.equals("hidden:text:verify you are human"));

                BrowserPageReadiness contact = probe(session, base + "/contact-form-visible-captcha");
                assertFalse("review #33: a visible captcha in an ordinary contact form of a readable page must not "
                        + "block: " + contact.challengeMarker, contact.challengeVisible);
                assertTrue(contact.challengeMarker, contact.challengeMarker.startsWith("embedded:"));

                BrowserPageReadiness sorry = probe(session, base + "/google-sorry-captcha-form");
                assertTrue("a captcha in the page's own #captcha-form must block: " + sorry.challengeMarker,
                        sorry.challengeVisible);

                BrowserPageReadiness gate = probe(session, base + "/captcha-gate-form");
                assertTrue("a captcha form with nothing readable besides it must block: " + gate.challengeMarker,
                        gate.challengeVisible);

                BrowserPageReadiness serp = probe(session, base + "/serp-about-captchas");
                assertFalse("a results page about CAPTCHAs is no challenge: " + serp.challengeMarker,
                        serp.challengeVisible);
            } finally {
                session.close();
            }

            // A user who deliberately keeps the bare word: the phrase rule's own viewport gate must still keep the
            // below-the-fold "FriendlyCaptcha" from blocking.
            Map<String, String> values = LegacyBrowserSearchSettingsCodec.toValues(LegacyBrowserSearchDefaults.create());
            values.put("captcha.challengeTexts", "captcha\none last step\nverify you are human");
            LegacyBrowserSearchSettings custom = LegacyBrowserSearchSettingsCodec.fromValues(values).settings;
            assertTrue(custom.captcha.challengeTexts.contains("captcha"));
            BrowserSession customSession = PlaywrightSessionFactory.create(channel, true, true,
                    base + "/find?q={query}", BrowserLimits.defaults(), custom);
            try {
                BrowserPageReadiness innoq = probe(customSession, base + "/innoq-footer-friendly-captcha");
                assertFalse("custom 'captcha' entry: an off-screen phrase must not block: " + innoq.challengeMarker,
                        innoq.challengeVisible);
                assertTrue(innoq.challengeMarker, innoq.challengeMarker.startsWith("hidden:"));
                BrowserPageReadiness cloudflare = probe(customSession, base + "/cloudflare-turnstile");
                assertTrue(cloudflare.challengeMarker, cloudflare.challengeVisible);
            } finally {
                customSession.close();
            }
        } finally {
            server.stop(0);
        }
    }

    /** Opt-in: the real INNOQ page, as the research run opened it ({@code ASKAI_LIVE_INNOQ=1}). */
    @Test
    public void theRealInnoqPageIsReadable() throws Exception {
        assumeTrue("SKIPPED: set ASKAI_LIVE_INNOQ=1 to probe the real INNOQ page",
                "1".equals(System.getenv("ASKAI_LIVE_INNOQ")));
        String channel = System.getenv().getOrDefault("ASKAI_TEST_BROWSER_CHANNEL", "chrome");
        PlaywrightReadiness readiness = new PlaywrightCapabilityProbe().probe(channel);
        assumeTrue("SKIPPED (environment-gated live test): " + readiness.render(), readiness.isReady());
        BrowserSession session = PlaywrightSessionFactory.create(channel, true, false,
                "https://www.bing.com/search?q={query}", BrowserLimits.defaults(), LegacyBrowserSearchDefaults.create());
        try {
            BrowserPageReadiness innoq = probe(session, INNOQ_URL);
            assertTrue("the INNOQ article was read: " + innoq.textLength, innoq.textLength > 1000);
            assertFalse("the INNOQ article must not read as a challenge: " + innoq.challengeMarker,
                    innoq.challengeVisible);
        } finally {
            session.close();
        }
    }

    private static BrowserPageReadiness probe(BrowserSession session, String url) throws Exception {
        BrowserPageReadiness readiness = session.probe(url);
        assertNotNull(readiness);
        System.err.println("[challenge-visibility] " + readiness.url + " title=" + readiness.title
                + " text=" + readiness.textLength + " challengeVisible=" + readiness.challengeVisible
                + " marker=" + readiness.challengeMarker);
        return readiness;
    }

    private static String resource(String name) throws IOException {
        InputStream in = ChallengeVisibilityFixtureLiveTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull("missing test resource " + name, in);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n = in.read(buffer); n > 0; n = in.read(buffer)) {
                bytes.write(buffer, 0, n);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private static HttpHandler page(final String html) {
        return new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                byte[] body = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                OutputStream out = exchange.getResponseBody();
                out.write(body);
                out.close();
            }
        };
    }
}
