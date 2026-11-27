package com.aresstack.askai.research.runtime.loop;

import com.aresstack.askai.browser.domain.DomainClassification;
import com.aresstack.askai.browser.render.DomStructureSignature;
import com.aresstack.askai.browser.render.LinkRedirectResolution;
import com.aresstack.askai.browser.render.RenderedBox;
import com.aresstack.askai.browser.render.RenderedColor;
import com.aresstack.askai.browser.render.RenderedContainerDescriptor;
import com.aresstack.askai.browser.render.RenderedLinkDescriptor;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.render.RenderedPageFingerprint;
import com.aresstack.askai.browser.render.RenderedPageViewport;
import com.aresstack.askai.browser.search.AiLayoutResolverSettings;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.SearchPageAnalysisSettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Self-contained fixtures for the cross-process repair integration test: a synthetic navigation +
 * result-column SERP (reproducing the proven browser-search-analysis test shape), and settings
 * variants that force LOW_CONFIDENCE (so the AI repair path is exercised) or keep the default
 * HIGH_CONFIDENCE, both with the AI resolver enabled.
 */
final class RepairBridgeFixtures {

    static final RenderedColor WHITE = new RenderedColor(255, 255, 255, 1);

    private RepairBridgeFixtures() {
    }

    /** A nav bar (container-0002) + a 3-block result column (container-0003, blocks 0004..0006). */
    static RenderedPageDocument navPlusColumn() {
        List<RenderedContainerDescriptor> containers = new ArrayList<RenderedContainerDescriptor>();
        List<RenderedLinkDescriptor> links = new ArrayList<RenderedLinkDescriptor>();
        int[] linkSeq = {0};

        // body root
        containers.add(RenderedContainerDescriptor.builder("container-0001")
                .hierarchy("", Arrays.asList("container-0002", "container-0003"), 0, 0)
                .semantics("body", "", Collections.<String>emptyList(), "", "",
                        Collections.<String>emptyList())
                .text("page", 40, 0, 40, 0, 0)
                .links(0, 0, 0, 0, 0, 0)
                .geometry(true, new RenderedBox(0, 0, 1280, 2000), 0.4, true, 0, 0)
                .colors(WHITE, WHITE, 0, 0)
                .separation("", 0, "", 0, 0)
                .structure(new DomStructureSignature("body"), 0)
                .build());

        // navigation bar
        int navLinks = 9;
        containers.add(RenderedContainerDescriptor.builder("container-0002")
                .hierarchy("container-0001", Collections.<String>emptyList(), 0, 1)
                .semantics("nav", "top-nav", Collections.<String>emptyList(), "navigation", "",
                        Arrays.asList("NAV"))
                .text("Images Videos Shopping News Maps", navLinks * 8, navLinks * 8, 0, 0, 0)
                .links(navLinks, navLinks, 0, 0, 0, 0)
                .geometry(true, new RenderedBox(0, 0, 1280, 56), 1.0, false, 0.0, 0.46)
                .colors(WHITE, WHITE, 0, 0)
                .separation("", 0, "", 4, 0)
                .structure(new DomStructureSignature("nav(ul(li,li,li))"), 0)
                .build());
        for (int i = 0; i < navLinks; i++) {
            links.add(link(linkSeq, "container-0002", "https://engine.example/vertical" + i,
                    "Videos", DomainClassification.SAME_HOST, false, ""));
        }

        // result column
        RenderedBox columnBox = new RenderedBox(300, 120, 680, 560);
        List<String> blockIds = Arrays.asList("container-0004", "container-0005", "container-0006");
        containers.add(RenderedContainerDescriptor.builder("container-0003")
                .hierarchy("container-0001", blockIds, 1, 1)
                .semantics("main", "results", Collections.<String>emptyList(), "main", "",
                        Arrays.asList("MAIN"))
                .text("results column", 3 * 190, 3 * 22, 3 * 168, 3, 3)
                .links(3, 0, 0, 0, 3, 0)
                .geometry(true, columnBox, 1.0, columnBox.contains(640, 400), 0.1, 0.1)
                .colors(WHITE, WHITE, 0, 0)
                .separation("", 0, "", 12, 8)
                .structure(new DomStructureSignature("main(li,li,li)"), 0)
                .build());
        for (int i = 0; i < 3; i++) {
            String blockId = blockIds.get(i);
            containers.add(RenderedContainerDescriptor.builder(blockId)
                    .hierarchy("container-0003", Collections.<String>emptyList(), i, 2)
                    .semantics("li", "", Arrays.asList("result"), "", "",
                            Collections.<String>emptyList())
                    .text("Result " + i + " title Explanatory snippet text describing the target "
                            + "page in one or two sentences.", 190, 22, 168, 1, 1)
                    .links(1, 0, 0, 0, 1, 0)
                    .geometry(true, new RenderedBox(columnBox.x + 8, columnBox.y + 8 + i * 140,
                            columnBox.width - 16, 120), 1.0, false, 0.1, 0.1)
                    .colors(RenderedColor.TRANSPARENT, WHITE, 0, 0)
                    .separation("", 0, "", 4, 8)
                    .structure(new DomStructureSignature("li(h2(a),p)"), 2)
                    .build());
            links.add(link(linkSeq, blockId, "https://site" + i + ".example.org/page",
                    "Result " + i + " title", DomainClassification.EXTERNAL_DOMAIN, true,
                    "Result " + i + " title Snippet for result " + i
                            + " describing exactly this target page."));
        }

        return new RenderedPageDocument("snap-1-itest", 1L, "https://engine.example/find?q=x",
                "SERP", new RenderedPageViewport(1280, 800, 1280, 2000),
                new RenderedPageFingerprint("fp-itest"), Arrays.asList("container-0001"), containers,
                links, false, Collections.<String>emptyList());
    }

    private static RenderedLinkDescriptor link(int[] seq, String containerId, String url, String text,
                                               DomainClassification classification,
                                               boolean insideHeading, String surrounding) {
        return new RenderedLinkDescriptor("link-" + String.format("%04d", ++seq[0]), containerId, url,
                url, LinkRedirectResolution.NOT_A_REDIRECT, text, surrounding,
                insideHeading ? text : "", "", classification, true, new RenderedBox(0, 0, 300, 20),
                insideHeading);
    }

    /** Default settings but with the AI resolver ENABLED. */
    static LegacyBrowserSearchSettings highConfidenceAiEnabled() {
        return withAiEnabled(LegacyBrowserSearchDefaults.create());
    }

    /** Settings that force LOW_CONFIDENCE (impossible discriminating-family demand) with AI enabled. */
    static LegacyBrowserSearchSettings lowConfidenceAiEnabled() {
        return lowConfidenceAiEnabled(true);
    }

    /** The same, with the link-harvest fallback switched off: a page with nothing to fall back to. */
    static LegacyBrowserSearchSettings lowConfidenceAiEnabledWithoutHarvest() {
        return lowConfidenceAiEnabled(false);
    }

    private static LegacyBrowserSearchSettings lowConfidenceAiEnabled(boolean harvest) {
        LegacyBrowserSearchSettings base = withAiEnabled(LegacyBrowserSearchDefaults.create());
        SearchPageAnalysisSettings a = base.analysis;
        SearchPageAnalysisSettings forced = new SearchPageAnalysisSettings(a.noResultsTexts,
                a.maximumCandidateContainers, a.minimumContainerTextCharacters,
                a.minimumNonLinkTextCharacters, a.minimumRepeatedSiblingCount,
                a.minimumResultStructuralConfidence, a.maximumNavigationLinkDensity,
                a.internalLinkWeight, a.externalLinkWeight, a.sameHostPenalty,
                a.sameRegistrableDomainPenalty, a.subdomainPenalty, a.unknownDomainPenalty,
                a.repeatedBlockWeight, a.nonLinkTextWeight, a.titleLinkWeight, a.snippetPresenceWeight,
                a.headingLinkWeight, a.semanticMainWeight, a.navigationRolePenalty,
                a.resultBlockSimilarityThreshold, 99, a.fullPageAreaRatio,
                a.textLengthSaturationCharacters, a.maximumContainerDomDepth,
                a.maximumCapturedContainers, a.maximumLinksPerContainer,
                a.maximumStructureSignatureDepth,
                harvest ? a.linkHarvestMinimumStructuredCandidates : 0,
                a.linkHarvestMaximumCandidates, a.linkHarvestExcludedDomains);
        return new LegacyBrowserSearchSettings(base.navigation, base.consent, base.captcha,
                base.readiness, forced, base.visualAnalysis, base.extraction, base.aiLayoutResolver,
                base.reranker, base.diagnostics, base.layoutRepair);
    }

    private static LegacyBrowserSearchSettings withAiEnabled(LegacyBrowserSearchSettings base) {
        AiLayoutResolverSettings d = base.aiLayoutResolver;
        AiLayoutResolverSettings ai = new AiLayoutResolverSettings(true, "profile-itest",
                d.reasoningEffort, d.temperature, d.maximumOutputTokens, d.systemPromptTemplate,
                d.userPromptTemplate, d.retryPolicy);
        return new LegacyBrowserSearchSettings(base.navigation, base.consent, base.captcha,
                base.readiness, base.analysis, base.visualAnalysis, base.extraction, ai,
                base.reranker, base.diagnostics, base.layoutRepair);
    }
}
