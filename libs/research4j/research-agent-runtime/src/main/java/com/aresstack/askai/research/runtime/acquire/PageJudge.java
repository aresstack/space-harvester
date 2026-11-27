package com.aresstack.askai.research.runtime.acquire;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The page worker's judgement of one opened page (plan T4, section 2.2): after the deterministic gates (blacklist,
 * relevance, scope) have let a page through, a model reads it and says whether it is useful, which of the
 * candidate links are worth opening and what to tell the orchestrator or another worker. Only a useful page becomes
 * a source; a page judged not useful stays parked and hands no link on. {@code null} means "no judgement" (the
 * model was unavailable): the funnel then decides as it always did.
 */
public interface PageJudge {

    /** What the worker sees: the query, the page and the candidate links (already chosen by relevance). */
    final class Page {
        private final String query;
        private final String url;
        private final String title;
        private final String text;
        private final List<String> links;

        public Page(String query, String url, String title, String text, List<String> links) {
            this.query = query == null ? "" : query;
            this.url = url == null ? "" : url;
            this.title = title == null ? "" : title;
            this.text = text == null ? "" : text;
            this.links = links == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(links));
        }

        public String getQuery() {
            return query;
        }

        public String getUrl() {
            return url;
        }

        public String getTitle() {
            return title;
        }

        public String getText() {
            return text;
        }

        /** The candidate links, numbered 1..n in this order in the worker's prompt. */
        public List<String> getLinks() {
            return links;
        }
    }

    /** The worker's verdict: useful or not, why, and the 1-based numbers of the links to open. */
    final class Verdict {
        private final boolean useful;
        private final String reason;
        private final List<Integer> follow;
        private final List<String> searches;

        public Verdict(boolean useful, String reason, List<Integer> follow) {
            this(useful, reason, follow, null);
        }

        /** @param searches new search queries the page suggests; they go into the search plan at once */
        public Verdict(boolean useful, String reason, List<Integer> follow, List<String> searches) {
            this.searches = searches == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(searches));
            this.useful = useful;
            this.reason = reason == null ? "" : reason;
            this.follow = follow == null ? Collections.<Integer>emptyList()
                    : Collections.unmodifiableList(new ArrayList<Integer>(follow));
        }

        public boolean isUseful() {
            return useful;
        }

        public String getReason() {
            return reason;
        }

        public List<Integer> getFollow() {
            return follow;
        }

        /** The new search queries the page suggests (empty when none). */
        public List<String> getSearches() {
            return searches;
        }

        /** The chosen links out of {@code links} (numbers outside the list are ignored). */
        public List<String> select(List<String> links) {
            List<String> chosen = new ArrayList<String>();
            for (Integer number : follow) {
                if (number != null && number >= 1 && number <= links.size()
                        && !chosen.contains(links.get(number - 1))) {
                    chosen.add(links.get(number - 1));
                }
            }
            return chosen;
        }
    }

    /** @return the verdict, or {@code null} when no judgement could be made */
    Verdict judge(Page page);
}
