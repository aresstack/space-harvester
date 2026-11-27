package com.aresstack.askai.browser.search.analysis;

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Hand-built rendered SERP documents for the mechanical-analysis tests: a navigation bar of short
 * same-host links, a results column of structurally similar blocks (heading link to an external
 * target plus explanatory snippet text), optional footer/ads/auxiliary modules — all WITHOUT any
 * real search engine.
 */
final class SerpDocuments {

    static final RenderedColor WHITE = new RenderedColor(255, 255, 255, 1);
    static final RenderedColor LIGHT_GRAY = new RenderedColor(246, 246, 246, 1);

    private final List<RenderedContainerDescriptor> containers =
            new ArrayList<RenderedContainerDescriptor>();
    private final List<RenderedLinkDescriptor> links = new ArrayList<RenderedLinkDescriptor>();
    private final List<String> bodyChildIds = new ArrayList<String>();
    /** Starts at 1: container-0001 is always the body root added in {@link #build()}. */
    private int containerSeq = 1;
    private int linkSeq;

    static SerpDocuments builder() {
        return new SerpDocuments();
    }

    String nextContainerId() {
        return "container-" + String.format("%04d", ++containerSeq);
    }

    /** A top navigation bar: many short same-host links, nav semantics, no explanatory text. */
    String addNavigationBar(int linkCount) {
        String id = nextContainerId();
        bodyChildIds.add(id);
        int text = linkCount * 8;
        containers.add(RenderedContainerDescriptor.builder(id)
                .hierarchy("container-0001", Collections.<String>emptyList(),
                        bodyChildIds.size() - 1, 1)
                .semantics("nav", "top-nav", Collections.<String>emptyList(), "navigation", "",
                        Arrays.asList("NAV"))
                .text("Images Videos Shopping News Maps", text, text, 0, 0, 0)
                .links(linkCount, linkCount, 0, 0, 0, 0)
                .geometry(true, new RenderedBox(0, 0, 1280, 56), 1.0, false, 0.0, 0.46)
                .colors(WHITE, WHITE, 0, 0)
                .separation("", 0, "", 4, 0)
                .structure(new DomStructureSignature("nav(ul(li,li,li))"), 0)
                .build());
        for (int i = 0; i < linkCount; i++) {
            addLink(id, "https://engine.example/vertical" + i, "Videos",
                    DomainClassification.SAME_HOST, false, "");
        }
        return id;
    }

    /**
     * The organic result column: {@code blocks} structurally similar child blocks, each with one
     * heading link to an external target and explanatory snippet text.
     */
    String addResultColumn(int blocks, RenderedBox columnBox, RenderedColor background) {
        List<String> classes = new ArrayList<String>();
        for (int i = 0; i < blocks; i++) {
            classes.add("result");
        }
        return addResultColumn(columnBox, background, classes);
    }

    /** As {@link #addResultColumn(int, RenderedBox, RenderedColor)}, one block per class token given. */
    String addResultColumn(RenderedBox columnBox, RenderedColor background,
                           List<String> blockClasses) {
        return addResultColumn(columnBox, background, blockClasses,
                Collections.nCopies(blockClasses.size(), "li(h2(a),p)"));
    }

    /** One block per class token and structure signature given. */
    String addResultColumn(RenderedBox columnBox, RenderedColor background,
                           List<String> blockClasses, List<String> blockSignatures) {
        int blocks = blockClasses.size();
        String columnId = nextContainerId();
        bodyChildIds.add(columnId);
        List<String> blockIds = new ArrayList<String>();
        List<RenderedContainerDescriptor> blockDescriptors =
                new ArrayList<RenderedContainerDescriptor>();
        for (int i = 0; i < blocks; i++) {
            String blockId = nextContainerId();
            blockIds.add(blockId);
            blockDescriptors.add(RenderedContainerDescriptor.builder(blockId)
                    .hierarchy(columnId, Collections.<String>emptyList(), i, 2)
                    .semantics("li", "", Arrays.asList(blockClasses.get(i)), "", "",
                            Collections.<String>emptyList())
                    .text("Result " + i + " title Explanatory snippet text describing the target "
                                    + "page in one or two sentences.",
                            190, 22, 168, 1, 1)
                    .links(1, 0, 0, 0, 1, 0)
                    .geometry(true, new RenderedBox(columnBox.x + 8,
                                    columnBox.y + 8 + i * 140, columnBox.width - 16, 120),
                            1.0, false, 0.1, 0.1)
                    .colors(RenderedColor.TRANSPARENT, background, 0, 0)
                    .separation("", 0, "", 4, 8)
                    .structure(new DomStructureSignature(blockSignatures.get(i)), blocks - 1)
                    .build());
            addLink(blockId, "https://site" + i + ".example.org/page", "Result " + i + " title",
                    DomainClassification.EXTERNAL_DOMAIN, true,
                    "Result " + i + " title Snippet for result " + i
                            + " describing exactly this target page.");
        }
        containers.add(RenderedContainerDescriptor.builder(columnId)
                .hierarchy("container-0001", blockIds, bodyChildIds.size() - 1, 1)
                .semantics("main", "results", Collections.<String>emptyList(), "main", "",
                        Arrays.asList("MAIN"))
                .text("results column", blocks * 190, blocks * 22, blocks * 168, blocks, blocks)
                .links(blocks, 0, 0, 0, blocks, 0)
                .geometry(true, columnBox, 1.0,
                        columnBox.contains(640, 400), 0.1, 0.1)
                .colors(background, background,
                        background.distanceTo(WHITE), background.distanceTo(WHITE))
                .separation("", 0, "", 12, 8)
                .structure(new DomStructureSignature("main(li,li,li)"), 0)
                .build());
        containers.addAll(blockDescriptors);
        return columnId;
    }

    /** A result column whose blocks carry ONLY a title link — no explanatory text anywhere. */
    String addBareResultColumn(int blocks, RenderedBox columnBox) {
        return addBareResultColumn(blocks, columnBox, new boolean[blocks]);
    }

    /** Like {@link #addBareResultColumn(int, RenderedBox)}; block i links to an unresolved Google /goto if set. */
    String addBareResultColumn(int blocks, RenderedBox columnBox, boolean[] unresolvedGoto) {
        return addBareResultColumn(blocks, columnBox, unresolvedGoto, new boolean[blocks]);
    }

    /** Block i links to a Baidu /link wrapper the capture discarded (no target on the page) if set. */
    String addBareResultColumn(int blocks, RenderedBox columnBox, boolean[] unresolvedGoto,
                               boolean[] discardedBaidu) {
        String columnId = nextContainerId();
        bodyChildIds.add(columnId);
        List<String> blockIds = new ArrayList<String>();
        List<RenderedContainerDescriptor> blockDescriptors =
                new ArrayList<RenderedContainerDescriptor>();
        for (int i = 0; i < blocks; i++) {
            String blockId = nextContainerId();
            blockIds.add(blockId);
            blockDescriptors.add(RenderedContainerDescriptor.builder(blockId)
                    .hierarchy(columnId, Collections.<String>emptyList(), i, 2)
                    .semantics("li", "", Arrays.asList("result"), "", "",
                            Collections.<String>emptyList())
                    .text("Result " + i + " title", 40, 22, 18, 1, 0)
                    .links(1, 0, 0, 0, 1, 0)
                    .geometry(true, new RenderedBox(columnBox.x + 8,
                                    columnBox.y + 8 + i * 60, columnBox.width - 16, 48),
                            1.0, false, 0.1, 0.1)
                    .colors(RenderedColor.TRANSPARENT, WHITE, 0, 0)
                    .separation("", 0, "", 4, 8)
                    .structure(new DomStructureSignature("li(h2(a))"), blocks - 1)
                    .build());
            if (discardedBaidu[i]) {
                addRawLink(blockId, "https://www.baidu.com/link?url=Token" + i, "",
                        LinkRedirectResolution.DISCARDED, "Result " + i + " title",
                        DomainClassification.EXTERNAL_DOMAIN, true, "");
            } else if (unresolvedGoto[i]) {
                addRawLink(blockId, "https://www.google.com/goto?url=CAES" + i, "",
                        LinkRedirectResolution.UNRESOLVED, "Result " + i + " title",
                        DomainClassification.EXTERNAL_DOMAIN, true, "");
            } else {
                addRawLink(blockId, "https://bare" + i + ".example.org/",
                        "https://bare" + i + ".example.org/", LinkRedirectResolution.NOT_A_REDIRECT,
                        "Result " + i + " title", DomainClassification.EXTERNAL_DOMAIN, true, "");
            }
        }
        containers.add(RenderedContainerDescriptor.builder(columnId)
                .hierarchy("container-0001", blockIds, bodyChildIds.size() - 1, 1)
                .semantics("main", "results", Collections.<String>emptyList(), "main", "",
                        Arrays.asList("MAIN"))
                .text("bare results", blocks * 40, blocks * 22, blocks * 18, blocks, 0)
                .links(blocks, 0, 0, 0, blocks, 0)
                .geometry(true, columnBox, 1.0, columnBox.contains(640, 400), 0.1, 0.1)
                .colors(WHITE, WHITE, 0, 0)
                .separation("", 0, "", 12, 8)
                .structure(new DomStructureSignature("main(li,li,li)"), 0)
                .build());
        containers.addAll(blockDescriptors);
        return columnId;
    }

    /** A generic container with configurable stats (auxiliary modules, panels, plain divs). */
    String addPlainContainer(String tag, String elementId, List<String> classes,
                             List<String> semanticFlags, RenderedBox box, int totalText,
                             int linkText, int linkCount, int sameHostLinks, int externalLinks) {
        String id = nextContainerId();
        bodyChildIds.add(id);
        containers.add(RenderedContainerDescriptor.builder(id)
                .hierarchy("container-0001", Collections.<String>emptyList(),
                        bodyChildIds.size() - 1, 1)
                .semantics(tag, elementId, classes, "", "", semanticFlags)
                .text("generic container text", totalText, linkText,
                        Math.max(0, totalText - linkText), 0, 0)
                .links(linkCount, sameHostLinks, 0, 0, externalLinks,
                        linkCount - sameHostLinks - externalLinks)
                .geometry(true, box, 1.0, box.contains(640, 400),
                        Math.abs(box.centerX() - 640) / 1280, Math.abs(box.centerY() - 400) / 800)
                .colors(WHITE, WHITE, 0, 0)
                .separation("", 0, "", 4, 4)
                .structure(new DomStructureSignature(tag + "(div)"), 0)
                .build());
        return id;
    }

    void addLink(String containerId, String url, String text,
                 DomainClassification classification, boolean insideHeading, String surrounding) {
        addRawLink(containerId, url, url, LinkRedirectResolution.NOT_A_REDIRECT, text,
                classification, insideHeading, surrounding);
    }

    /** Full control (wrapper links, unresolved wrappers, displayed domains). */
    void addRawLink(String containerId, String rawHref, String resolvedTarget,
                    LinkRedirectResolution status, String text,
                    DomainClassification classification, boolean insideHeading,
                    String surrounding) {
        links.add(new RenderedLinkDescriptor("link-" + String.format("%04d", ++linkSeq),
                containerId, rawHref, resolvedTarget, status, text, surrounding,
                insideHeading ? text : "", "", classification, true,
                new RenderedBox(0, 0, 300, 20), insideHeading));
    }

    /** The id of the {@code index}-th result BLOCK of the most recent result column. */
    static String blockId(String columnId, int index) {
        int column = Integer.parseInt(columnId.substring("container-".length()));
        return "container-" + String.format("%04d", column + 1 + index);
    }

    /**
     * Let the body root carry the aggregated statistics of everything below it — what the real
     * capture reports for {@code <body>} (text, headings, external links of the whole page).
     */
    SerpDocuments withAggregatingBody() {
        aggregatingBody = true;
        return this;
    }

    private boolean aggregatingBody;

    RenderedPageDocument build() {
        int text = 40;
        int linkText = 0;
        int headings = 0;
        int paragraphs = 0;
        int linkCount = 0;
        int sameHost = 0;
        int external = 0;
        if (aggregatingBody) {
            for (RenderedContainerDescriptor c : containers) {
                if ("container-0001".equals(c.parentContainerId)) {
                    text += c.totalTextLength;
                    linkText += c.linkTextLength;
                    headings += c.headingCount;
                    paragraphs += c.paragraphCount;
                    linkCount += c.linkCount;
                    sameHost += c.sameHostLinkCount;
                    external += c.externalDomainLinkCount;
                }
            }
        }
        List<RenderedContainerDescriptor> all = new ArrayList<RenderedContainerDescriptor>();
        all.add(RenderedContainerDescriptor.builder("container-0001")
                .hierarchy("", bodyChildIds, 0, 0)
                .semantics("body", "", Collections.<String>emptyList(), "", "",
                        Collections.<String>emptyList())
                .text("page", text, linkText, text - linkText, headings, paragraphs)
                .links(linkCount, sameHost, 0, 0, external, linkCount - sameHost - external)
                .geometry(true, new RenderedBox(0, 0, 1280, 2000), 0.4, true, 0, 0)
                .colors(WHITE, WHITE, 0, 0)
                .separation("", 0, "", 0, 0)
                .structure(new DomStructureSignature("body"), 0)
                .build());
        all.addAll(containers);
        return new RenderedPageDocument("snap-1-test", 1L, "https://engine.example/find?q=x",
                "SERP", new RenderedPageViewport(1280, 800, 1280, 2000),
                new RenderedPageFingerprint("f"), Arrays.asList("container-0001"), all, links,
                false, Collections.<String>emptyList());
    }
}
