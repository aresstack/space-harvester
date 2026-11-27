package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.domain.DomainClassification;
import com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver;
import com.aresstack.askai.browser.render.LinkRedirectResolution;
import com.aresstack.askai.browser.render.RenderedLinkDescriptor;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.SearchPageAnalysisOutcome;
import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.SearchResultExtractionResult;
import com.aresstack.askai.browser.search.analysis.LegacySearchResultExtractor;
import com.google.gson.Gson;
import com.aresstack.askai.browser.search.UrlResolutionStatus;
import org.junit.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Live acceptance of #28 (Angelo, 2026-10-31, "Java 21 LTS release"): Google searched, AskAI extracted nothing
 * ({@code EXTRACTION_FAILED} on all three result pages). Google's 2026 result links are {@code /goto?url=<token>}:
 * the token is opaque, the target is not in the URL. The resolver only knew {@code /url?q=}, so every result link
 * counted as a Google-internal link, and the diagnosis read like an unknown layout.
 *
 * <p>{@code chromium-capture.json} is the current capture script run on the real result page Camoufox saved in that
 * run ({@code page.html.gz}, sanitized: scripts and HTML comments removed (Google keeps result data in them),
 * session parameters and event ids blanked, the location line neutralised, every {@code /goto} token replaced by
 * {@code test-goto-N}).</p>
 *
 * <p>Decision (Angelo, 2026-10-31): such links are discarded. Asking Google for each target ahead of time (the 302 of
 * {@code /goto}) took about 1.5 s per link through the browser, and after the first burst of direct requests Google
 * answered with a check page instead of a redirect. So {@code /goto} is never a source URL, it stays
 * {@code UNRESOLVED} ({@link OpaqueRedirectStrategy#DISCARD}), and the attempt names the reason.</p>
 *
 * <p>Since askai #41/#47 (lazy resolution, Angelo 2026-11-02) such a hit is no longer dropped: it is kept as an
 * UNVERIFIED candidate whose navigation URL is the raw {@code /goto} link, and only the browser resolves it when the
 * hit is opened. It is still never a verified target and never a Google URL as source.</p>
 */
public class GoogleGotoRegressionTest {

    private static final String DIR = "/serp/google-java21-2026-10-31/";
    private static final String QUERY = "Java 21 LTS release";
    private static final String URL = "https://www.google.com/search?q=Java+21+LTS+release";

    private final LegacyBrowserSearchSettings settings = LegacyBrowserSearchDefaults.create();

    @Test
    public void everyGotoLinkStaysUnresolvedAndIsNeitherAGoogleLinkNorAResult() throws Exception {
        RenderedPageDocument page = capture();

        List<RenderedLinkDescriptor> gotos = gotoLinks(page);
        assertEquals(33, gotos.size());
        for (RenderedLinkDescriptor link : gotos) {
            assertEquals(link.rawHref, LinkRedirectResolution.UNRESOLVED, link.redirectResolutionStatus);
            assertEquals(link.rawHref, "", link.resolvedTargetUrl);
            assertNotEquals("never judged by the wrapper's own host: " + link.rawHref,
                    DomainClassification.SAME_HOST, link.domainClassification);
        }
        SearchResultExtractionResult extraction = new LegacySearchResultExtractor(settings).extract(page, QUERY);
        for (SearchResultCandidate candidate : extraction.candidates) {
            assertFalse("a Google wrapper is never a source: " + candidate.resolvedTargetUrl,
                    candidate.resolvedTargetUrl.contains("google.com"));
            if (candidate.rawSearchHref.contains("/goto")) {
                assertEquals("lazy resolution (#41): kept unverified", UrlResolutionStatus.UNRESOLVED,
                        candidate.urlResolution);
                assertFalse("never a verified target", candidate.hasVerifiedTarget());
                assertEquals("the browser opens the raw /goto link", candidate.rawSearchHref,
                        candidate.navigationUrl());
            }
        }
    }

    @Test
    public void theAttemptNamesTheOpaqueRedirectsInsteadOfAnUnknownLayout() throws Exception {
        RenderedPageDocument page = capture();
        assertTrue(page.captureWarnings.toString(),
                page.captureWarnings.toString().contains("OPAQUE_REDIRECTS: 33 links are opaque engine wrappers"));

        SearchResultExtractionResult extraction = new LegacySearchResultExtractor(settings).extract(page, QUERY);
        String diagnostic = PlaywrightBrowserSession.failureDiagnostic(page, extraction);

        assertTrue(diagnostic, diagnostic.startsWith("OPAQUE_REDIRECTS: 33 links"));
        assertTrue(diagnostic, diagnostic.contains("never results"));
    }

    private static List<RenderedLinkDescriptor> gotoLinks(RenderedPageDocument page) {
        List<RenderedLinkDescriptor> gotos = new ArrayList<RenderedLinkDescriptor>();
        for (RenderedLinkDescriptor link : page.links) {
            if (link.rawHref.startsWith("https://www.google.com/goto?url=")) {
                gotos.add(link);
            }
        }
        return gotos;
    }

    /** Feeds the raw capture through the real Java side of the capture. */
    private RenderedPageDocument capture() throws Exception {
        @SuppressWarnings("unchecked")
        final Map<String, Object> raw = new Gson().fromJson(read("chromium-capture.json"), Map.class);
        RenderedPageDocumentCapture capture = new RenderedPageDocumentCapture(settings.analysis);
        return capture.capture(new RenderedPageDocumentCapture.PageScriptRunner() {
            public Object evaluate(String script) {
                return script.equals(RenderedPageDocumentCapture.FINGERPRINT_SCRIPT) ? "f" : raw;
            }

            public String url() {
                return URL;
            }

            public String title() {
                return QUERY + " - Google Suche";
            }
        }, new PublicSuffixDomainKeyResolver(), 1L);
    }

    private static String read(String name) throws Exception {
        InputStream in = GoogleGotoRegressionTest.class.getResourceAsStream(DIR + name);
        if (in == null) {
            throw new IllegalStateException("missing test resource " + DIR + name);
        }
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[8192];
            for (int n; (n = reader.read(buffer)) > 0; ) {
                sb.append(buffer, 0, n);
            }
            return sb.toString();
        }
    }
}
