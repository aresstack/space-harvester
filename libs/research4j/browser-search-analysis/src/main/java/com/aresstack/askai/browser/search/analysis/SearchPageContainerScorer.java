package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.RenderedContainerDescriptor;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.SearchPageAnalysisSettings;
import com.aresstack.askai.browser.search.SearchPageVisualAnalysisSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * Scores ONE container as a potential ORGANIC_RESULTS region by combining the independent signal
 * families. Every weight and threshold comes from the settings — the DOM-geometry and color
 * families use the {@link SearchPageVisualAnalysisSettings} weights (center probe, background
 * distances, separation), which equally govern the later screenshot-based stage. A family that has
 * nothing to say emits NO signal (neutral), never a zero-value guess: an everywhere-identical
 * background yields a neutral color family, not a boundary.
 */
public final class SearchPageContainerScorer {

    private final SearchPageAnalysisSettings analysis;
    private final SearchPageVisualAnalysisSettings visual;

    public SearchPageContainerScorer(SearchPageAnalysisSettings analysis,
                                     SearchPageVisualAnalysisSettings visual) {
        this.analysis = analysis;
        this.visual = visual;
    }

    public HeuristicScoreBreakdown score(RenderedContainerDescriptor c,
                                         RenderedPageDocument document) {
        List<HeuristicSignal> signals = new ArrayList<HeuristicSignal>();
        domSemantics(c, signals);
        geometry(c, document, signals);
        visualStyling(c, signals);
        linkStructure(c, signals);
        textStructure(c, signals);
        repeatedStructure(c, document, signals);
        return new HeuristicScoreBreakdown(c.containerId, signals);
    }

    private void domSemantics(RenderedContainerDescriptor c, List<HeuristicSignal> signals) {
        if (c.semanticFlags.contains("MAIN")) {
            signals.add(new HeuristicSignal(SignalFamily.DOM_SEMANTICS, "main",
                    analysis.semanticMainWeight, "main/role=main"));
        }
        if (c.semanticFlags.contains("NAV")) {
            signals.add(new HeuristicSignal(SignalFamily.DOM_SEMANTICS, "nav",
                    -analysis.navigationRolePenalty, "nav/role=navigation"));
        }
        if (c.semanticFlags.contains("FOOTER")) {
            signals.add(new HeuristicSignal(SignalFamily.DOM_SEMANTICS, "footer",
                    -analysis.navigationRolePenalty, "footer/contentinfo"));
        }
    }

    private void geometry(RenderedContainerDescriptor c, RenderedPageDocument document,
                          List<HeuristicSignal> signals) {
        if (!c.visible) {
            return; // invisible containers were filtered before scoring; stay silent
        }
        double probeX = document.viewport.viewportWidth
                * (visual.centerProbeXRatio - visual.centerProbeWidthRatio / 2);
        double probeY = document.viewport.viewportHeight
                * (visual.centerProbeYRatio - visual.centerProbeHeightRatio / 2);
        double probeW = document.viewport.viewportWidth * visual.centerProbeWidthRatio;
        double probeH = document.viewport.viewportHeight * visual.centerProbeHeightRatio;
        boolean intersectsProbe = c.boundingBox.x < probeX + probeW
                && c.boundingBox.x + c.boundingBox.width > probeX
                && c.boundingBox.y < probeY + probeH
                && c.boundingBox.y + c.boundingBox.height > probeY;
        if (intersectsProbe) {
            signals.add(new HeuristicSignal(SignalFamily.GEOMETRY, "centerProbe",
                    visual.centerIntersectionWeight, "intersects the center probe zone"));
        } else {
            signals.add(new HeuristicSignal(SignalFamily.GEOMETRY, "edgeRegion",
                    -visual.edgeRegionPenalty, "entirely outside the center probe zone"));
        }
        double centerDistance = (c.horizontalCenterDistance + c.verticalCenterDistance) / 2;
        if (centerDistance > 0) {
            signals.add(new HeuristicSignal(SignalFamily.GEOMETRY, "centerDistance",
                    -visual.centerDistanceWeight * Math.min(1, centerDistance),
                    "normalized distance " + round(centerDistance)));
        }
        double documentArea = Math.max(1.0,
                (double) document.viewport.documentWidth * document.viewport.documentHeight);
        double areaRatio = c.boundingBox.area() / documentArea;
        if (areaRatio >= analysis.fullPageAreaRatio) {
            // The center prior must not merge header+nav+footer into "the page": a near-full-page
            // container is penalized so real result columns win below it.
            signals.add(new HeuristicSignal(SignalFamily.GEOMETRY, "fullPage",
                    -visual.fullPageContainerPenalty,
                    "covers " + round(areaRatio) + " of the document"));
        }
    }

    private void visualStyling(RenderedContainerDescriptor c, List<HeuristicSignal> signals) {
        // Distinct background against the SURROUNDING page — neutral when everything is the same.
        if (c.backgroundDistanceToParent >= visual.minimumDistinctBackgroundDistance) {
            signals.add(new HeuristicSignal(SignalFamily.VISUAL_STYLING, "distinctBackground",
                    visual.distinctBackgroundWeight,
                    "background distance to parent " + round(c.backgroundDistanceToParent)));
        }
        if (!c.borderSummary.isEmpty()) {
            signals.add(new HeuristicSignal(SignalFamily.VISUAL_STYLING, "border",
                    visual.borderSeparationWeight, c.borderSummary));
        }
        if (!c.boxShadow.isEmpty()) {
            signals.add(new HeuristicSignal(SignalFamily.VISUAL_STYLING, "shadow",
                    visual.shadowSeparationWeight, "box shadow present"));
        }
        if (c.padding > 0 && c.margin > 0) {
            signals.add(new HeuristicSignal(SignalFamily.VISUAL_STYLING, "spacing",
                    visual.spacingSeparationWeight,
                    "padding " + round(c.padding) + "px, margin " + round(c.margin) + "px"));
        }
    }

    private void linkStructure(RenderedContainerDescriptor c, List<HeuristicSignal> signals) {
        if (c.linkCount == 0) {
            return; // nothing to judge — neutral, a result region needs links anyway
        }
        double external = (double) c.externalDomainLinkCount / c.linkCount;
        if (external > 0) {
            signals.add(new HeuristicSignal(SignalFamily.LINK_STRUCTURE, "externalTargets",
                    analysis.externalLinkWeight * external,
                    c.externalDomainLinkCount + "/" + c.linkCount + " external"));
        }
        penaltyRatio(signals, "sameHost", c.sameHostLinkCount, c.linkCount,
                analysis.sameHostPenalty);
        penaltyRatio(signals, "subdomain", c.subdomainLinkCount, c.linkCount,
                analysis.subdomainPenalty);
        penaltyRatio(signals, "sameRegistrable", c.sameRegistrableDomainLinkCount, c.linkCount,
                analysis.sameRegistrableDomainPenalty);
        penaltyRatio(signals, "actionLinks", c.javascriptOrActionLinkCount, c.linkCount,
                analysis.unknownDomainPenalty);
        if (c.totalTextLength > 0) {
            double linkDensity = (double) c.linkTextLength / c.totalTextLength;
            if (linkDensity > analysis.maximumNavigationLinkDensity) {
                signals.add(new HeuristicSignal(SignalFamily.LINK_STRUCTURE, "linkDensity",
                        -analysis.navigationRolePenalty,
                        "link-text density " + round(linkDensity) + " exceeds "
                                + analysis.maximumNavigationLinkDensity));
            }
        }
    }

    private void penaltyRatio(List<HeuristicSignal> signals, String name, int count, int total,
                              double penalty) {
        if (count > 0) {
            double ratio = (double) count / total;
            signals.add(new HeuristicSignal(SignalFamily.LINK_STRUCTURE, name, -penalty * ratio,
                    count + "/" + total));
        }
    }

    private void textStructure(RenderedContainerDescriptor c, List<HeuristicSignal> signals) {
        if (c.nonLinkTextLength >= analysis.minimumNonLinkTextCharacters
                && c.nonLinkTextLength > 0) {
            double saturated = Math.min(1.0,
                    (double) c.nonLinkTextLength / analysis.textLengthSaturationCharacters);
            signals.add(new HeuristicSignal(SignalFamily.TEXT_STRUCTURE, "nonLinkText",
                    analysis.nonLinkTextWeight * saturated,
                    c.nonLinkTextLength + " non-link chars"));
        }
        if (c.headingCount > 0) {
            signals.add(new HeuristicSignal(SignalFamily.TEXT_STRUCTURE, "headings",
                    analysis.titleLinkWeight * Math.min(1.0,
                            (double) c.headingCount / analysis.minimumRepeatedSiblingCount),
                    c.headingCount + " headings"));
        }
        if (c.paragraphCount > 0) {
            signals.add(new HeuristicSignal(SignalFamily.TEXT_STRUCTURE, "paragraphs",
                    analysis.snippetPresenceWeight * Math.min(1.0,
                            (double) c.paragraphCount / analysis.minimumRepeatedSiblingCount),
                    c.paragraphCount + " paragraphs"));
        }
    }

    private void repeatedStructure(RenderedContainerDescriptor c, RenderedPageDocument document,
                                   List<HeuristicSignal> signals) {
        int largestGroup = 0;
        for (String childId : c.childContainerIds) {
            RenderedContainerDescriptor child = document.container(childId);
            if (child != null) {
                largestGroup = Math.max(largestGroup, child.similarSiblingCount + 1);
            }
        }
        if (largestGroup >= analysis.minimumRepeatedSiblingCount) {
            signals.add(new HeuristicSignal(SignalFamily.REPEATED_STRUCTURE, "repeatedBlocks",
                    analysis.repeatedBlockWeight,
                    largestGroup + " structurally similar child blocks"));
        }
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
