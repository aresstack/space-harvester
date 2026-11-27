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
import com.aresstack.askai.browser.search.SearchPageAnalysisSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Captures the rendered page as one NEUTRAL {@link RenderedPageDocument}: a single in-page script
 * measures the container hierarchy, text/link statistics, geometry, visibility and computed colors
 * in ONE DOM pass (structure and geometry from the same state), guarded by a cheap DOM fingerprint
 * before and after — on a mismatch the capture settles briefly and is recaptured (bounded by the
 * {@code maximumCaptureRetries} setting; default 0 = capture once) and otherwise marked inconsistent,
 * never silently mixed. All limits come from {@link SearchPageAnalysisSettings}; the script writes
 * nothing into the page, navigates nowhere and opens nothing.
 *
 * <p>Link enrichment happens Java-side: static search-redirect resolution (the resolved DIRECT URL
 * is the later navigation candidate, the raw wrapper URL stays diagnostic provenance) and the
 * domain classification of the resolved target against the page host.</p>
 */
final class RenderedPageDocumentCapture {

    /** The narrow page seam so the conversion logic is unit-testable without a browser. */
    interface PageScriptRunner {
        Object evaluate(String script);

        String url();

        String title();
    }

    /** The injectable settle wait between recaptures, so the bounded loop is unit-testable. */
    interface SettleDelay {
        void settle(long millis);
    }

    private final SearchPageAnalysisSettings limits;
    private final SettleDelay settleDelay;

    RenderedPageDocumentCapture(SearchPageAnalysisSettings limits) {
        this(limits, new SettleDelay() {
            public void settle(long millis) {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    RenderedPageDocumentCapture(SearchPageAnalysisSettings limits, SettleDelay settleDelay) {
        this.limits = limits;
        this.settleDelay = settleDelay;
    }

    RenderedPageDocument capture(PageScriptRunner page, DomainKeyResolver domainKeys,
                                 long snapshotGeneration) {
        List<String> warnings = new ArrayList<String>();
        RenderedPageFingerprint before = fingerprint(page);
        Object raw = page.evaluate(captureScript());
        RenderedPageFingerprint after = fingerprint(page);
        // The DOM changed DURING capture: bounded settle + FULL fresh recapture (fingerprint taken
        // anew after the settle — the retry must describe ONE state, not straddle the mutation). The
        // bound is a SETTING: on continuously mutating SERPs (Bing) retries never settle and only cost time.
        for (int recapture = 0; !before.matches(after) && recapture < limits.maximumCaptureRetries;
             recapture++) {
            settleDelay.settle(limits.captureSettleMillis);
            before = fingerprint(page);
            raw = page.evaluate(captureScript());
            after = fingerprint(page);
        }
        if (!before.matches(after)) {
            warnings.add("DOM_CHANGED_DURING_CAPTURE: geometry and structure may not describe "
                    + "one consistent state");
        }
        return convert(raw, page.url(), page.title(), after, snapshotGeneration, domainKeys,
                warnings);
    }

    private RenderedPageFingerprint fingerprint(PageScriptRunner page) {
        Object value = page.evaluate(FINGERPRINT_SCRIPT);
        return new RenderedPageFingerprint(String.valueOf(value));
    }

    static final String FINGERPRINT_SCRIPT =
            "() => document.getElementsByTagName('*').length + ':'"
                    + " + (document.body ? document.body.textContent.length : 0) + ':'"
                    + " + Array.from(document.body ? document.body.children : [])"
                    + ".slice(0, 24).map(e => e.tagName).join(',')";

    // ------------------------------------------------------------------ conversion (unit-tested)

    @SuppressWarnings("unchecked")
    private RenderedPageDocument convert(Object raw, String url, String title,
                                         RenderedPageFingerprint fingerprint, long generation,
                                         DomainKeyResolver domainKeys, List<String> warnings) {
        Map<String, Object> root = raw instanceof Map ? (Map<String, Object>) raw
                : Collections.<String, Object>emptyMap();
        boolean truncated = bool(root.get("truncated"));
        for (Object warning : list(root.get("warnings"))) {
            warnings.add(String.valueOf(warning));
        }
        RenderedPageViewport viewport = new RenderedPageViewport(
                intOf(root.get("viewportWidth")), intOf(root.get("viewportHeight")),
                intOf(root.get("documentWidth")), intOf(root.get("documentHeight")));

        List<Map<String, Object>> rawContainers = mapList(root.get("containers"));
        // Pass 1: colors by id (for parent/page distances) and children per parent.
        Map<String, RenderedColor> effectiveById = new HashMap<String, RenderedColor>();
        Map<String, List<String>> childrenByParent = new HashMap<String, List<String>>();
        Map<String, DomStructureSignature> signatureById =
                new HashMap<String, DomStructureSignature>();
        for (Map<String, Object> c : rawContainers) {
            String id = str(c.get("id"));
            effectiveById.put(id, color(c.get("effectiveBg")));
            signatureById.put(id, new DomStructureSignature(str(c.get("signature"))));
            String parent = str(c.get("parentId"));
            List<String> siblings = childrenByParent.get(parent);
            if (siblings == null) {
                siblings = new ArrayList<String>();
                childrenByParent.put(parent, siblings);
            }
            siblings.add(id);
        }
        RenderedColor pageBackground = effectiveById.isEmpty() ? RenderedColor.TRANSPARENT
                : effectiveById.values().iterator().next();
        // The page background is the effective background of the FIRST (body) container.
        for (Map<String, Object> c : rawContainers) {
            if (str(c.get("parentId")).isEmpty()) {
                pageBackground = effectiveById.get(str(c.get("id")));
                break;
            }
        }

        DomainIdentity pageIdentity = domainKeys.resolve(url);
        List<RenderedContainerDescriptor> containers =
                new ArrayList<RenderedContainerDescriptor>();
        List<String> rootIds = new ArrayList<String>();
        for (Map<String, Object> c : rawContainers) {
            String id = str(c.get("id"));
            String parentId = str(c.get("parentId"));
            if (parentId.isEmpty()) {
                rootIds.add(id);
            }
            RenderedColor computed = color(c.get("computedBg"));
            RenderedColor effective = effectiveById.get(id);
            RenderedColor parentEffective = parentId.isEmpty() ? pageBackground
                    : effectiveById.get(parentId);
            containers.add(RenderedContainerDescriptor.builder(id)
                    .hierarchy(parentId, strings(c.get("childIds")), intOf(c.get("siblingIndex")),
                            intOf(c.get("depth")))
                    .semantics(str(c.get("tag")), str(c.get("elementId")),
                            strings(c.get("classes")), str(c.get("role")),
                            str(c.get("ariaLabel")), strings(c.get("semanticFlags")))
                    .text(str(c.get("textExcerpt")), intOf(c.get("textLength")),
                            intOf(c.get("linkTextLength")),
                            Math.max(0, intOf(c.get("textLength")) - intOf(c.get("linkTextLength"))),
                            intOf(c.get("headingCount")), intOf(c.get("paragraphCount")))
                    .links(intOf(c.get("linkCount")), intOf(c.get("sameHostLinks")),
                            intOf(c.get("sameRegistrableLinks")), intOf(c.get("subdomainLinks")),
                            intOf(c.get("externalLinks")), intOf(c.get("actionLinks")))
                    .geometry(bool(c.get("visible")), box(c.get("box")),
                            doubleOf(c.get("viewportIntersection")),
                            bool(c.get("containsCenter")), doubleOf(c.get("centerDx")),
                            doubleOf(c.get("centerDy")))
                    .colors(computed, effective,
                            effective == null ? 1.0 : effective.distanceTo(parentEffective),
                            effective == null ? 1.0 : effective.distanceTo(pageBackground))
                    .separation(str(c.get("border")), doubleOf(c.get("borderRadius")),
                            str(c.get("boxShadow")), doubleOf(c.get("padding")),
                            doubleOf(c.get("margin")))
                    .structure(signatureById.get(id),
                            similarSiblings(id, parentId, childrenByParent, signatureById))
                    .build());
        }

        List<RenderedLinkDescriptor> links = new ArrayList<RenderedLinkDescriptor>();
        int opaqueWrappers = 0;
        for (Map<String, Object> l : mapList(root.get("links"))) {
            String rawHref = str(l.get("href"));
            SearchRedirectResolver.Resolution resolution =
                    SearchRedirectResolver.resolve(rawHref, str(l.get("targetHint")));
            if ((resolution.getStatus() == SearchRedirectResolver.Status.UNRESOLVED
                    || resolution.getStatus() == SearchRedirectResolver.Status.DISCARDED)
                    && SearchRedirectResolver.isOpaqueWrapper(rawHref)) {
                opaqueWrappers++;
            }
            LinkRedirectResolution status;
            String target;
            switch (resolution.getStatus()) {
                case RESOLVED:
                    status = LinkRedirectResolution.RESOLVED;
                    target = resolution.getTargetUrl();
                    break;
                case UNRESOLVED:
                    status = LinkRedirectResolution.UNRESOLVED;
                    target = "";
                    break;
                case DISCARDED:
                    status = LinkRedirectResolution.DISCARDED;
                    target = "";
                    break;
                default:
                    status = LinkRedirectResolution.NOT_A_REDIRECT;
                    target = rawHref;
            }
            DomainClassification classification = target.isEmpty()
                    ? DomainClassification.EXTERNAL_DOMAIN
                    : DomainClassification.classify(domainKeys.resolve(target), pageIdentity);
            links.add(new RenderedLinkDescriptor(str(l.get("id")), str(l.get("containerId")),
                    rawHref, target, status, str(l.get("text")), str(l.get("surrounding")),
                    str(l.get("heading")), str(l.get("displayedDomain")), classification,
                    bool(l.get("visible")), box(l.get("box")), bool(l.get("insideHeading"))));
        }

        if (opaqueWrappers > 0) {
            warnings.add("OPAQUE_REDIRECTS: " + opaqueWrappers + " links are opaque engine wrappers (Google /goto, Baidu /link) "
                    + "whose target is not in the URL; they stay unresolved and are never results");
        }
        String snapshotId = "snap-" + generation + "-"
                + Integer.toHexString(fingerprint.value.hashCode());
        return new RenderedPageDocument(snapshotId, generation, url, title, viewport, fingerprint,
                rootIds, containers, links, truncated, warnings);
    }

    private static int similarSiblings(String id, String parentId,
                                       Map<String, List<String>> childrenByParent,
                                       Map<String, DomStructureSignature> signatureById) {
        List<String> siblings = childrenByParent.get(parentId);
        if (siblings == null) {
            return 0;
        }
        DomStructureSignature own = signatureById.get(id);
        if (own == null || own.isEmpty()) {
            return 0;
        }
        int similar = 0;
        for (String sibling : siblings) {
            if (!sibling.equals(id) && own.equals(signatureById.get(sibling))) {
                similar++;
            }
        }
        return similar;
    }

    // ------------------------------------------------------------------ the in-page script

    String captureScript() {
        // ONE bottom-up DOM pass: structure, text/link statistics, geometry and computed colors are
        // read from the SAME state. The script only READS — no attributes, no navigation, no popups.
        return "() => {\n"
                + "  const MAX_DEPTH = " + limits.maximumContainerDomDepth + ";\n"
                + "  const MAX_CONTAINERS = " + limits.maximumCapturedContainers + ";\n"
                + "  const MAX_LINKS_PER = " + limits.maximumLinksPerContainer + ";\n"
                + "  const SIG_DEPTH = " + limits.maximumStructureSignatureDepth + ";\n"
                + "  const EXCERPT = 200;\n"
                // LINK/META are never rendered: Bing puts deferred stylesheet <link>s INTO the first
                // result block, whose structure signature became li(link,link,...) and never matched.
                + "  const SKIP = new Set(['SCRIPT','STYLE','NOSCRIPT','TEMPLATE','SVG','IFRAME','HEAD','LINK','META']);\n"
                + "  const CONTAINER = new Set(['DIV','SECTION','MAIN','NAV','HEADER','FOOTER','ASIDE',"
                + "'ARTICLE','UL','OL','LI','FORM','TABLE','TBODY','TR','TD','DL','DD','DT',"
                + "'FIELDSET','DETAILS','BODY']);\n"
                + "  const vw = window.innerWidth, vh = window.innerHeight;\n"
                + "  const containers = [], links = [], warnings = [];\n"
                + "  let truncated = false, containerSeq = 0, linkSeq = 0;\n"
                + "  let capReached = false, depthReached = false, linkCapReached = false;\n"
                + "  const pad = n => String(n).padStart(4, '0');\n"
                + "  const clean = t => (t || '').replace(/\\s+/g, ' ').trim();\n"
                // Open shadow roots are part of the rendered page: a result list rendered by a web
                // component is invisible to a childNodes-only walk (no containers, no links).
                + "  let shadowRoots = 0;\n"
                + "  const kidsOf = n => {\n"
                + "    if (!n.shadowRoot) return n.childNodes;\n"
                + "    shadowRoots++;\n"
                + "    return Array.from(n.shadowRoot.childNodes).concat(Array.from(n.childNodes));\n"
                + "  };\n"
                // textContent minus the SKIP subtrees: raw textContent includes <style>/<script> text,
                // which turned Bing's inline CSS into stored search excerpts. Every user-facing text
                // (excerpts, link texts, headings) goes through THIS, never through raw textContent.
                + "  const textOf = (el) => {\n"
                + "    let out = '';\n"
                + "    const walk = (n) => {\n"
                + "      if (n.nodeType === 3) { out += n.nodeValue + ' '; return; }\n"
                + "      if (n.nodeType !== 1 || SKIP.has(n.tagName)) return;\n"
                + "      for (const c of kidsOf(n)) walk(c);\n"
                + "    };\n"
                + "    if (el) walk(el);\n"
                + "    return clean(out);\n"
                + "  };\n"
                + "  const parseColor = c => {\n"
                + "    const m = /rgba?\\(([\\d.]+),\\s*([\\d.]+),\\s*([\\d.]+)(?:,\\s*([\\d.]+))?\\)/.exec(c || '');\n"
                + "    return m ? [+m[1], +m[2], +m[3], m[4] === undefined ? 1 : +m[4]] : [0, 0, 0, 0];\n"
                + "  };\n"
                + "  const effectiveBg = el => {\n"
                + "    let cur = el;\n"
                + "    while (cur) {\n"
                + "      const c = parseColor(getComputedStyle(cur).backgroundColor);\n"
                + "      if (c[3] > 0.01) return c;\n"
                + "      cur = cur.parentElement;\n"
                + "    }\n"
                + "    return [255, 255, 255, 1];\n"
                + "  };\n"
                + "  const signature = (el, depth) => {\n"
                + "    const tag = el.tagName.toLowerCase();\n"
                + "    if (depth <= 0 || !el.children.length) return tag;\n"
                + "    const kids = [];\n"
                + "    for (const child of el.children) {\n"
                + "      if (SKIP.has(child.tagName)) continue;\n"
                + "      kids.push(signature(child, depth - 1));\n"
                + "      if (kids.length >= 8) break;\n"
                + "    }\n"
                + "    return tag + (kids.length ? '(' + kids.join(',') + ')' : '');\n"
                + "  };\n"
                + "  const displayedDomainOf = a => {\n"
                + "    let cur = a, hops = 0;\n"
                + "    while (cur && hops++ < 4) {\n"
                + "      const cite = cur.querySelector ? cur.querySelector('cite') : null;\n"
                + "      if (cite) return clean(cite.textContent).slice(0, 120);\n"
                + "      cur = cur.parentElement;\n"
                + "    }\n"
                + "    return '';\n"
                + "  };\n"
                // Why links count as invisible is the first question when a result page yields no
                // primary link; the counts travel as one LINK_VISIBILITY capture warning.
                + "  const linkStats = { noBox: 0, styleHidden: 0, skippedContent: 0 };\n"
                + "  const containerStats = { visibilityHidden: 0, transparent: 0, noBox: 0 };\n"
                // content-visibility:auto content below the fold has no layout box yet, but it IS
                // displayed content: checkVisibility() says visible, the contentVisibilityAuto check
                // says skipped. Only that case is let through without a box.
                + "  const inSkippedContent = el => typeof el.checkVisibility === 'function'\n"
                + "      && el.checkVisibility() && !el.checkVisibility({ contentVisibilityAuto: true });\n"
                + "  const linkVisible = (a, st, rect) => {\n"
                + "    if (st.display === 'none' || st.visibility === 'hidden') { linkStats.styleHidden++; return false; }\n"
                + "    if (rect.width > 0 && rect.height > 0) return true;\n"
                + "    if (inSkippedContent(a)) { linkStats.skippedContent++; return true; }\n"
                + "    linkStats.noBox++;\n"
                + "    return false;\n"
                + "  };\n"
                + "  const recordLink = (a, containerId, ctx) => {\n"
                + "    if (ctx.links >= MAX_LINKS_PER) { truncated = true; linkCapReached = true; return; }\n"
                + "    ctx.links++;\n"
                + "    const rect = a.getBoundingClientRect();\n"
                + "    const st = getComputedStyle(a);\n"
                + "    const blockText = ctx.containerEl ? textOf(ctx.containerEl) : '';\n"
                + "    links.push({\n"
                + "      id: 'link-' + pad(++linkSeq), containerId: containerId,\n"
                + "      href: a.href || '', text: textOf(a).slice(0, 300),\n"
                // the target an engine prints on the result block itself (Baidu's mu attribute): read from the
                // page, never fetched; only an opaque wrapper link uses it (SearchRedirectResolver)
                + "      targetHint: (a.closest('[" + SearchRedirectResolver.BAIDU_TARGET_ATTRIBUTE + "]') || a)"
                + ".getAttribute('" + SearchRedirectResolver.BAIDU_TARGET_ATTRIBUTE + "') || '',\n"
                + "      surrounding: blockText.slice(0, EXCERPT),\n"
                + "      heading: ctx.lastHeading.slice(0, 200),\n"
                + "      displayedDomain: displayedDomainOf(a),\n"
                + "      insideHeading: !!a.closest('h1,h2,h3,h4,h5,h6'),\n"
                + "      visible: linkVisible(a, st, rect),\n"
                + "      box: { x: rect.x, y: rect.y, w: rect.width, h: rect.height }\n"
                + "    });\n"
                + "  };\n"
                + "  // Collect text/link statistics of NON-container subtrees into the owning container.\n"
                + "  const collectInline = (node, containerId, stats, ctx) => {\n"
                + "    if (node.nodeType === 3) { stats.text += clean(node.nodeValue).length; return; }\n"
                + "    if (node.nodeType !== 1 || SKIP.has(node.tagName)) return;\n"
                + "    const tag = node.tagName;\n"
                + "    if (/^H[1-6]$/.test(tag)) { stats.headings++; ctx.lastHeading = textOf(node); }\n"
                + "    if (tag === 'P') stats.paragraphs++;\n"
                + "    if (tag === 'A' && node.href) {\n"
                + "      const len = clean(node.textContent).length;\n"
                + "      stats.text += len; stats.linkText += len;\n"
                + "      recordLink(node, containerId, ctx);\n"
                + "      countLink(node, stats);\n"
                + "      return;\n"
                + "    }\n"
                + "    for (const child of kidsOf(node)) collectInline(child, containerId, stats, ctx);\n"
                + "  };\n"
                + "  const pageHost = location.hostname;\n"
                + "  const countLink = (a, stats) => {\n"
                + "    stats.linkCount++;\n"
                + "    const href = a.getAttribute('href') || '';\n"
                + "    if (href.startsWith('javascript:') || href === '#' || href.startsWith('#')) {\n"
                + "      stats.actionLinks++; return;\n"
                + "    }\n"
                + "    let host = '';\n"
                + "    try { host = new URL(a.href, location.href).hostname; } catch (e) {}\n"
                + "    if (!host || host === pageHost) { stats.sameHostLinks++; return; }\n"
                + "    if (host.endsWith('.' + pageHost)) { stats.subdomainLinks++; return; }\n"
                + "    const tail = h => h.split('.').slice(-2).join('.');\n"
                + "    if (tail(host) === tail(pageHost)) { stats.sameRegistrableLinks++; return; }\n"
                + "    stats.externalLinks++;\n"
                + "  };\n"
                + "  const walk = (el, parentId, depth, siblingIndex) => {\n"
                + "    if (containerSeq >= MAX_CONTAINERS) { truncated = true; capReached = true; return null; }\n"
                + "    if (depth > MAX_DEPTH) { truncated = true; depthReached = true; return null; }\n"
                + "    const seqAtEntry = containerSeq, containersAtEntry = containers.length;\n"
                + "    const id = 'container-' + pad(++containerSeq);\n"
                + "    const stats = { text: 0, linkText: 0, headings: 0, paragraphs: 0, linkCount: 0,\n"
                + "      sameHostLinks: 0, sameRegistrableLinks: 0, subdomainLinks: 0,\n"
                + "      externalLinks: 0, actionLinks: 0 };\n"
                + "    const ctx = { links: 0, lastHeading: '', containerEl: el };\n"
                + "    const childIds = [];\n"
                + "    let childIndex = 0;\n"
                + "    for (const child of kidsOf(el)) {\n"
                + "      if (child.nodeType === 1 && CONTAINER.has(child.tagName) && !SKIP.has(child.tagName)) {\n"
                // display:none subtrees (closed flyouts, menus, templates) are never visible results;
                // walking them only spent the container budget before the result list was reached.
                + "        if (getComputedStyle(child).display === 'none') continue;\n"
                + "        const childResult = walk(child, id, depth + 1, childIndex);\n"
                + "        if (childResult) {\n"
                + "          childIds.push(childResult.id); childIndex++;\n"
                + "          stats.text += childResult.stats.text;\n"
                + "          stats.linkText += childResult.stats.linkText;\n"
                + "          stats.headings += childResult.stats.headings;\n"
                + "          stats.paragraphs += childResult.stats.paragraphs;\n"
                + "          stats.linkCount += childResult.stats.linkCount;\n"
                + "          stats.sameHostLinks += childResult.stats.sameHostLinks;\n"
                + "          stats.sameRegistrableLinks += childResult.stats.sameRegistrableLinks;\n"
                + "          stats.subdomainLinks += childResult.stats.subdomainLinks;\n"
                + "          stats.externalLinks += childResult.stats.externalLinks;\n"
                + "          stats.actionLinks += childResult.stats.actionLinks;\n"
                + "        } else {\n"
                + "          collectInline(child, id, stats, ctx);\n"
                + "        }\n"
                + "      } else {\n"
                + "        collectInline(child, id, stats, ctx);\n"
                + "      }\n"
                + "    }\n"
                // A subtree without any text or link (icon/image wrappers, spacers, empty slots) can
                // never hold a result; it gives its ids back so the budget reaches the result list.
                // The descendants were pushed after this entry, so they are exactly the tail.
                + "    if (depth > 0 && stats.text === 0 && stats.linkCount === 0) {\n"
                + "      containers.length = containersAtEntry; containerSeq = seqAtEntry;\n"
                + "      return null;\n"
                + "    }\n"
                + "    const rect = el.getBoundingClientRect();\n"
                + "    const st = getComputedStyle(el);\n"
                + "    const hasBox = (rect.width > 0 && rect.height > 0) || inSkippedContent(el);\n"
                + "    const visible = st.display !== 'none' && st.visibility !== 'hidden'\n"
                + "        && parseFloat(st.opacity || '1') > 0.05 && hasBox;\n"
                + "    if (!visible) {\n"
                + "      if (st.visibility === 'hidden') containerStats.visibilityHidden++;\n"
                + "      else if (!(parseFloat(st.opacity || '1') > 0.05)) containerStats.transparent++;\n"
                + "      else if (!hasBox) containerStats.noBox++;\n"
                + "    }\n"
                + "    const ix = Math.max(0, Math.min(rect.right, vw) - Math.max(rect.left, 0));\n"
                + "    const iy = Math.max(0, Math.min(rect.bottom, vh) - Math.max(rect.top, 0));\n"
                + "    const area = Math.max(1, rect.width * rect.height);\n"
                + "    const borderWidth = parseFloat(st.borderTopWidth || '0');\n"
                + "    const avg4 = (a, b, c, d) =>\n"
                + "        (parseFloat(a || '0') + parseFloat(b || '0') + parseFloat(c || '0') + parseFloat(d || '0')) / 4;\n"
                + "    containers.push({\n"
                + "      id: id, parentId: parentId, childIds: childIds, siblingIndex: siblingIndex,\n"
                + "      depth: depth, tag: el.tagName.toLowerCase(), elementId: el.id || '',\n"
                + "      classes: Array.from(el.classList).slice(0, 12),\n"
                + "      role: el.getAttribute('role') || '', ariaLabel: el.getAttribute('aria-label') || '',\n"
                + "      semanticFlags: [\n"
                + "        el.tagName === 'MAIN' || el.getAttribute('role') === 'main' ? 'MAIN' : null,\n"
                + "        el.tagName === 'NAV' || el.getAttribute('role') === 'navigation' ? 'NAV' : null,\n"
                + "        el.tagName === 'HEADER' || el.getAttribute('role') === 'banner' ? 'HEADER' : null,\n"
                + "        el.tagName === 'FOOTER' || el.getAttribute('role') === 'contentinfo' ? 'FOOTER' : null,\n"
                + "        el.tagName === 'ASIDE' || el.getAttribute('role') === 'complementary' ? 'ASIDE' : null,\n"
                + "        el.tagName === 'ARTICLE' ? 'ARTICLE' : null,\n"
                + "        el.tagName === 'SECTION' ? 'SECTION' : null,\n"
                + "        el.tagName === 'FORM' || el.getAttribute('role') === 'search' ? 'FORM' : null,\n"
                + "        el.tagName === 'UL' || el.tagName === 'OL' ? 'LIST' : null\n"
                + "      ].filter(Boolean),\n"
                + "      textExcerpt: textOf(el).slice(0, EXCERPT),\n"
                + "      textLength: stats.text, linkTextLength: stats.linkText,\n"
                + "      headingCount: stats.headings, paragraphCount: stats.paragraphs,\n"
                + "      linkCount: stats.linkCount, sameHostLinks: stats.sameHostLinks,\n"
                + "      sameRegistrableLinks: stats.sameRegistrableLinks,\n"
                + "      subdomainLinks: stats.subdomainLinks, externalLinks: stats.externalLinks,\n"
                + "      actionLinks: stats.actionLinks,\n"
                + "      visible: visible,\n"
                + "      box: { x: rect.x, y: rect.y, w: rect.width, h: rect.height },\n"
                + "      viewportIntersection: (ix * iy) / area,\n"
                + "      containsCenter: rect.left <= vw / 2 && rect.right >= vw / 2\n"
                + "          && rect.top <= vh / 2 && rect.bottom >= vh / 2,\n"
                + "      centerDx: vw > 0 ? Math.abs((rect.left + rect.width / 2) - vw / 2) / vw : 0,\n"
                + "      centerDy: vh > 0 ? Math.abs((rect.top + rect.height / 2) - vh / 2) / vh : 0,\n"
                + "      computedBg: parseColor(st.backgroundColor), effectiveBg: effectiveBg(el),\n"
                + "      border: borderWidth > 0\n"
                + "          ? st.borderTopWidth + ' ' + st.borderTopStyle + ' ' + st.borderTopColor : '',\n"
                + "      borderRadius: parseFloat(st.borderTopLeftRadius || '0'),\n"
                + "      boxShadow: st.boxShadow && st.boxShadow !== 'none' ? st.boxShadow : '',\n"
                + "      padding: avg4(st.paddingTop, st.paddingRight, st.paddingBottom, st.paddingLeft),\n"
                + "      margin: avg4(st.marginTop, st.marginRight, st.marginBottom, st.marginLeft),\n"
                + "      signature: signature(el, SIG_DEPTH)\n"
                + "    });\n"
                + "    return { id: id, stats: stats };\n"
                + "  };\n"
                + "  if (document.body) walk(document.body, '', 0, 0);\n"
                // Truncation must be VISIBLE in the diagnostics: a capped capture flattens everything
                // past the bound into its ancestors, and the result list then no longer repeats.
                + "  if (linkStats.noBox + linkStats.styleHidden + linkStats.skippedContent > 0)\n"
                + "    warnings.push('LINK_VISIBILITY: ' + links.length + ' links, ' + linkStats.noBox\n"
                + "        + ' without a layout box, ' + linkStats.styleHidden + ' hidden by style, '\n"
                + "        + linkStats.skippedContent + ' in content-visibility skipped content (counted visible)');\n"
                + "  if (containerStats.visibilityHidden + containerStats.transparent + containerStats.noBox > 0)\n"
                + "    warnings.push('CONTAINER_VISIBILITY: ' + containerStats.visibilityHidden\n"
                + "        + ' visibility:hidden, ' + containerStats.transparent + ' opacity<=0.05, '\n"
                + "        + containerStats.noBox + ' without a layout box');\n"
                + "  if (shadowRoots > 0) warnings.push('SHADOW_DOM: open shadow roots were traversed');\n"
                + "  if (capReached) warnings.push('CAPTURE_TRUNCATED: container cap ' + MAX_CONTAINERS\n"
                + "      + ' reached; later page structure was flattened into its ancestors');\n"
                + "  if (depthReached) warnings.push('CAPTURE_TRUNCATED: container depth bound ' + MAX_DEPTH\n"
                + "      + ' reached');\n"
                + "  if (linkCapReached) warnings.push('CAPTURE_TRUNCATED: more than ' + MAX_LINKS_PER\n"
                + "      + ' links in one container; the rest was not recorded');\n"
                + "  return {\n"
                + "    viewportWidth: vw, viewportHeight: vh,\n"
                + "    documentWidth: document.documentElement ? document.documentElement.scrollWidth : vw,\n"
                + "    documentHeight: document.documentElement ? document.documentElement.scrollHeight : vh,\n"
                + "    truncated: truncated, warnings: warnings,\n"
                + "    containers: containers, links: links\n"
                + "  };\n"
                + "}";
    }

    // ------------------------------------------------------------------ raw-value helpers

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean bool(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static int intOf(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static double doubleOf(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
    }

    private static List<Object> list(Object value) {
        return value instanceof List ? castList(value) : Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Object entry : list(value)) {
            if (entry instanceof Map) {
                result.add((Map<String, Object>) entry);
            }
        }
        return result;
    }

    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<String>();
        for (Object entry : list(value)) {
            result.add(String.valueOf(entry));
        }
        return result;
    }

    private static RenderedBox box(Object value) {
        if (!(value instanceof Map)) {
            return new RenderedBox(0, 0, 0, 0);
        }
        Map<?, ?> map = (Map<?, ?>) value;
        return new RenderedBox(doubleOf(map.get("x")), doubleOf(map.get("y")),
                doubleOf(map.get("w")), doubleOf(map.get("h")));
    }

    private static RenderedColor color(Object value) {
        if (!(value instanceof List) || ((List<?>) value).size() < 4) {
            return RenderedColor.TRANSPARENT;
        }
        List<?> rgba = (List<?>) value;
        return new RenderedColor(intOf(rgba.get(0)), intOf(rgba.get(1)), intOf(rgba.get(2)),
                doubleOf(rgba.get(3)));
    }

    /** Lower-cases text for comparisons that must not depend on the page's casing. */
    static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
