package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.render.RenderedContainerDescriptor;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.SearchPageAnalysisOutcome;
import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.SearchResultExtractionResult;
import com.aresstack.askai.browser.search.analysis.LegacySearchResultExtractor;
import com.google.gson.Gson;
import org.junit.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #22, "Java 21 LTS release": Bing served ten {@code li.b_algo} results, all visible, all
 * children of {@code ol#b_results} — preceded by AI answer cards ({@code li.b_ans}). The captures are
 * two real runs of {@code CamoufoxBingSearchLiveTest} (2026-10-31). Before the fix the answer card in
 * front split the results into two structure clusters and only the larger one was kept: run 1 lost
 * five of ten results, run 2 lost three (the ones with a sitelink row), and the answer cards
 * themselves were taken as results.
 */
public class BingAnswerCardRegressionTest {

    private static final String DIR = "/serp/bing-java21-2026-10-31/";
    private static final String QUERY = "Java 21 LTS release";

    /** Bing's organic results, in Bing's order — identical in both runs. */
    private static final List<String> ORGANIC = Arrays.asList(
            "https://www.oracle.com/java/technologies/javase/21all-relnotes.html",
            "https://versionlog.com/java/21/",
            "https://www.oracle.com/java/technologies/java-se-support-roadmap.html",
            "https://adoptium.net/temurin/releases/?version=21",
            "https://ops.java/releases/",
            "https://en.wikipedia.org/wiki/Java_version_history",
            "https://openjdk.org/projects/jdk/21/",
            "https://bell-sw.com/blog/liberica-jdk-21-lts-release-a-lasting-foundation-for-your-java-application/",
            "https://jdk.java.net/21/",
            "https://www.innoq.com/de/podcast/135-java-21/");

    @Test
    public void runOneExtractsAllTenResultsInBingsOrder() throws Exception {
        assertTenOrganicResults("answer-card-run-1.capture.json");
    }

    @Test
    public void runTwoExtractsAllTenResultsInBingsOrder() throws Exception {
        assertTenOrganicResults("answer-card-run-2.capture.json");
    }

    @Test
    public void bothCapturesReallyCarryTenVisibleResultsBehindAnAnswerCard() throws Exception {
        for (String capture : Arrays.asList("answer-card-run-1.capture.json",
                "answer-card-run-2.capture.json")) {
            RenderedPageDocument document = load(capture);
            RenderedContainerDescriptor list = null;
            for (RenderedContainerDescriptor container : document.containers) {
                if (container.elementId.equals("b_results")) {
                    list = container;
                }
            }
            int results = 0;
            for (String childId : list.childContainerIds) {
                RenderedContainerDescriptor child = document.container(childId);
                if (child.classTokens.contains("b_algo")) {
                    assertTrue(childId + " visible", child.visible);
                    results++;
                }
            }
            assertEquals(capture, 10, results);
            assertTrue(capture + ": an answer card comes first",
                    document.container(list.childContainerIds.get(0)).classTokens
                            .contains("b_ans"));
        }
    }

    private void assertTenOrganicResults(String capture) throws Exception {
        RenderedPageDocument document = load(capture);

        SearchResultExtractionResult extraction =
                new LegacySearchResultExtractor(LegacyBrowserSearchDefaults.create())
                        .extract(document, QUERY);

        assertEquals(extraction.diagnostics.toString(),
                SearchPageAnalysisOutcome.ORGANIC_RESULTS, extraction.outcome);
        List<String> structured = new ArrayList<String>();
        for (SearchResultCandidate candidate : extraction.candidates) {
            assertFalse("an organic result is not repeated as a harvested link", candidate.resultBlockContainerId
                    .isEmpty() && ORGANIC.contains(candidate.resolvedTargetUrl));
            if (!candidate.resultBlockContainerId.isEmpty()) {
                RenderedContainerDescriptor block =
                        document.container(candidate.resultBlockContainerId);
                assertTrue("only organic results are result blocks, not "
                                + block.classTokens + " (" + candidate.resolvedTargetUrl + ")",
                        block.classTokens.contains("b_algo"));
                structured.add(candidate.resolvedTargetUrl);
            }
        }
        assertEquals(extraction.diagnostics.toString(), ORGANIC, structured);
        for (int i = 0; i < structured.size(); i++) {
            assertEquals("Bing rank kept", i + 1, extraction.candidates.get(i).originalRank);
        }
        assertTrue("the answer cards are reported, not silently dropped: "
                        + extraction.diagnostics,
                extraction.diagnostics.toString().contains("not part of the result list"));
    }

    private static RenderedPageDocument load(String name) throws Exception {
        return new Gson().fromJson(read(name), RenderedPageDocument.class);
    }

    private static String read(String name) throws Exception {
        InputStream in = BingAnswerCardRegressionTest.class.getResourceAsStream(DIR + name);
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
