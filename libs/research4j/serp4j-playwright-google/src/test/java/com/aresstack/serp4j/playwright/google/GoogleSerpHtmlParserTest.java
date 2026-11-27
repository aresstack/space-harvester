package com.aresstack.serp4j.playwright.google;

import com.aresstack.serp4j.testkit.fixture.Fixture;
import com.aresstack.serp4j.testkit.fixture.Fixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleSerpHtmlParserTest {

    private final GoogleSerpHtmlParser parser = new GoogleSerpHtmlParser();

    static String html(String name) {
        Fixture fixture = Fixtures.loadRecorded("google", name);
        return fixture.getText();
    }

    @Test
    void recordedBrowserPageYieldsAllOrganicResultsInOrder() {
        GoogleSerpPage page = parser.parse(html("browser-web-de.html"));

        assertThat(page.isStructureRecognized()).isTrue();
        assertThat(page.getPageObstacle()).isEqualTo(GoogleObstacle.NONE);
        List<GoogleSerpPage.Organic> hits = page.getOrganicResults();
        assertThat(hits).extracting(GoogleSerpPage.Organic::getTitle).containsExactly(
                "Java",
                "Official Elasticsearch Java Client",
                "co.elastic.clients » elasticsearch-java",
                "Elasticsearch Java Client 9.2.0",
                "Guide to Elasticsearch in Java",
                "Java Client",
                "Introduction | Elasticsearch Java API Client [8.19]",
                "New Elasticsearch Java Client: Getting Started, Usage ...",
                "elasticsearch-java 9.5.3 javadoc (co.elastic.clients)");
        assertThat(hits).extracting(GoogleSerpPage.Organic::getRank).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertThat(hits).allSatisfy(hit -> {
            assertThat(hit.getUrl()).startsWith("https://www.google.com/goto?url=ANONYMIZED");
            assertThat(GoogleUrlBuilder.isAiModeUrl(hit.getUrl())).isFalse();
        });
        // "Diese Seite übersetzen" links are not results
        assertThat(hits).extracting(GoogleSerpPage.Organic::getUrl).doesNotHaveDuplicates()
                .doesNotContain("https://www.google.com/goto?url=ANONYMIZED2&gsessionid=ANONYMIZED");
    }

    @Test
    void firstResultCarriesBreadcrumbSiteNameSnippetAndSitelinks() {
        GoogleSerpPage.Organic first = parser.parse(html("browser-web-de.html")).getOrganicResults().get(0);

        assertThat(first.getSiteName()).isEqualTo("Elastic.co");
        assertThat(first.getDisplayUrl()).isEqualTo("https://www.elastic.co › clients");
        assertThat(first.getSnippet()).startsWith("The Elasticsearch Java client is forward compatible;");
        assertThat(first.isVideo()).isFalse();
        assertThat(first.getSiteLinks()).extracting(GoogleSerpPage.Link::getText)
                .containsExactly("Using the Java API client", "Getting started");
        assertThat(first.getSiteLinks().get(1).getDescription())
                .isEqualTo("This page guides you through the installation process of the Java ...");
        assertThat(first.getSiteLinks().get(0).getUrl())
                .isEqualTo("https://www.google.com/goto?url=ANONYMIZED3&gsessionid=ANONYMIZED");
    }

    @Test
    void dateIsSplitFromTheSnippetAndVideosAreMarked() {
        List<GoogleSerpPage.Organic> hits = parser.parse(html("browser-web-de.html")).getOrganicResults();

        GoogleSerpPage.Organic baeldung = hits.get(4);
        assertThat(baeldung.getDateText()).isEqualTo("08.01.2024");
        assertThat(baeldung.getSnippet()).startsWith("In this article, we'll dive into");

        GoogleSerpPage.Organic video = hits.get(7);
        assertThat(video.isVideo()).isTrue();
        assertThat(video.getSiteName()).isEqualTo("YouTube · Official Elastic Community");
        assertThat(video.getSnippet()).startsWith("Elastic has recently released a new Java client");
    }

    @Test
    void relatedSearchesAndNextPageAreRecognisedAndNoBlockIsLeftOver() {
        GoogleSerpPage page = parser.parse(html("browser-web-de.html"));

        assertThat(page.getRelatedQueries()).hasSize(8);
        assertThat(page.getRelatedQueries().get(0).getText()).isEqualTo("Elasticsearch-java maven");
        assertThat(page.getRelatedQueries().get(0).getUrl()).startsWith("https://www.google.com/search?")
                .contains("q=Elasticsearch-java+maven");
        assertThat(page.hasNextPage()).isTrue();
        assertThat(page.getUnrecognizedBlocks()).isZero();
    }

    @Test
    void recordedCookieFreeHttpAnswerIsJavascriptRequired() {
        GoogleSerpPage page = parser.parse(html("http-enablejs-de.html"));

        assertThat(page.getOrganicResults()).isEmpty();
        assertThat(page.isStructureRecognized()).isFalse();
        assertThat(page.getPageObstacle()).isEqualTo(GoogleObstacle.JAVASCRIPT_REQUIRED);
    }

    @Test
    void changedLayoutIsReportedAsUnrecognisedNotAsEmptyResult() {
        GoogleSerpPage page = parser.parse("<html><body><div id=\"main\"><p>neues Layout</p></div></body></html>");

        assertThat(page.isStructureRecognized()).isFalse();
        assertThat(page.getOrganicResults()).isEmpty();
        assertThat(parser.parse(null).isStructureRecognized()).isFalse();
    }

    @Test
    void containerWithUnparseableResultMarkupIsNotRecognised() {
        GoogleSerpPage page = parser.parse("<html><body><div id=\"rso\"><div class=\"MjjYud\">"
                + "<div><h3>Titel</h3><span><a href=\"/goto?url=x\">Link</a></span></div></div></div></body></html>");

        assertThat(page.getOrganicResults()).isEmpty();
        assertThat(page.isStructureRecognized()).isFalse();
    }

    @Test
    void emptyContainerIsARecognisedEmptyResult() {
        GoogleSerpPage page = parser.parse("<html><body><div id=\"rso\"></div></body></html>");

        assertThat(page.isStructureRecognized()).isTrue();
        assertThat(page.getOrganicResults()).isEmpty();
    }

    @Test
    void classicUrlWrapperIsUnwrappedAndJavascriptLinksAreDropped() {
        assertThat(GoogleSerpHtmlParser.absolute("/url?q=https://example.org/a%3Fb%3D1&sa=U"))
                .isEqualTo("https://example.org/a?b=1");
        assertThat(GoogleSerpHtmlParser.absolute("https://example.org/x")).isEqualTo("https://example.org/x");
        assertThat(GoogleSerpHtmlParser.absolute("javascript:void(0)")).isNull();
        assertThat(GoogleSerpHtmlParser.absolute("#")).isNull();
    }
}
