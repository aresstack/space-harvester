package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.DomStructureSignature;
import com.aresstack.askai.browser.render.RenderedContainerDescriptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups sibling containers into clusters of the SAME simplified block shape. Two signatures count
 * as the same shape when their tag-token similarity (Dice coefficient) reaches the configured
 * {@code resultBlockSimilarityThreshold} — small per-block differences (an optional date line, a
 * missing sitelink row) must not break the repetition signal.
 * <p>
 * Each cluster is measured against ONE representative, and that representative is the sibling with
 * the most similar siblings — never simply the first one. A list that opens with a foreign element
 * (Bing puts an AI answer card above its ten results) would otherwise make that element the yardstick:
 * results close to it join its cluster, the others open a second one, and one list of ten results is
 * split in two (issue #22, "Java 21 LTS release": 5 of 10 and 3 of 10 results lost). The most
 * connected sibling is a member of the dominant repeated shape, so the clusters no longer depend on
 * what happens to come first.
 */
public final class DomStructureClusterer {

    private final double similarityThreshold;

    public DomStructureClusterer(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    /**
     * Clusters of similar siblings. The first cluster forms around the sibling with the most similar
     * siblings (ties: the earliest), the next around the best connected of the rest, and so on; each
     * cluster keeps its members in input (sibling) order.
     */
    public List<List<RenderedContainerDescriptor>> cluster(
            List<RenderedContainerDescriptor> siblings) {
        List<List<RenderedContainerDescriptor>> clusters =
                new ArrayList<List<RenderedContainerDescriptor>>();
        List<RenderedContainerDescriptor> remaining =
                new ArrayList<RenderedContainerDescriptor>(siblings);
        while (!remaining.isEmpty()) {
            RenderedContainerDescriptor representative = bestConnected(remaining);
            List<RenderedContainerDescriptor> cluster = new ArrayList<RenderedContainerDescriptor>();
            List<RenderedContainerDescriptor> rest = new ArrayList<RenderedContainerDescriptor>();
            for (RenderedContainerDescriptor sibling : remaining) {
                if (sibling == representative || similar(representative, sibling)) {
                    cluster.add(sibling);
                } else {
                    rest.add(sibling);
                }
            }
            clusters.add(cluster);
            remaining = rest;
        }
        return clusters;
    }

    /** The sibling with the most similar siblings; the earliest wins a tie. */
    private RenderedContainerDescriptor bestConnected(List<RenderedContainerDescriptor> siblings) {
        RenderedContainerDescriptor best = null;
        int bestNeighbours = -1;
        for (RenderedContainerDescriptor candidate : siblings) {
            int neighbours = 0;
            for (RenderedContainerDescriptor other : siblings) {
                if (other != candidate && similar(candidate, other)) {
                    neighbours++;
                }
            }
            if (neighbours > bestNeighbours) {
                best = candidate;
                bestNeighbours = neighbours;
            }
        }
        return best;
    }

    private boolean similar(RenderedContainerDescriptor a, RenderedContainerDescriptor b) {
        return similarity(a.structureSignature, b.structureSignature) >= similarityThreshold;
    }

    /** Dice coefficient over tag-token multisets (0..1); identical signatures are 1. */
    static double similarity(DomStructureSignature a, DomStructureSignature b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        if (a.equals(b)) {
            return 1;
        }
        Map<String, Integer> tokensA = tokens(a.value);
        Map<String, Integer> tokensB = tokens(b.value);
        int intersection = 0;
        int totalA = 0;
        int totalB = 0;
        for (Map.Entry<String, Integer> entry : tokensA.entrySet()) {
            totalA += entry.getValue();
            Integer other = tokensB.get(entry.getKey());
            if (other != null) {
                intersection += Math.min(entry.getValue(), other);
            }
        }
        for (int count : tokensB.values()) {
            totalB += count;
        }
        return totalA + totalB == 0 ? 0 : (2.0 * intersection) / (totalA + totalB);
    }

    private static Map<String, Integer> tokens(String signature) {
        Map<String, Integer> tokens = new HashMap<String, Integer>();
        for (String token : signature.split("[^a-z0-9]+")) {
            if (!token.isEmpty()) {
                Integer count = tokens.get(token);
                tokens.put(token, count == null ? 1 : count + 1);
            }
        }
        return tokens;
    }
}
