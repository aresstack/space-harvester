package com.aresstack.serp4j.playwright.google;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Neutral parse result of one classic Google result page; independent of jsoup. Immutable. */
public final class GoogleSerpPage {

    private final List<Organic> organicResults;
    private final List<Link> relatedQueries;
    private final boolean structureRecognized;
    private final boolean nextPage;
    private final int unrecognizedBlocks;
    private final GoogleObstacle pageObstacle;

    GoogleSerpPage(List<Organic> organicResults, List<Link> relatedQueries, boolean structureRecognized,
                   boolean nextPage, int unrecognizedBlocks, GoogleObstacle pageObstacle) {
        this.organicResults = Collections.unmodifiableList(new ArrayList<Organic>(organicResults));
        this.relatedQueries = Collections.unmodifiableList(new ArrayList<Link>(relatedQueries));
        this.structureRecognized = structureRecognized;
        this.nextPage = nextPage;
        this.unrecognizedBlocks = unrecognizedBlocks;
        this.pageObstacle = Objects.requireNonNull(pageObstacle, "pageObstacle");
    }

    /** Organic and video results in page order (ads are skipped). */
    public List<Organic> getOrganicResults() {
        return organicResults;
    }

    /** "Related searches" ({@code Wird auch oft gesucht}). */
    public List<Link> getRelatedQueries() {
        return relatedQueries;
    }

    /**
     * Whether the page is a result page the parser understands: the container ({@code #search} / {@code #rso}) exists
     * and, if no result was extracted, it contains no result markup either ({@code h3}, linked result blocks).
     */
    public boolean isStructureRecognized() {
        return structureRecognized;
    }

    public boolean hasNextPage() {
        return nextPage;
    }

    /** Result blocks with content that the parser did not understand (SERP features not mapped yet). */
    public int getUnrecognizedBlocks() {
        return unrecognizedBlocks;
    }

    /** Obstacle markers found in the HTML itself. */
    public GoogleObstacle getPageObstacle() {
        return pageObstacle;
    }

    @Override
    public String toString() {
        return "GoogleSerpPage{organic=" + organicResults.size() + ", related=" + relatedQueries.size()
                + ", recognized=" + structureRecognized + ", obstacle=" + pageObstacle + '}';
    }

    /** A link with text. */
    public static final class Link {
        private final String text;
        private final String url;
        private final String description;

        Link(String text, String url, String description) {
            this.text = Objects.requireNonNull(text, "text");
            this.url = Objects.requireNonNull(url, "url");
            this.description = description;
        }

        public String getText() {
            return text;
        }

        public String getUrl() {
            return url;
        }

        /** Short description (sitelinks); may be {@code null}. */
        public String getDescription() {
            return description;
        }

        @Override
        public String toString() {
            return text + " <" + url + '>';
        }
    }

    /** One organic (or video) result. */
    public static final class Organic {
        private final int rank;
        private final String title;
        private final String url;
        private final String displayUrl;
        private final String siteName;
        private final String snippet;
        private final String dateText;
        private final boolean video;
        private final List<Link> siteLinks;

        Organic(int rank, String title, String url, String displayUrl, String siteName, String snippet,
                String dateText, boolean video, List<Link> siteLinks) {
            this.rank = rank;
            this.title = Objects.requireNonNull(title, "title");
            this.url = Objects.requireNonNull(url, "url");
            this.displayUrl = displayUrl;
            this.siteName = siteName;
            this.snippet = snippet;
            this.dateText = dateText;
            this.video = video;
            this.siteLinks = Collections.unmodifiableList(new ArrayList<Link>(siteLinks));
        }

        /** 1-based rank among the organic results of the page. */
        public int getRank() {
            return rank;
        }

        public String getTitle() {
            return title;
        }

        /** Absolute target URL; may be a Google {@code /goto} redirect that still needs resolving. */
        public String getUrl() {
            return url;
        }

        /** Breadcrumb shown under the title ({@code https://www.elastic.co › clients}); may be {@code null}. */
        public String getDisplayUrl() {
            return displayUrl;
        }

        public String getSiteName() {
            return siteName;
        }

        public String getSnippet() {
            return snippet;
        }

        /** Date text in front of the snippet ({@code 08.01.2024}); may be {@code null}. */
        public String getDateText() {
            return dateText;
        }

        public boolean isVideo() {
            return video;
        }

        public List<Link> getSiteLinks() {
            return siteLinks;
        }

        @Override
        public String toString() {
            return "#" + rank + ' ' + title + " <" + url + '>';
        }
    }
}
