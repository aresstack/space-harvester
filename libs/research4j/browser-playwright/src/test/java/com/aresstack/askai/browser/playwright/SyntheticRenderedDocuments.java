package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.domain.DomainClassification;
import com.aresstack.askai.browser.domain.DomainIdentity;
import com.aresstack.askai.browser.domain.DomainKeyResolver;
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
 * Test-only stand-in for the real browser capture: turns a {@link PlaywrightPageState} fixture into
 * a structurally VALID rendered SERP — every titled anchor whose (statically resolved) target lies
 * OUTSIDE the engine's domain family becomes one repeated result block (title link + synthetic
 * snippet), engine-internal anchors form a navigation bar. Fixtures therefore express the modern
 * contract: a page needs at least the configured number of similar result blocks to count as a
 * SERP — a single naked link is no longer a "result page".
 */
final class SyntheticRenderedDocuments {

    private SyntheticRenderedDocuments() {
    }

    static RenderedPageDocument fromState(PlaywrightPageState state, DomainKeyResolver domainKeys,
                                          long generation) {
        DomainIdentity pageIdentity = domainKeys.resolve(state.url);
        List<RenderedContainerDescriptor> containers =
                new ArrayList<RenderedContainerDescriptor>();
        List<RenderedLinkDescriptor> links = new ArrayList<RenderedLinkDescriptor>();
        List<String> navLinkTexts = new ArrayList<String>();
        List<Object[]> resultAnchors = new ArrayList<Object[]>(); // [text, rawHref, target, cls]

        for (PlaywrightPageState.Anchor anchor : state.anchors) {
            if (anchor.text.trim().isEmpty() || !anchor.href.startsWith("http")) {
                continue;
            }
            SearchRedirectResolver.Resolution resolution =
                    SearchRedirectResolver.resolve(anchor.href);
            if (resolution.getStatus() == SearchRedirectResolver.Status.UNRESOLVED) {
                continue; // no navigation target — the real pipeline discards these too
            }
            String target = resolution.getStatus() == SearchRedirectResolver.Status.RESOLVED
                    ? resolution.getTargetUrl() : anchor.href;
            DomainClassification classification =
                    DomainClassification.classify(domainKeys.resolve(target), pageIdentity);
            if (classification == DomainClassification.EXTERNAL_DOMAIN) {
                resultAnchors.add(new Object[]{anchor.text.trim(), anchor.href, target,
                        classification});
            } else {
                navLinkTexts.add(anchor.text.trim());
                links.add(new RenderedLinkDescriptor("link-nav-" + navLinkTexts.size(),
                        "container-0002", anchor.href, target,
                        LinkRedirectResolution.NOT_A_REDIRECT, anchor.text.trim(), "", "", "",
                        classification, true, new RenderedBox(0, 0, 80, 20), false));
            }
        }

        int linkSeq = 0;
        List<String> blockIds = new ArrayList<String>();
        List<RenderedContainerDescriptor> blocks = new ArrayList<RenderedContainerDescriptor>();
        for (int i = 0; i < resultAnchors.size(); i++) {
            Object[] anchor = resultAnchors.get(i);
            String blockId = "container-" + String.format("%04d", 4 + i);
            blockIds.add(blockId);
            String title = (String) anchor[0];
            String blockText = title + " Synthetic snippet describing " + title + " in detail.";
            blocks.add(RenderedContainerDescriptor.builder(blockId)
                    .hierarchy("container-0003", Collections.<String>emptyList(), i, 2)
                    .semantics("li", "", Arrays.asList("result"), "", "",
                            Collections.<String>emptyList())
                    .text(blockText, blockText.length(), title.length(),
                            blockText.length() - title.length(), 1, 1)
                    .links(1, 0, 0, 0, 1, 0)
                    .geometry(true, new RenderedBox(308, 128 + i * 140, 664, 120), 1.0, false,
                            0.1, 0.1)
                    .colors(RenderedColor.TRANSPARENT, white(), 0, 0)
                    .separation("", 0, "", 4, 8)
                    .structure(new DomStructureSignature("li(h2(a),p)"),
                            resultAnchors.size() - 1)
                    .build());
            links.add(new RenderedLinkDescriptor("link-" + String.format("%04d", ++linkSeq),
                    blockId, (String) anchor[1], (String) anchor[2],
                    ((String) anchor[1]).equals(anchor[2]) ? LinkRedirectResolution.NOT_A_REDIRECT
                            : LinkRedirectResolution.RESOLVED,
                    title, blockText, title, "", (DomainClassification) anchor[3], true,
                    new RenderedBox(308, 128 + i * 140, 400, 20), true));
        }

        int columnText = 0;
        for (RenderedContainerDescriptor block : blocks) {
            columnText += block.totalTextLength;
        }
        containers.add(RenderedContainerDescriptor.builder("container-0001")
                .hierarchy("", Arrays.asList("container-0002", "container-0003"), 0, 0)
                .semantics("body", "", Collections.<String>emptyList(), "", "",
                        Collections.<String>emptyList())
                .text(state.text, Math.max(40, state.text.length()), 0,
                        Math.max(40, state.text.length()), 0, 0)
                .links(0, 0, 0, 0, 0, 0)
                .geometry(true, new RenderedBox(0, 0, 1280, 2000), 0.4, true, 0, 0)
                .colors(white(), white(), 0, 0)
                .separation("", 0, "", 0, 0)
                .structure(new DomStructureSignature("body"), 0)
                .build());
        containers.add(RenderedContainerDescriptor.builder("container-0002")
                .hierarchy("container-0001", Collections.<String>emptyList(), 0, 1)
                .semantics("nav", "top-nav", Collections.<String>emptyList(), "navigation", "",
                        Arrays.asList("NAV"))
                .text(join(navLinkTexts), navLinkTexts.size() * 10, navLinkTexts.size() * 10, 0,
                        0, 0)
                .links(navLinkTexts.size(), navLinkTexts.size(), 0, 0, 0, 0)
                .geometry(true, new RenderedBox(0, 0, 1280, 56), 1.0, false, 0.0, 0.46)
                .colors(white(), white(), 0, 0)
                .separation("", 0, "", 4, 0)
                .structure(new DomStructureSignature("nav(a,a,a)"), 0)
                .build());
        containers.add(RenderedContainerDescriptor.builder("container-0003")
                .hierarchy("container-0001", blockIds, 1, 1)
                .semantics("main", "results", Collections.<String>emptyList(), "main", "",
                        Arrays.asList("MAIN"))
                .text("results", columnText, blocks.size() * 16, columnText - blocks.size() * 16,
                        blocks.size(), blocks.size())
                .links(blocks.size(), 0, 0, 0, blocks.size(), 0)
                .geometry(true, new RenderedBox(300, 120, 680, 560), 1.0, true, 0.1, 0.1)
                .colors(white(), white(), 0, 0)
                .separation("", 0, "", 12, 8)
                .structure(new DomStructureSignature("main(li,li,li)"), 0)
                .build());
        containers.addAll(blocks);

        return new RenderedPageDocument("snap-" + generation + "-synthetic", generation, state.url,
                state.title, new RenderedPageViewport(1280, 800, 1280, 2000),
                new RenderedPageFingerprint("synthetic"), Arrays.asList("container-0001"),
                containers, links, false, Collections.<String>emptyList());
    }

    private static RenderedColor white() {
        return new RenderedColor(255, 255, 255, 1);
    }

    private static String join(List<String> texts) {
        StringBuilder sb = new StringBuilder();
        for (String text : texts) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(text);
        }
        return sb.toString();
    }
}
