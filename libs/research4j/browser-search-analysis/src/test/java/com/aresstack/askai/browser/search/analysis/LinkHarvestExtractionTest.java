package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.domain.DomainClassification;
import com.aresstack.askai.browser.render.RenderedBox;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.SearchPageAnalysisOutcome;
import com.aresstack.askai.browser.search.SearchPageAnalysisSettings;
import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.SearchResultExtractionResult;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The SERP-as-JSON safety net (the live 'hasenpfote' regression: Bing collapsed to ONE candidate, the
 * whole search ran on a single link): when the structured extraction understands too little of the
 * page, the EXTERNAL links are harvested as candidates — title, url and surrounding excerpt, exactly
 * the shape a SERP API's JSON delivers — and the mandatory reranker judges them. Engine-internal links
 * never qualify, duplicates collapse, a strong structured page stays untouched, and 0 disables it.
 */
public class LinkHarvestExtractionTest {

    /** A page the block detector cannot use (one lonely block) plus loose external links elsewhere. */
    private static RenderedPageDocument collapsedSerpWithLooseLinks() {
        SerpDocuments doc = SerpDocuments.builder();
        doc.addNavigationBar(6); // engine-internal links — must never be harvested
        doc.addResultColumn(1, new RenderedBox(160, 80, 700, 200), SerpDocuments.LIGHT_GRAY);
        String loose = doc.addPlainContainer("div", "loose", java.util.Collections.<String>emptyList(),
                java.util.Collections.<String>emptyList(), new RenderedBox(160, 320, 700, 400),
                600, 120, 4, 0, 4);
        doc.addLink(loose, "https://hasen.example.org/pfote", "Hasenpfote als Glücksbringer",
                DomainClassification.EXTERNAL_DOMAIN, false,
                "Die Hasenpfote gilt seit Jahrhunderten als Talisman.");
        doc.addLink(loose, "https://kultur.example.net/aberglaube", "Aberglaube und Tiersymbole",
                DomainClassification.EXTERNAL_DOMAIN, false,
                "Ein Überblick über Tiersymbole im Volksglauben.");
        doc.addLink(loose, "https://hasen.example.org/pfote", "Hasenpfote als Glücksbringer",
                DomainClassification.EXTERNAL_DOMAIN, false, "duplicate target");
        doc.addLink(loose, "https://engine.example/settings", "Einstellungen",
                DomainClassification.SAME_HOST, false, "engine-internal");
        return doc.build();
    }

    /**
     * SERP chrome never enters the harvest: Bing links microsoft.com legal/consent pages on EVERY
     * result page (Servicevertrag, Datenschutz, Nutzungsbedingungen) — they landed in the sources as
     * "results". The configured excluded domains (default: microsoft.com) cut them here, subdomains
     * included.
     */
    @Test
    public void engineChromeDomainsAreNeverHarvested() {
        SerpDocuments doc = SerpDocuments.builder();
        doc.addResultColumn(1, new RenderedBox(160, 80, 700, 200), SerpDocuments.LIGHT_GRAY);
        String loose = doc.addPlainContainer("div", "loose", java.util.Collections.<String>emptyList(),
                java.util.Collections.<String>emptyList(), new RenderedBox(160, 320, 700, 400),
                600, 120, 4, 0, 4);
        doc.addLink(loose, "https://hasen.example.org/pfote", "Hasenpfote als Glücksbringer",
                DomainClassification.EXTERNAL_DOMAIN, false, "echtes Ergebnis");
        doc.addLink(loose, "https://www.microsoft.com/de-de/servicesagreement/", "Servicevertrag",
                DomainClassification.EXTERNAL_DOMAIN, false, "Bing-Fußzeile");
        doc.addLink(loose, "https://go.microsoft.com/fwlink/?LinkId=521839", "Datenschutz",
                DomainClassification.EXTERNAL_DOMAIN, false, "Bing-Fußzeile");

        SearchResultExtractionResult result = new LegacySearchResultExtractor(
                LegacyBrowserSearchDefaults.create()).extract(doc.build());

        assertTrue("the real result survives",
                byUrl(result, "https://hasen.example.org/pfote") != null);
        for (SearchResultCandidate candidate : result.candidates) {
            assertFalse("engine-owner chrome must never become a candidate: "
                    + candidate.resolvedTargetUrl,
                    candidate.resolvedTargetUrl.contains("microsoft.com"));
        }
    }

    @Test
    public void aCollapsedSerpStillDeliversItsExternalLinksAsCandidates() {
        LegacySearchResultExtractor extractor =
                new LegacySearchResultExtractor(LegacyBrowserSearchDefaults.create());
        SearchResultExtractionResult result = extractor.extract(collapsedSerpWithLooseLinks());

        assertEquals("the page delivers — the reranker filters, not the block detector",
                SearchPageAnalysisOutcome.ORGANIC_RESULTS, result.outcome);
        assertTrue("harvest is named in the diagnostics: " + result.diagnostics,
                containsPart(result, "link harvest"));
        SearchResultCandidate pfote = byUrl(result, "https://hasen.example.org/pfote");
        assertEquals("Hasenpfote als Glücksbringer", pfote.title);
        assertEquals("the surrounding excerpt IS the snippet the reranker judges",
                "Die Hasenpfote gilt seit Jahrhunderten als Talisman.", pfote.snippet);
        assertTrue(byUrl(result, "https://kultur.example.net/aberglaube") != null);
        assertEquals("a duplicate target is carried once", 1, countUrl(result,
                "https://hasen.example.org/pfote"));
        for (SearchResultCandidate candidate : result.candidates) {
            assertFalse("engine-internal links are never candidates: " + candidate.resolvedTargetUrl,
                    candidate.resolvedTargetUrl.startsWith("https://engine.example/"));
        }
    }

    @Test
    public void aStructurallyStrongSerpIsNotAugmented() {
        LegacySearchResultExtractor extractor =
                new LegacySearchResultExtractor(LegacyBrowserSearchDefaults.create());
        SerpDocuments doc = SerpDocuments.builder();
        doc.addNavigationBar(6);
        doc.addResultColumn(6, new RenderedBox(160, 80, 700, 900), SerpDocuments.LIGHT_GRAY);
        SearchResultExtractionResult result = extractor.extract(doc.build());

        assertEquals(SearchPageAnalysisOutcome.ORGANIC_RESULTS, result.outcome);
        assertEquals("six structured blocks meet the minimum — nothing is harvested",
                6, result.candidates.size());
        assertFalse("no harvest diagnostic on a strong page: " + result.diagnostics,
                containsPart(result, "link harvest"));
    }

    @Test
    public void zeroDisablesTheHarvestAndKeepsTheHonestFailure() {
        SearchResultExtractionResult result = new LegacySearchResultExtractor(
                withHarvestDisabled(LegacyBrowserSearchDefaults.create()))
                .extract(collapsedSerpWithLooseLinks());
        assertTrue("without the harvest the old verdict stands (failed or a lone candidate): "
                        + result.outcome,
                result.outcome != SearchPageAnalysisOutcome.ORGANIC_RESULTS
                        || result.candidates.size() <= 1);
    }

    private static boolean containsPart(SearchResultExtractionResult result, String part) {
        for (String diagnostic : result.diagnostics) {
            if (diagnostic.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static SearchResultCandidate byUrl(SearchResultExtractionResult result, String url) {
        for (SearchResultCandidate candidate : result.candidates) {
            if (url.equals(candidate.resolvedTargetUrl)) {
                return candidate;
            }
        }
        throw new AssertionError("candidate missing: " + url + " in " + result.candidates.size()
                + " candidates; diagnostics=" + result.diagnostics);
    }

    private static int countUrl(SearchResultExtractionResult result, String url) {
        int count = 0;
        for (SearchResultCandidate candidate : result.candidates) {
            if (url.equals(candidate.resolvedTargetUrl)) {
                count++;
            }
        }
        return count;
    }

    private static LegacyBrowserSearchSettings withHarvestDisabled(LegacyBrowserSearchSettings base) {
        SearchPageAnalysisSettings a = base.analysis;
        SearchPageAnalysisSettings disabled = new SearchPageAnalysisSettings(a.noResultsTexts,
                a.maximumCandidateContainers, a.minimumContainerTextCharacters,
                a.minimumNonLinkTextCharacters, a.minimumRepeatedSiblingCount,
                a.minimumResultStructuralConfidence, a.maximumNavigationLinkDensity,
                a.internalLinkWeight, a.externalLinkWeight, a.sameHostPenalty,
                a.sameRegistrableDomainPenalty, a.subdomainPenalty, a.unknownDomainPenalty,
                a.repeatedBlockWeight, a.nonLinkTextWeight, a.titleLinkWeight, a.snippetPresenceWeight,
                a.headingLinkWeight, a.semanticMainWeight, a.navigationRolePenalty,
                a.resultBlockSimilarityThreshold, a.minimumDiscriminatingSignalFamilies,
                a.fullPageAreaRatio, a.textLengthSaturationCharacters, a.maximumContainerDomDepth,
                a.maximumCapturedContainers, a.maximumLinksPerContainer,
                a.maximumStructureSignatureDepth, 0, a.linkHarvestMaximumCandidates,
                a.linkHarvestExcludedDomains);
        return new LegacyBrowserSearchSettings(base.navigation, base.consent, base.captcha,
                base.readiness, disabled, base.visualAnalysis, base.extraction,
                base.aiLayoutResolver, base.reranker, base.diagnostics, base.layoutRepair);
    }
}
