package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.domain.DomainClassification;
import com.aresstack.askai.browser.render.RenderedContainerDescriptor;
import com.aresstack.askai.browser.render.LinkRedirectResolution;
import com.aresstack.askai.browser.render.RenderedLinkDescriptor;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Detects the repeated RESULT BLOCKS inside the chosen ORGANIC_RESULTS container: descend through
 * single-child wrappers, cluster the sibling blocks by simplified structure signature, take the
 * largest cluster of at least the configured repetition count, then resolve each block's primary
 * title link, snippet and sitelinks. A single anchor is never a result; blocks without a
 * qualifying primary link are rejected with a reason (diagnostics).
 * <p>
 * Inside the chosen cluster only the members of the list count: an element named as an ad, and an
 * element that lacks the class every other result of the list carries (Bing's AI answer cards
 * {@code li.b_ans} between ten {@code li.b_algo}), is set aside with a reason. Structure alone does
 * not separate them — an answer card is built from the same {@code li}/{@code div} skeleton.
 */
public final class SearchResultBlockDetector {

    /** The detected blocks plus the reasons for everything that was rejected. */
    public static final class Detection {
        public final List<DetectedResultBlock> blocks;
        public final List<String> rejectionReasons;
        /**
         * Result blocks whose primary link is a redirect without a known target (e.g. Google {@code /goto}). Since
         * askai #41 (lazy resolution) they ARE blocks and become unverified candidates; this list only reports
         * them for diagnostics.
         */
        public final List<UnresolvedRedirectHit> unresolvedRedirectHits;

        Detection(List<DetectedResultBlock> blocks, List<String> rejectionReasons) {
            this(blocks, rejectionReasons, Collections.<UnresolvedRedirectHit>emptyList());
        }

        Detection(List<DetectedResultBlock> blocks, List<String> rejectionReasons,
                  List<UnresolvedRedirectHit> unresolvedRedirectHits) {
            this.blocks = Collections.unmodifiableList(blocks);
            this.rejectionReasons = Collections.unmodifiableList(rejectionReasons);
            this.unresolvedRedirectHits = Collections.unmodifiableList(unresolvedRedirectHits);
        }
    }

    /** A detected hit whose redirect is not resolved yet — diagnostic only; the block itself is kept. */
    public static final class UnresolvedRedirectHit {
        public final String containerId;
        public final String title;
        public final String rawHref;

        UnresolvedRedirectHit(String containerId, String title, String rawHref) {
            this.containerId = containerId;
            this.title = title;
            this.rawHref = rawHref;
        }
    }

    private final LegacyBrowserSearchSettings settings;
    private final DomStructureClusterer clusterer;
    private final PrimaryResultLinkResolver primaryResolver;
    private final SnippetExtractor snippetExtractor;

    public SearchResultBlockDetector(LegacyBrowserSearchSettings settings) {
        this.settings = settings;
        this.clusterer = new DomStructureClusterer(
                settings.analysis.resultBlockSimilarityThreshold);
        this.primaryResolver = new PrimaryResultLinkResolver(settings.analysis,
                settings.extraction);
        this.snippetExtractor = new SnippetExtractor(settings.extraction);
    }

    public Detection detect(RenderedPageDocument document, SearchPageLayoutResolution resolution) {
        List<String> reasons = new ArrayList<String>();
        if (!resolution.hasOrganicResultsContainer()
                || !document.snapshotId.equals(resolution.snapshotId)) {
            reasons.add(document.snapshotId.equals(resolution.snapshotId)
                    ? "no organic results container was resolved"
                    : "stale resolution: snapshot " + resolution.snapshotId
                            + " does not match document " + document.snapshotId);
            return new Detection(Collections.<DetectedResultBlock>emptyList(), reasons);
        }
        RenderedContainerDescriptor container =
                document.container(resolution.organicResultsContainerId);
        // Descend through single-child wrappers to the level where the blocks repeat.
        while (container != null && container.childContainerIds.size() == 1) {
            container = document.container(container.childContainerIds.get(0));
        }
        if (container == null || container.childContainerIds.isEmpty()) {
            reasons.add("organic container has no child blocks");
            return new Detection(Collections.<DetectedResultBlock>emptyList(), reasons);
        }

        List<RenderedContainerDescriptor> children =
                new ArrayList<RenderedContainerDescriptor>();
        for (String childId : container.childContainerIds) {
            RenderedContainerDescriptor child = document.container(childId);
            if (child != null && child.visible) {
                children.add(child);
            }
        }
        Collections.sort(children, new Comparator<RenderedContainerDescriptor>() {
            public int compare(RenderedContainerDescriptor a, RenderedContainerDescriptor b) {
                return Integer.compare(a.siblingIndex, b.siblingIndex);
            }
        });

        List<List<RenderedContainerDescriptor>> clusters = clusterer.cluster(children);
        // A cluster competes with what is LEFT of it once ads and non-list members are set aside:
        // six ads must not push five organic results out just because six is more than five.
        List<RenderedContainerDescriptor> resultCluster = null;
        List<RenderedContainerDescriptor> resultBlocks = null;
        List<String> resultReasons = null;
        boolean repeated = false;
        for (List<RenderedContainerDescriptor> cluster : clusters) {
            if (cluster.size() < settings.analysis.minimumRepeatedSiblingCount) {
                continue;
            }
            repeated = true;
            List<String> memberReasons = new ArrayList<String>();
            List<RenderedContainerDescriptor> members = listMembers(cluster, memberReasons);
            if (!members.isEmpty()
                    && (resultBlocks == null || members.size() > resultBlocks.size())) {
                resultCluster = cluster;
                resultBlocks = members;
                resultReasons = memberReasons;
            }
        }
        if (resultCluster == null) {
            reasons.add(repeated
                    ? "no repeated block structure of organic results: every cluster of at least "
                            + settings.analysis.minimumRepeatedSiblingCount
                            + " similar siblings consists of ads or non-list elements only"
                    : "no repeated block structure: largest cluster of "
                            + largest(clusters) + " similar siblings is below the required "
                            + settings.analysis.minimumRepeatedSiblingCount);
            return new Detection(Collections.<DetectedResultBlock>emptyList(), reasons);
        }

        reportSetAsideClusters(clusters, resultCluster, resultBlocks.size(), reasons);
        reasons.addAll(resultReasons);
        List<UnresolvedRedirectHit> unresolved = new ArrayList<UnresolvedRedirectHit>();
        return new Detection(buildBlocks(document, resultBlocks, resultBlocks.size(), reasons, unresolved),
                reasons, unresolved);
    }

    /**
     * A second repeated shape next to the chosen list is worth a line in the diagnostics: when a list
     * of results is ever split again, the log says so instead of silently showing fewer results.
     */
    private void reportSetAsideClusters(List<List<RenderedContainerDescriptor>> clusters,
                                        List<RenderedContainerDescriptor> resultCluster,
                                        int resultMembers, List<String> reasons) {
        for (List<RenderedContainerDescriptor> cluster : clusters) {
            if (cluster != resultCluster
                    && cluster.size() >= settings.analysis.minimumRepeatedSiblingCount) {
                reasons.add("set aside a second repeated block shape of " + cluster.size()
                        + " siblings starting at " + cluster.get(0).containerId
                        + " (the result list is " + resultCluster.get(0).containerId + "… with "
                        + resultMembers + " list members)");
            }
        }
    }

    /**
     * The members of the result list: ads are never results. When most of the cluster shares a class
     * token, a member without it stays a result as long as it carries the STRUCTURAL CORE of the list:
     * every element path (tag below tag, from the block down) that all results with the class have in
     * common. What it adds or leaves out beyond that core does not matter, so a "featured" variant
     * with a sitelink row or a source line keeps its place even where the clusterer admitted it only
     * through the representative. An answer card that lacks part of the core (Bing's {@code b_ans}
     * cards have no {@code li > h2 > a} title link) does not. Without a shared class nothing is removed.
     */
    private List<RenderedContainerDescriptor> listMembers(
            List<RenderedContainerDescriptor> cluster, List<String> reasons) {
        List<RenderedContainerDescriptor> withoutAds = new ArrayList<RenderedContainerDescriptor>();
        for (RenderedContainerDescriptor member : cluster) {
            if (SearchPageMechanicalAnalyzer.namedAsAdvertisement(member)) {
                reasons.add(member.containerId + ": advertisement, not an organic result");
            } else {
                withoutAds.add(member);
            }
        }
        Set<String> listClasses = sharedClassTokens(withoutAds);
        if (listClasses.isEmpty()) {
            return withoutAds;
        }
        List<RenderedContainerDescriptor> majority = new ArrayList<RenderedContainerDescriptor>();
        for (RenderedContainerDescriptor member : withoutAds) {
            if (!Collections.disjoint(member.classTokens, listClasses)) {
                majority.add(member);
            }
        }
        // Not pairwise similarity: the clusterer admits a member through the representative alone, so
        // two admitted variants may be far apart from each other. What every result shares is the core.
        Set<String> core = null;
        for (RenderedContainerDescriptor member : majority) {
            Set<String> paths = elementPaths(member);
            if (core == null) {
                core = new TreeSet<String>(paths);
            } else {
                core.retainAll(paths);
            }
        }
        List<RenderedContainerDescriptor> members = new ArrayList<RenderedContainerDescriptor>();
        for (RenderedContainerDescriptor member : withoutAds) {
            Set<String> missing = new TreeSet<String>();
            if (Collections.disjoint(member.classTokens, listClasses)) {
                missing.addAll(core);
                missing.removeAll(elementPaths(member));
            }
            if (!missing.isEmpty()) {
                reasons.add(member.containerId + ": not part of the result list (class "
                        + member.classTokens + " lacks " + listClasses
                        + " that the other results carry, and it lacks the structure every one of them has: "
                        + missing + ")");
            } else {
                members.add(member);
            }
        }
        return members;
    }

    /**
     * The element paths of a block's structure signature, e.g. {@code li(h2(a),p)} gives {@code li},
     * {@code li>h2}, {@code li>h2>a} and {@code li>p} (presence only, not how often).
     */
    static Set<String> elementPaths(RenderedContainerDescriptor block) {
        Set<String> paths = new TreeSet<String>();
        String signature = block.structureSignature == null ? null : block.structureSignature.value;
        if (signature == null) {
            return paths;
        }
        java.util.Deque<String> open = new java.util.ArrayDeque<String>();
        String current = null;
        StringBuilder tag = new StringBuilder();
        for (int i = 0; i <= signature.length(); i++) {
            char c = i < signature.length() ? signature.charAt(i) : ',';
            if (Character.isLetterOrDigit(c)) {
                tag.append(Character.toLowerCase(c));
                continue;
            }
            if (tag.length() > 0) {
                current = open.isEmpty() ? tag.toString() : open.peek() + ">" + tag;
                paths.add(current);
                tag.setLength(0);
            }
            if (c == '(' && current != null) {
                open.push(current);
            } else if (c == ')' && !open.isEmpty()) {
                current = open.pop();
            }
        }
        return paths;
    }

    /**
     * The most frequent class token(s) of the cluster, provided they mark a clear majority that is
     * itself a repeated list — otherwise none, and the cluster stays as it is.
     */
    private Set<String> sharedClassTokens(List<RenderedContainerDescriptor> members) {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        for (RenderedContainerDescriptor member : members) {
            for (String token : new HashSet<String>(member.classTokens)) {
                Integer count = counts.get(token);
                counts.put(token, count == null ? 1 : count + 1);
            }
        }
        int top = 0;
        for (int count : counts.values()) {
            top = Math.max(top, count);
        }
        Set<String> shared = new TreeSet<String>();
        if (top == members.size() || top * 2 <= members.size()
                || top < settings.analysis.minimumRepeatedSiblingCount) {
            return shared;
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() == top) {
                shared.add(entry.getKey());
            }
        }
        return shared;
    }

    /**
     * The result blocks a VALIDATED layout decision named, taken at their word.
     * <p>
     * Everything that makes a block a result stays in force: the container must exist and be visible,
     * it must yield a qualifying primary external link, the candidate limit and the URL deduplication
     * downstream are unchanged. What does NOT apply is the repeated-sibling requirement — that is a
     * DISCOVERY heuristic for working out which children of a region are result cards. Once a repair
     * has already identified the cards, running the same discovery again as a second gate threw the
     * answer away and failed the page for not looking like a list.
     */
    public Detection detectExplicit(RenderedPageDocument document, List<String> blockIds) {
        List<String> reasons = new ArrayList<String>();
        List<RenderedContainerDescriptor> named = new ArrayList<RenderedContainerDescriptor>();
        for (String blockId : blockIds == null ? Collections.<String>emptyList() : blockIds) {
            RenderedContainerDescriptor block = document.container(blockId);
            if (block == null) {
                reasons.add(blockId + ": no such container in this snapshot");
                continue;
            }
            if (!block.visible) {
                reasons.add(blockId + ": not visible");
                continue;
            }
            named.add(block);
        }
        if (named.isEmpty()) {
            reasons.add("no named result block survived the structural checks");
            return new Detection(Collections.<DetectedResultBlock>emptyList(), reasons);
        }
        List<UnresolvedRedirectHit> unresolved = new ArrayList<UnresolvedRedirectHit>();
        return new Detection(buildBlocks(document, named, named.size(), reasons, unresolved), reasons,
                unresolved);
    }

    /**
     * Block containers → extracted result blocks. ONE implementation for both paths: the mechanically
     * clustered blocks and the ones a repair named go through the same primary-link resolution,
     * snippet extraction, sitelink collection and candidate limit.
     */
    private List<DetectedResultBlock> buildBlocks(RenderedPageDocument document,
                                                  List<RenderedContainerDescriptor> blockContainers,
                                                  int peerCount, List<String> reasons,
                                                  List<UnresolvedRedirectHit> unresolved) {
        Map<String, List<RenderedLinkDescriptor>> linksByContainer = indexLinks(document);
        Map<String, List<String>> childrenByParent = indexChildren(document);
        List<DetectedResultBlock> blocks = new ArrayList<DetectedResultBlock>();
        int rank = 0;
        for (RenderedContainerDescriptor block : blockContainers) {
            if (blocks.size() >= settings.extraction.maximumExtractedCandidates) {
                reasons.add("candidate limit " + settings.extraction.maximumExtractedCandidates
                        + " reached — remaining blocks dropped");
                break;
            }
            List<RenderedLinkDescriptor> blockLinks =
                    subtreeLinks(block.containerId, childrenByParent, linksByContainer);
            PrimaryResultLinkResolver.Primary primary = primaryResolver.resolve(blockLinks);
            if (primary == null) {
                reasons.add(block.containerId + ": no qualifying primary link");
                continue;
            }
            if (primary.link.redirectResolutionStatus == LinkRedirectResolution.UNRESOLVED) {
                unresolved.add(new UnresolvedRedirectHit(block.containerId, primary.link.visibleText.trim(),
                        primary.link.rawHref));
                reasons.add(block.containerId + ": redirect target not resolved yet — kept as unverified"
                        + " candidate (the browser resolves it when it opens the hit)");
            }
            String snippet = snippetExtractor.extract(block, primary.link);
            List<RenderedLinkDescriptor> siteLinks = siteLinks(blockLinks, primary.link);
            rank++;
            blocks.add(new DetectedResultBlock(block.containerId, rank, primary.link,
                    primary.confidence, primary.link.visibleText.trim(), snippet,
                    primary.link.displayedDomainText, siteLinks,
                    structuralConfidence(peerCount, snippet)));
        }
        return blocks;
    }

    /**
     * Settings-derived block confidence: repetition strength, the (always present) title link and
     * the snippet, normalized by their weights — a hit WITH explanatory text scores clearly above
     * an isolated link.
     */
    private double structuralConfidence(int clusterSize, String snippet) {
        double repetition = settings.analysis.repeatedBlockWeight
                * Math.min(1.0, (double) clusterSize / settings.analysis.minimumRepeatedSiblingCount);
        double title = settings.analysis.titleLinkWeight;
        double snippetScore = snippet.isEmpty() ? 0 : settings.analysis.snippetPresenceWeight;
        double maximum = settings.analysis.repeatedBlockWeight
                + settings.analysis.titleLinkWeight + settings.analysis.snippetPresenceWeight;
        return maximum <= 0 ? 0
                : Math.max(0, Math.min(1, (repetition + title + snippetScore) / maximum));
    }

    private List<RenderedLinkDescriptor> siteLinks(List<RenderedLinkDescriptor> blockLinks,
                                                   RenderedLinkDescriptor primary) {
        List<RenderedLinkDescriptor> siteLinks = new ArrayList<RenderedLinkDescriptor>();
        for (RenderedLinkDescriptor link : blockLinks) {
            if (siteLinks.size() >= settings.extraction.maximumSiteLinksPerResult) {
                break;
            }
            String url = LegacySearchResultExtractor.navigationUrlOf(link);
            if (!link.linkId.equals(primary.linkId) && link.visible
                    && !url.isEmpty()
                    && link.domainClassification == DomainClassification.EXTERNAL_DOMAIN
                    && !url.equals(LegacySearchResultExtractor.navigationUrlOf(primary))) {
                siteLinks.add(link);
            }
        }
        return siteLinks;
    }

    private static int largest(List<List<RenderedContainerDescriptor>> clusters) {
        int largest = 0;
        for (List<RenderedContainerDescriptor> cluster : clusters) {
            largest = Math.max(largest, cluster.size());
        }
        return largest;
    }

    private static Map<String, List<RenderedLinkDescriptor>> indexLinks(
            RenderedPageDocument document) {
        Map<String, List<RenderedLinkDescriptor>> byContainer =
                new HashMap<String, List<RenderedLinkDescriptor>>();
        for (RenderedLinkDescriptor link : document.links) {
            List<RenderedLinkDescriptor> links = byContainer.get(link.containerId);
            if (links == null) {
                links = new ArrayList<RenderedLinkDescriptor>();
                byContainer.put(link.containerId, links);
            }
            links.add(link);
        }
        return byContainer;
    }

    private static Map<String, List<String>> indexChildren(RenderedPageDocument document) {
        Map<String, List<String>> byParent = new HashMap<String, List<String>>();
        for (RenderedContainerDescriptor container : document.containers) {
            byParent.put(container.containerId, container.childContainerIds);
        }
        return byParent;
    }

    /** All links of the block's container SUBTREE, in document order. */
    private static List<RenderedLinkDescriptor> subtreeLinks(
            String blockId, Map<String, List<String>> childrenByParent,
            Map<String, List<RenderedLinkDescriptor>> linksByContainer) {
        List<RenderedLinkDescriptor> links = new ArrayList<RenderedLinkDescriptor>();
        Set<String> visited = new HashSet<String>();
        Deque<String> queue = new ArrayDeque<String>();
        queue.add(blockId);
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!visited.add(id)) {
                continue;
            }
            List<RenderedLinkDescriptor> own = linksByContainer.get(id);
            if (own != null) {
                links.addAll(own);
            }
            List<String> children = childrenByParent.get(id);
            if (children != null) {
                queue.addAll(children);
            }
        }
        Collections.sort(links, new Comparator<RenderedLinkDescriptor>() {
            public int compare(RenderedLinkDescriptor a, RenderedLinkDescriptor b) {
                return a.linkId.compareTo(b.linkId);
            }
        });
        return links;
    }
}
