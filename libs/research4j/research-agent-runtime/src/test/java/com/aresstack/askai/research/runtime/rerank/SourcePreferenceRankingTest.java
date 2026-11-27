package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.SearchResultSiteLink;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The mission's source preference weighs the selected hits of a planned query; it never drops one. */
public class SourcePreferenceRankingTest {

    private static RerankedSearchResultCandidate hit(int rank, String url) {
        SearchResultCandidate c = new SearchResultCandidate("c" + rank, "s", url, url, "t" + rank, "s", "", rank,
                "r", "b", 1.0, 1.0, Collections.<SearchResultSiteLink>emptyList());
        return new RerankedSearchResultCandidate(c, 10 - rank, rank);
    }

    private static List<String> urls(List<RerankedSearchResultCandidate> ranked) {
        List<String> out = new ArrayList<String>();
        for (RerankedSearchResultCandidate r : ranked) {
            out.add(r.candidate.resolvedTargetUrl);
        }
        return out;
    }

    private static final SourcePreferenceRanking RUN3 = new SourcePreferenceRanking(
            Arrays.asList("dguv.de", "baua.de", ".bund.de"), Arrays.asList("/blog", "/produkt"), 3);

    @Test
    public void aGuidelineRankedFifthOpensBeforeAVendorBlogRankedSecond() {
        List<RerankedSearchResultCandidate> ranked = Arrays.asList(
                hit(1, "https://wiki.example/wearables"),
                hit(2, "https://vendor.example/blog/wearables-baustelle"),
                hit(3, "https://news.example/a"),
                hit(4, "https://other.example/b"),
                hit(5, "https://publikationen.dguv.de/regelwerk/123"));
        List<RerankedSearchResultCandidate> out = RUN3.apply(ranked);
        assertEquals(Arrays.asList("https://wiki.example/wearables", "https://publikationen.dguv.de/regelwerk/123",
                "https://news.example/a", "https://other.example/b",
                "https://vendor.example/blog/wearables-baustelle"), urls(out));
        assertEquals(5, out.size()); // nothing dropped
        assertEquals(2, out.get(1).rerankRank);
    }

    @Test
    public void hostSuffixesAndSubdomainsMatchButLookalikesDoNot() {
        assertTrue(RUN3.preferred(hit(1, "https://www.bsi.bund.de/x").candidate));
        assertTrue(RUN3.preferred(hit(1, "https://www.baua.de/DE/x").candidate));
        assertFalse(RUN3.preferred(hit(1, "https://notdguv.de/x").candidate));
        assertFalse(RUN3.demoted(hit(1, "https://www.dguv.de/blog/x").candidate)); // a preferred host is never demoted
    }

    /** The evidence pack of a section weighs its sources by their URLs with the same rule. */
    @Test
    public void aSourceUrlMovesUpWhenPreferredAndDownWhenAVendorPage() {
        assertEquals(-3, RUN3.shiftOf("https://publikationen.dguv.de/widgets/pdf/download/article/4517"));
        assertEquals(-3, RUN3.shiftOf("https://www.bfdi.bund.de/DE/Home/home_node.html"));
        assertEquals(-3, RUN3.shiftOf("https://www.dguv.de/blog/x")); // a preferred host is never demoted
        assertEquals(3, RUN3.shiftOf("https://built-smart-hub.com/blog/wearables"));
        assertEquals(0, RUN3.shiftOf("https://notdguv.de/x"));
        assertEquals(0, RUN3.shiftOf(""));
        assertEquals(0, RUN3.shiftOf(null));
        assertEquals(0, new SourcePreferenceRanking(Arrays.asList("dguv.de"), null, 0).shiftOf("https://dguv.de/x"));
    }

    @Test
    public void noShiftOrNoListsLeavesTheOrderAsItIs() {
        List<RerankedSearchResultCandidate> ranked = Arrays.asList(hit(1, "https://a.example"),
                hit(2, "https://dguv.de/x"));
        assertSame(ranked, new SourcePreferenceRanking(Arrays.asList("dguv.de"), null, 0).apply(ranked));
        assertSame(ranked, SourcePreferenceRanking.NONE.apply(ranked));
    }
}
