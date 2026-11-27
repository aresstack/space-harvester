package com.aresstack.serp4j.playwright.google;

import com.aresstack.serp4j.core.model.SearchRequest;
import com.aresstack.serp4j.core.model.SearchResponse;
import com.aresstack.serp4j.core.model.id.ProviderId;
import com.aresstack.serp4j.core.model.metadata.SearchWarning;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleSerpMapperTest {

    private final GoogleSerpMapper mapper = new GoogleSerpMapper(ProviderId.of("google-browser"));
    private final GoogleSerpPage page = new GoogleSerpHtmlParser()
            .parse(GoogleSerpHtmlParserTest.html("browser-web-de.html"));

    @Test
    void responseIsCompleteWithoutWarnings() {
        SearchResponse response = mapper.map(page, SearchRequest.builder("q").build(), "https://www.google.com/search",
                null, Instant.EPOCH, Instant.EPOCH, Collections.<SearchWarning>emptyList());

        assertThat(response.getMetadata().get().isComplete()).isTrue();
    }

    @Test
    void partialResultsWarningMarksResponseIncomplete() {
        SearchResponse response = mapper.map(page, SearchRequest.builder("q").build(), "https://www.google.com/search",
                null, Instant.EPOCH, Instant.EPOCH, Collections.singletonList(SearchWarning.of(
                        SearchWarning.PARTIAL_RESULTS, "snapshot truncated", ProviderId.of("google-browser"))));

        assertThat(response.getMetadata().get().isComplete()).isFalse();
    }
}
