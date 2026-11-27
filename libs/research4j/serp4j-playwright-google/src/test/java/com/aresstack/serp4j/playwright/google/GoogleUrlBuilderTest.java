package com.aresstack.serp4j.playwright.google;

import com.aresstack.serp4j.core.model.Paging;
import com.aresstack.serp4j.core.model.SearchLocale;
import com.aresstack.serp4j.core.model.SearchRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleUrlBuilderTest {

    @Test
    void encodesQueryAndNeverSetsUdm() {
        String url = GoogleUrlBuilder.defaults().build(SearchRequest.builder("c++ & java öäü?").build());

        assertThat(url).isEqualTo("https://www.google.com/search?q=c%2B%2B+%26+java+%C3%B6%C3%A4%C3%BC%3F");
        assertThat(url).doesNotContain("udm");
        assertThat(GoogleUrlBuilder.isAiModeUrl(url)).isFalse();
    }

    @Test
    void localeAndOffsetBecomeHlGlStart() {
        SearchRequest request = SearchRequest.builder("elasticsearch java client")
                .locale(SearchLocale.builder().languageCode("de").countryCode("DE").build())
                .paging(Paging.of(10, 10))
                .build();

        assertThat(new GoogleUrlBuilder(GoogleUrlBuilder.DEFAULT_BASE_URL, "en", "us").build(request))
                .isEqualTo("https://www.google.com/search?q=elasticsearch+java+client&hl=de&gl=de&start=10");
    }

    @Test
    void defaultsApplyWithoutLocale() {
        assertThat(new GoogleUrlBuilder(GoogleUrlBuilder.DEFAULT_BASE_URL, "de", "DE")
                .build(SearchRequest.builder("x").build()))
                .isEqualTo("https://www.google.com/search?q=x&hl=de&gl=de");
    }

    @Test
    void aiModeGuardRecognisesUdm50() {
        assertThat(GoogleUrlBuilder.isAiModeUrl("https://www.google.com/search?q=x&udm=50")).isTrue();
        assertThat(GoogleUrlBuilder.isAiModeUrl("https://www.google.com/search?udm=50&q=x")).isTrue();
        assertThat(GoogleUrlBuilder.isAiModeUrl("https://www.google.com/search?q=udm%3D50")).isFalse();
    }

    @Test
    void blankQueryIsRejected() {
        assertThatThrownBy(() -> GoogleUrlBuilder.defaults().build(SearchRequest.builder(" ").build()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
