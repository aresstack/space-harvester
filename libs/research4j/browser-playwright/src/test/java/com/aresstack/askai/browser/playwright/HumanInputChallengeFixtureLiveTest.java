package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserLimits;
import com.aresstack.askai.browser.BrowserPageReadiness;
import com.aresstack.askai.browser.BrowserSession;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * #44 — the LOCAL challenge/consent fixture (environment-gated live test, same pattern as
 * {@link PlaywrightLiveBrowserTest}): a real local browser against local pages that SIMULATE
 * a consent banner and a CAPTCHA-like challenge, so pointer paths, click pauses and the
 * detect → pause → user-solves → resume handoff are testable deterministically WITHOUT ever
 * automating a real CAPTCHA service. The challenge fixture "solves itself" after a short
 * delay — the stand-in for the human's manual click; automation never touches it.
 */
public class HumanInputChallengeFixtureLiveTest {

    /**
     * A consent banner whose accept button matches the DEFAULT positive text markers. It only reacts to a TRUSTED
     * click (issue #22): a script click ({@code el.click()}, untrusted) leaves the banner in place, so passing proves
     * the real mouse was used.
     */
    private static final String CONSENT_PAGE = "<!doctype html><html><head><title>Consent</title>"
            + "</head><body>"
            + "<div id='banner' style='position:fixed;inset:0;background:#fffbe6;z-index:9;"
            + "display:flex;align-items:center;justify-content:center;'>"
            + "<button id='ok' style='padding:14px 28px;font-size:16px;' "
            + "onclick=\"if(!event.isTrusted){return;}document.getElementById('banner').remove();"
            + "document.getElementById('c').textContent='consent resolved content';\">"
            + "Accept all</button></div>"
            + "<div id='c'>waiting for consent</div>"
            + "</body></html>";

    /**
     * A consent control BELOW the window (2500 px down, not fixed): the pointer must scroll there with the wheel first.
     * Camoufox runs without viewport emulation, so the window height comes from its fingerprint. Trusted clicks only.
     */
    private static final String CONSENT_BELOW_PAGE = "<!doctype html><html><head><title>Below</title></head><body>"
            + "<div id='c'>waiting for consent</div><div style='height:2500px'></div>"
            + "<div id='banner'><button id='ok' style='padding:14px 28px;font-size:16px;' "
            + "onclick=\"if(!event.isTrusted){return;}document.getElementById('banner').remove();"
            + "document.getElementById('c').textContent='consent below resolved';\">Accept all</button></div>"
            + "</body></html>";

    /**
     * Two stacked banners with the SAME control at the SAME spot: removing the first one puts the second one's button
     * exactly where the clicked one was. Both must be dismissed (the clicked element is tracked, not its position).
     */
    private static final String STACKED_PAGE = "<!doctype html><html><head><title>Stacked</title></head><body>"
            + stackedBanner("b2", "z-index:8") + stackedBanner("b1", "z-index:9")
            + "<div id='c'>waiting for consent</div></body></html>";

    private static String stackedBanner(String id, String z) {
        return "<div id='" + id + "' style='position:fixed;inset:0;background:#fffbe6;" + z + ";"
                + "display:flex;align-items:center;justify-content:center;'>"
                + "<button style='padding:14px 28px;font-size:16px;' onclick=\"if(!event.isTrusted){return;}"
                + "document.getElementById('" + id + "').remove();var c=document.getElementById('c');"
                + "c.textContent=(c.textContent==='waiting for consent'?'':c.textContent)+'[" + id + "]';\">"
                + "Accept all</button></div>";
    }

    /**
     * A CAPTCHA-like page: the DEFAULT challenge phrase is visible and the content is blocked.
     * After ~1.5s the fixture replaces itself with readable content — simulating the HUMAN
     * solving the checkbox; the sidecar only detects, pauses and re-probes, it never solves.
     */
    private static final String CHALLENGE_PAGE = "<!doctype html><html><head>"
            + "<title>One last step</title></head><body>"
            + "<div id='gate'><p>Verify you are human</p>"
            + "<input type='checkbox' id='cb'><label for='cb'>I am human</label>"
            + "<button id='continue'>Continue</button></div>"
            + "<script>setTimeout(function(){"
            + "document.getElementById('gate').remove();"
            + "var d=document.createElement('div');"
            + "d.textContent='The protected article content is now readable after the manual "
            + "step. It contains enough text to count as a real page for the readiness "
            + "probe and demonstrates that the SAME run continues after the handoff.';"
            + "document.body.appendChild(d);},1500);</script>"
            + "</body></html>";

    @Test
    public void consentIsClickedWithTheHumanPointerAndChallengeHandsOffToTheUser()
            throws Exception {
        String channel = System.getenv().getOrDefault("ASKAI_TEST_BROWSER_CHANNEL", "chrome");
        PlaywrightReadiness readiness = new PlaywrightCapabilityProbe().probe(channel);
        assumeTrue("SKIPPED (environment-gated live test): " + readiness.render(),
                readiness.isReady());

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/consent", page(CONSENT_PAGE));
        server.createContext("/challenge", page(CHALLENGE_PAGE));
        server.createContext("/below", page(CONSENT_BELOW_PAGE));
        server.createContext("/stacked", page(STACKED_PAGE));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        BrowserSession session = PlaywrightSessionFactory.create(channel, true, true,
                base + "/find?q={query}", BrowserLimits.defaults(),
                com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create());
        try {
            // ---- consent: detected, then clicked with the HUMAN pointer path ----
            BrowserPageReadiness beforeConsent = session.probe(base + "/consent");
            assertTrue("the fixture banner must be detected as consent: "
                    + beforeConsent.consentPresent, beforeConsent.consentPresent);
            String clicked = session.dismissConsent();
            assertTrue("the consent control was clicked: " + clicked,
                    clicked.startsWith("clicked"));
            assertTrue("the POINTER path landed (there is no JS-click fallback anymore): " + clicked,
                    clicked.contains(":pointer"));
            BrowserPageReadiness afterConsent = session.probeCurrent();
            assertFalse("the banner is gone", afterConsent.consentPresent);
            assertTrue(afterConsent.excerpt != null
                    && afterConsent.excerpt.contains("consent resolved content"));

            // ---- consent below the window: real wheel scrolling, then the trusted click ----
            session.probe(base + "/below");
            String below = session.dismissConsent();
            assertTrue("the control below the window was reached and clicked: " + below,
                    below.startsWith("clicked") && below.contains(":pointer"));
            BrowserPageReadiness afterBelow = session.probeCurrent();
            assertTrue(afterBelow.excerpt, afterBelow.excerpt != null
                    && afterBelow.excerpt.contains("consent below resolved"));

            // ---- stacked banners with the same control at the same spot: both are dismissed ----
            // One dismiss attempt per call (default): the first call must count as clicked although an equal control
            // now sits where the clicked one was; the second call takes the next banner.
            session.probe(base + "/stacked");
            String first = session.dismissConsent();
            assertTrue("the first stacked banner counts as dismissed: " + first, first.startsWith("clicked"));
            String second = session.dismissConsent();
            assertTrue("the second stacked banner: " + second, second.startsWith("clicked"));
            BrowserPageReadiness afterStacked = session.probeCurrent();
            assertTrue(afterStacked.excerpt, afterStacked.excerpt != null
                    && afterStacked.excerpt.contains("[b1][b2]"));

            // ---- challenge: detect → pause (no automation) → the human solves → resume ----
            BrowserPageReadiness blocked = session.probe(base + "/challenge");
            assertTrue("the CAPTCHA-like gate must read as a VISIBLE challenge",
                    blocked.challengeVisible);
            // The sidecar does NOT touch the page. The fixture's timer is the human stand-in.
            Thread.sleep(2500L);
            BrowserPageReadiness resolved = session.probeCurrent();
            assertFalse("after the manual step the challenge is gone",
                    resolved.challengeVisible);
            assertTrue("the SAME run reads the now-unblocked content",
                    resolved.excerpt != null
                            && resolved.excerpt.contains("protected article content"));
        } finally {
            session.close();
            server.stop(0);
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
