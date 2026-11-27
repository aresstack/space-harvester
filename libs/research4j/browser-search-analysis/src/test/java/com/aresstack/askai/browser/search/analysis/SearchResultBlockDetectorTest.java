package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.domain.DomainClassification;
import com.aresstack.askai.browser.render.LinkRedirectResolution;
import com.aresstack.askai.browser.render.RenderedBox;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.render.DomStructureSignature;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The result-block business unit: repeated similar siblings become blocks with ONE primary title
 * link each; the snippet belongs to its OWN block; sitelinks are metadata, never own candidates;
 * resolved wrapper targets are the navigation candidate while raw wrappers stay provenance;
 * unresolved wrappers are never primary; a snippet-less isolated link scores clearly lower.
 */
public class SearchResultBlockDetectorTest {

    private final SearchPageMechanicalAnalyzer analyzer =
            new SearchPageMechanicalAnalyzer(LegacyBrowserSearchDefaults.create());
    private final SearchResultBlockDetector detector =
            new SearchResultBlockDetector(LegacyBrowserSearchDefaults.create());

    private SearchResultBlockDetector.Detection detect(SerpDocuments serp) {
        RenderedPageDocument document = serp.build();
        SearchPageLayoutResolution resolution = analyzer.analyze(document);
        assertTrue("fixture must resolve an organic container",
                resolution.hasOrganicResultsContainer());
        return detector.detect(document, resolution);
    }

    @Test
    public void repeatedBlocksYieldOnePrimaryLinkSnippetAndSitelinksEach() {
        SerpDocuments serp = SerpDocuments.builder();
        serp.addNavigationBar(8);
        String column = serp.addResultColumn(3,
                new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE);
        // Block 0 additionally carries two sitelinks and one engine-internal action link.
        String block0 = SerpDocuments.blockId(column, 0);
        serp.addLink(block0, "https://site0.example.org/docs", "Docs",
                DomainClassification.EXTERNAL_DOMAIN, false, "");
        serp.addLink(block0, "https://site0.example.org/download", "Download",
                DomainClassification.EXTERNAL_DOMAIN, false, "");
        serp.addLink(block0, "https://engine.example/translate", "Übersetzen",
                DomainClassification.SAME_HOST, false, "");

        SearchResultBlockDetector.Detection detection = detect(serp);
        assertEquals(3, detection.blocks.size());
        DetectedResultBlock first = detection.blocks.get(0);
        assertEquals(1, first.rank);
        assertEquals("Result 0 title", first.title);
        assertEquals("https://site0.example.org/page", first.primaryLink.resolvedTargetUrl);
        assertEquals("sitelinks are metadata of the primary hit", 2, first.siteLinks.size());
        for (DetectedResultBlock block : detection.blocks) {
            assertFalse("engine-internal action must never be the primary link",
                    block.primaryLink.resolvedTargetUrl.contains("/translate"));
        }
    }

    @Test
    public void aResultVariantWithAnotherClassButTheSameShapeStaysInTheList() {
        // Issue #22 review: a class token shared by most results is no reason on its own to drop the
        // others — six "normal" and four "featured" results of identical shape are ten results.
        SerpDocuments serp = SerpDocuments.builder();
        serp.addResultColumn(new RenderedBox(300, 120, 680, 1460), SerpDocuments.WHITE,
                java.util.Arrays.asList("normal", "featured", "normal", "normal", "featured",
                        "normal", "featured", "normal", "featured", "normal"));

        SearchResultBlockDetector.Detection detection = detect(serp);

        assertEquals(detection.rejectionReasons.toString(), 10, detection.blocks.size());
        for (int i = 0; i < 10; i++) {
            assertEquals("Result " + i + " title", detection.blocks.get(i).title);
        }
    }

    @Test
    public void aResultVariantWithAnotherClassAndASmallExtraElementStaysInTheList() {
        // Issue #22, second review: the majority shares one class and one identical shape; one
        // legitimate result carries another class AND an extra source line. Its shape is not the
        // majority's, but it reaches the cluster threshold against every one of them, so it stays.
        String plain = "li(h2(a),p)";
        String withSource = "li(h2(a),p,cite)";
        DomStructureSignature a = new DomStructureSignature(plain);
        DomStructureSignature b = new DomStructureSignature(withSource);
        double threshold = LegacyBrowserSearchDefaults.create().analysis.resultBlockSimilarityThreshold;
        double similarity = DomStructureClusterer.similarity(a, b);
        assertTrue("the variant differs from the majority (" + similarity + ")", similarity < 1);
        assertTrue("but stays above the threshold (" + similarity + ")", similarity >= threshold);
        SerpDocuments serp = SerpDocuments.builder();
        serp.addResultColumn(new RenderedBox(300, 120, 680, 1460), SerpDocuments.WHITE,
                java.util.Arrays.asList("result", "result", "result", "result", "result-with-source",
                        "result", "result"),
                java.util.Arrays.asList(plain, plain, plain, plain, withSource, plain, plain));

        SearchResultBlockDetector.Detection detection = detect(serp);

        assertEquals(detection.rejectionReasons.toString(), 7, detection.blocks.size());
        for (int i = 0; i < 7; i++) {
            assertEquals("Result " + i + " title", detection.blocks.get(i).title);
            assertEquals(i + 1, detection.blocks.get(i).rank);
        }
    }

    @Test
    public void twoVariantsAdmittedThroughTheRepresentativeBothStay() {
        // Issue #22, review of 7d19175: four plain results, one result with an extra div and one
        // "featured" result with a sitelink row. Both variants join the cluster through the plain
        // representative (0.73 each) although they are only 0.57 alike to each other. The featured
        // result carries everything every result has, so it stays: six results, not five.
        String plain = "li(h2(a),p)";
        String withDiv = "li(h2(a),p,div(span,span))";
        String withSitelinks = "li(h2(a),p,ul(li,li))";
        double threshold = LegacyBrowserSearchDefaults.create().analysis.resultBlockSimilarityThreshold;
        assertTrue(DomStructureClusterer.similarity(new DomStructureSignature(withSitelinks),
                new DomStructureSignature(plain)) >= threshold);
        assertTrue(DomStructureClusterer.similarity(new DomStructureSignature(withDiv),
                new DomStructureSignature(plain)) >= threshold);
        assertTrue("the variants are below the threshold against each other",
                DomStructureClusterer.similarity(new DomStructureSignature(withSitelinks),
                        new DomStructureSignature(withDiv)) < threshold);
        SerpDocuments serp = SerpDocuments.builder();
        serp.addResultColumn(new RenderedBox(300, 120, 680, 1460), SerpDocuments.WHITE,
                java.util.Arrays.asList("result", "result", "featured", "result", "result", "result"),
                java.util.Arrays.asList(plain, plain, withSitelinks, withDiv, plain, plain));

        SearchResultBlockDetector.Detection detection = detect(serp);

        assertEquals(detection.rejectionReasons.toString(), 6, detection.blocks.size());
        for (int i = 0; i < 6; i++) {
            assertEquals("Result " + i + " title", detection.blocks.get(i).title);
            assertEquals(i + 1, detection.blocks.get(i).rank);
        }
    }

    @Test
    public void aMemberLackingTheStructureEveryResultSharesIsSetAside() {
        // The counterpart, shaped like Bing's answer cards: similar enough to join the cluster, but its
        // heading link sits in a wrapper, so it lacks the li > h2 > a that every result has.
        String result = "li(h2(a),p)";
        String withSitelinks = "li(h2(a),p,ul(li,li))";
        String card = "li(div(h2(a),div,div),p)";
        SerpDocuments serp = SerpDocuments.builder();
        serp.addResultColumn(new RenderedBox(300, 120, 680, 1460), SerpDocuments.WHITE,
                java.util.Arrays.asList("answer", "result", "result", "result", "result", "result"),
                java.util.Arrays.asList(card, result, withSitelinks, result, result, result));

        SearchResultBlockDetector.Detection detection = detect(serp);

        assertEquals(detection.rejectionReasons.toString(), 5, detection.blocks.size());
        for (int i = 0; i < 5; i++) {
            assertEquals("Result " + (i + 1) + " title", detection.blocks.get(i).title);
        }
        assertTrue(detection.rejectionReasons.toString(),
                detection.rejectionReasons.toString().contains("li>h2>a"));
    }

    @Test
    public void aLargerClusterOfAdsDoesNotPushOutTheOrganicResults() {
        // Issue #22 review: six ads of one shape and five organic results of another. The clusters
        // compete with what is left after the ads are set aside — 0 against 5 — not with 6 against 5.
        String ad = "li(div(span,span,span),div(a,img,img))";
        String organic = "li(h2(a),p)";
        SerpDocuments serp = SerpDocuments.builder();
        serp.addResultColumn(new RenderedBox(300, 120, 680, 1600), SerpDocuments.WHITE,
                java.util.Arrays.asList("b_ad", "b_ad", "b_ad", "result", "b_ad", "result",
                        "b_ad", "result", "b_ad", "result", "result"),
                java.util.Arrays.asList(ad, ad, ad, organic, ad, organic, ad, organic, ad,
                        organic, organic));

        SearchResultBlockDetector.Detection detection = detect(serp);

        assertEquals(detection.rejectionReasons.toString(), 5, detection.blocks.size());
        int[] organicIndexes = {3, 5, 7, 9, 10};
        for (int i = 0; i < 5; i++) {
            assertEquals("Result " + organicIndexes[i] + " title", detection.blocks.get(i).title);
            assertEquals(i + 1, detection.blocks.get(i).rank);
        }
        assertTrue(detection.rejectionReasons.toString(),
                detection.rejectionReasons.toString().contains("set aside a second repeated"));
    }

    @Test
    public void snippetBelongsToItsOwnBlockNotTheNeighbor() {
        SerpDocuments serp = SerpDocuments.builder();
        serp.addResultColumn(3, new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE);
        SearchResultBlockDetector.Detection detection = detect(serp);
        for (int i = 0; i < 3; i++) {
            DetectedResultBlock block = detection.blocks.get(i);
            assertTrue("snippet of block " + i + " must describe result " + i + ": "
                    + block.snippet, block.snippet.contains("Snippet for result " + i));
            assertFalse("snippet must not repeat the title",
                    block.snippet.contains(block.title));
            for (int other = 0; other < 3; other++) {
                if (other != i) {
                    assertFalse("snippet of block " + i + " leaked from block " + other,
                            block.snippet.contains("Snippet for result " + other));
                }
            }
        }
    }

    @Test
    public void resolvedWrapperTargetIsTheNavigationCandidateRawStaysProvenance() {
        SerpDocuments serp = SerpDocuments.builder();
        String column = serp.addResultColumn(3,
                new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE);
        // Replace nothing — ADD a wrapper-style primary into a fourth block-like sibling is
        // complex; instead give block 1 an additional WRAPPED, heading-level link that wins.
        String block1 = SerpDocuments.blockId(column, 1);
        serp.addRawLink(block1, "https://engine.example/ck/a?u=a1aHR0cHM6Ly9kaXJlY3QuZXhhbXBsZS8",
                "https://direct.example/target", LinkRedirectResolution.RESOLVED,
                "Wrapped result title with more text", DomainClassification.EXTERNAL_DOMAIN, true,
                "Wrapped result title with more text Explains the wrapped target in detail.");

        SearchResultBlockDetector.Detection detection = detect(serp);
        DetectedResultBlock block = detection.blocks.get(1);
        // Both candidates qualify; the detector must navigate to a RESOLVED target either way and
        // never to the raw wrapper.
        assertFalse(block.primaryLink.resolvedTargetUrl.contains("/ck/"));
        assertTrue(block.primaryLink.resolvedTargetUrl.startsWith("https://"));
        for (DetectedResultBlock b : detection.blocks) {
            assertFalse("navigation candidates never carry the wrapper",
                    b.primaryLink.resolvedTargetUrl.contains("u=a1"));
        }
    }

    @Test
    public void unresolvedWrapperIsNeverPrimary() {
        SerpDocuments serp = SerpDocuments.builder();
        String column = serp.addResultColumn(3,
                new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE);
        // A fourth similar block whose ONLY link is an unresolvable wrapper.
        String block2 = SerpDocuments.blockId(column, 2);
        serp.addRawLink(block2, "https://engine.example/ck/a?u=garbage", "",
                LinkRedirectResolution.UNRESOLVED, "Broken wrapper result",
                DomainClassification.EXTERNAL_DOMAIN, true, "context");
        SearchResultBlockDetector.Detection detection = detect(serp);
        for (DetectedResultBlock block : detection.blocks) {
            assertFalse(block.primaryLink.rawHref.contains("garbage"));
            assertFalse(block.primaryLink.resolvedTargetUrl.isEmpty());
        }
    }

    @Test
    public void subdomainResultInASolidBlockIsNotDiscardedAsMenu() {
        SerpDocuments serp = SerpDocuments.builder();
        String column = serp.addResultColumn(3,
                new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE);
        RenderedPageDocument base = serp.build();
        // Rebuild block 1's primary as a SAME_REGISTRABLE_DOMAIN link (finance.engine.example on
        // an engine.example SERP): the detector must still accept it as primary.
        SerpDocuments serp2 = SerpDocuments.builder();
        String column2 = serp2.addResultColumn(3,
                new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE);
        String block = SerpDocuments.blockId(column2, 1);
        serp2.addRawLink(block, "https://finance.engine.example/quote/x",
                "https://finance.engine.example/quote/x", LinkRedirectResolution.NOT_A_REDIRECT,
                "Finance subdomain result title", DomainClassification.SAME_REGISTRABLE_DOMAIN,
                true, "Finance subdomain result title Quotes and financial data for x.");
        SearchResultBlockDetector.Detection detection = detect(serp2);
        assertEquals(3, detection.blocks.size());
        assertTrue(base.containers.size() > 0); // silence unused; base validated the fixture shape
    }

    @Test
    public void snippetlessBlockScoresClearlyBelowABlockWithExplanatoryText() {
        SerpDocuments withSnippets = SerpDocuments.builder();
        withSnippets.addResultColumn(3, new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE);
        SearchResultBlockDetector.Detection rich = detect(withSnippets);
        double richConfidence = rich.blocks.get(0).structuralConfidence;
        assertTrue(rich.blocks.get(0).snippet.length() > 0);

        // Same repeated structure, but the blocks carry NO explanatory text at all.
        SerpDocuments bare = SerpDocuments.builder();
        bare.addBareResultColumn(3, new RenderedBox(300, 120, 680, 560));
        SearchResultBlockDetector.Detection detection = detect(bare);
        double bareConfidence = detection.blocks.get(0).structuralConfidence;
        assertTrue(detection.blocks.get(0).snippet.isEmpty());
        assertTrue("a hit with explanatory text must score clearly above an isolated link: "
                        + richConfidence + " vs " + bareConfidence,
                richConfidence > bareConfidence + 0.2);
    }

    @Test
    public void onlyUnresolvedGotoHitsAreAllKeptAsBlocks() {
        // askai #41 (lazy resolution), same rule as the Google search over Serp4J: a /goto hit is a hit.
        SerpDocuments serp = SerpDocuments.builder();
        serp.addBareResultColumn(3, new RenderedBox(300, 120, 680, 560), new boolean[] {true, true, true});
        SearchResultBlockDetector.Detection detection = detect(serp);
        assertEquals("every /goto hit stays a block", 3, detection.blocks.size());
        for (DetectedResultBlock block : detection.blocks) {
            assertEquals(LinkRedirectResolution.UNRESOLVED, block.primaryLink.redirectResolutionStatus);
            assertTrue(block.primaryLink.rawHref.contains("/goto?url="));
        }
        assertEquals("reported for diagnostics", 3, detection.unresolvedRedirectHits.size());
        assertTrue(detection.rejectionReasons.toString().contains("kept as unverified candidate"));
    }

    @Test
    public void mixedDirectAndUnresolvedGotoHitsAreAllKeptInTheirOrder() {
        SerpDocuments serp = SerpDocuments.builder();
        serp.addBareResultColumn(4, new RenderedBox(300, 120, 680, 560),
                new boolean[] {false, true, false, true});
        SearchResultBlockDetector.Detection detection = detect(serp);
        assertEquals(4, detection.blocks.size());
        assertFalse(detection.blocks.get(0).primaryLink.rawHref.contains("/goto"));
        assertTrue(detection.blocks.get(1).primaryLink.rawHref.contains("/goto"));
        assertFalse(detection.blocks.get(2).primaryLink.rawHref.contains("/goto"));
        assertTrue(detection.blocks.get(3).primaryLink.rawHref.contains("/goto"));
        assertEquals("ranks stay dense", 4, detection.blocks.get(3).rank);
        assertEquals(2, detection.unresolvedRedirectHits.size());
        assertEquals("Result 1 title", detection.unresolvedRedirectHits.get(0).title);
    }
}
