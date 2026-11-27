package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.DomStructureSignature;
import com.aresstack.askai.browser.render.RenderedContainerDescriptor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Issue #22: the siblings of Bing's {@code ol#b_results} for "Java 21 LTS release", with their real
 * structure signatures. An AI answer card ({@code li.b_ans}) comes first; the ten {@code li.b_algo}
 * results vary in their last part (snippet with or without a date, a sitelink row). Measured against
 * the answer card, part of the results fell below the similarity threshold and the list split in two.
 */
public class DomStructureClustererTest {

    private static final String A = "li(div(a(div,div)),h2(a),div(p(span)),div)";
    private static final String B = "li(div(a(div,div)),h2(a),div(p(span)))";
    private static final String C = "li(div(a(div,div)),h2(a),div(div(div)))";
    private static final String D = "li(div(a(div,div)),h2(a),div(p))";
    private static final String E = "li(div(a(div,div)),h2(a),div(p(span),object(div,div)))";
    private static final String F = "li(div(a(div,div)),h2(a),div(p,object(div,div)))";

    private final DomStructureClusterer clusterer = new DomStructureClusterer(0.6);

    @Test
    public void anAnswerCardInFrontNoLongerSplitsTheTenResults() {
        // Run 1: before the fix the answer card made a cluster of 7 and the other five results
        // a cluster of 6 — five of ten results were lost.
        List<RenderedContainerDescriptor> siblings = siblings(
                "b_ans", "li(div(div(div),div(div,div)),div)",
                "b_algo", A, "b_algo", B, "b_algo", C, "b_algo", D, "b_algo", E,
                "b_algo", D, "b_algo", E, "b_algo", B, "b_algo", F, "b_algo", B,
                "b_ans", "li(div(div(div,div),div),div)",
                "b_ans", "li(div(div(span,a),div(ul)),div(h2(strong),ul(li,li,li)))",
                "b_msg", "li(div(a))",
                "b_pag", "li(nav(h4,ul(li,li,li,li,li)))");

        List<RenderedContainerDescriptor> largest = largest(clusterer.cluster(siblings));

        assertEquals("all ten results sit in one cluster", 10, count(largest, "b_algo"));
        assertInSiblingOrder(largest);
    }

    @Test
    public void theSitelinkResultsStayWithTheirListBehindTwoAnswerCards() {
        // Run 2: before the fix the three results with a sitelink row (E, E, F) formed their own
        // cluster of 3 and were dropped.
        List<RenderedContainerDescriptor> siblings = siblings(
                "b_ans", "li(div(div(div)),div)",
                "b_algo", A,
                "b_ans", "li(div(div(div),div(div,div)))",
                "b_algo", B, "b_algo", C, "b_algo", D, "b_algo", E, "b_algo", D,
                "b_algo", E, "b_algo", B, "b_algo", F, "b_algo", B,
                "b_ans", "li(div(div(div,div),div),div)",
                "b_ans", "li(div(div(span,a),div(ul)),div(h2(strong),ul(li,li,li)))",
                "b_msg", "li(a)",
                "b_pag", "li(nav(h4,ul(li,li,li,li,li,li)))");

        List<RenderedContainerDescriptor> largest = largest(clusterer.cluster(siblings));

        assertEquals(10, count(largest, "b_algo"));
        assertInSiblingOrder(largest);
    }

    @Test
    public void theResultClusterDoesNotDependOnWhatComesFirst() {
        List<RenderedContainerDescriptor> siblings = siblings(
                "b_ans", "li(div(div(div),div(div,div)),div)",
                "b_algo", A, "b_algo", B, "b_algo", C, "b_algo", D, "b_algo", E,
                "b_algo", F, "b_pag", "li(nav(h4,ul(li,li,li,li,li)))");
        List<RenderedContainerDescriptor> reversed =
                new ArrayList<RenderedContainerDescriptor>(siblings);
        Collections.reverse(reversed);

        assertEquals(ids(largest(clusterer.cluster(siblings))),
                sorted(ids(largest(clusterer.cluster(reversed)))));
    }

    @Test
    public void unrelatedShapesStayApart() {
        List<List<RenderedContainerDescriptor>> clusters = clusterer.cluster(siblings(
                "r", "li(h2(a),p)", "r", "li(h2(a),p)", "r", "li(h2(a),p)",
                "nav", "nav(ul(li,li,li,li,li,li))", "form", "form(input,button)"));

        assertEquals(3, clusters.size());
        assertEquals(3, clusters.get(0).size());
    }

    private static List<RenderedContainerDescriptor> siblings(String... classAndSignature) {
        List<RenderedContainerDescriptor> siblings = new ArrayList<RenderedContainerDescriptor>();
        for (int i = 0; i < classAndSignature.length; i += 2) {
            int index = i / 2;
            siblings.add(RenderedContainerDescriptor.builder(String.format("container-%04d", index))
                    .hierarchy("container-0000", Collections.<String>emptyList(), index, 3)
                    .semantics("li", "", Arrays.asList(classAndSignature[i]), "", "",
                            Collections.<String>emptyList())
                    .structure(new DomStructureSignature(classAndSignature[i + 1]), 0)
                    .build());
        }
        return siblings;
    }

    private static List<RenderedContainerDescriptor> largest(
            List<List<RenderedContainerDescriptor>> clusters) {
        List<RenderedContainerDescriptor> largest = clusters.get(0);
        for (List<RenderedContainerDescriptor> cluster : clusters) {
            if (cluster.size() > largest.size()) {
                largest = cluster;
            }
        }
        return largest;
    }

    private static int count(List<RenderedContainerDescriptor> cluster, String classToken) {
        int count = 0;
        for (RenderedContainerDescriptor member : cluster) {
            if (member.classTokens.contains(classToken)) {
                count++;
            }
        }
        return count;
    }

    private static void assertInSiblingOrder(List<RenderedContainerDescriptor> cluster) {
        for (int i = 1; i < cluster.size(); i++) {
            assertEquals("cluster keeps sibling order", true,
                    cluster.get(i - 1).siblingIndex < cluster.get(i).siblingIndex);
        }
    }

    private static List<String> ids(List<RenderedContainerDescriptor> cluster) {
        List<String> ids = new ArrayList<String>();
        for (RenderedContainerDescriptor member : cluster) {
            ids.add(member.containerId);
        }
        return ids;
    }

    private static List<String> sorted(List<String> ids) {
        List<String> sorted = new ArrayList<String>(ids);
        Collections.sort(sorted);
        return sorted;
    }
}
