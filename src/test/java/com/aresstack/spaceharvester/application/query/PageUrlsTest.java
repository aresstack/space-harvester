package com.aresstack.spaceharvester.application.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

/** Seiten-IDs aus Confluence-URLs. */
class PageUrlsTest {

    @Test
    void readsTheIdFromEveryFormThatCarriesIt() {
        assertThat(PageUrls.pageId("https://wiki.example.org/pages/viewpage.action?pageId=123456")).contains("123456");
        assertThat(PageUrls.pageId("https://wiki.example.org/confluence/pages/viewpage.action?spaceKey=DEV&pageId=42"))
                .contains("42");
        assertThat(PageUrls.pageId("https://wiki.example.org/spaces/DEV/pages/987/Architektur+%C3%9Cbersicht"))
                .contains("987");
        assertThat(PageUrls.pageId("https://wiki.example.org/wiki/spaces/DEV/pages/987")).contains("987");
        assertThat(PageUrls.pageId("https://wiki.example.org/x/AgAB")).contains("65538");
        assertThat(PageUrls.pageId("https://wiki.example.org/x/Fc1bBw")).contains("123456789");
    }

    @Test
    void refusesFormsWithoutIdAndForeignSchemes() {
        assertThat(PageUrls.pageId("https://wiki.example.org/display/DEV/Architektur")).isEmpty();
        assertThat(PageUrls.pageId("https://wiki.example.org/pages/viewpage.action?pageId=abc")).isEmpty();
        assertThat(PageUrls.pageId("https://wiki.example.org/pages/viewpage.action?xpageId=1")).isEmpty();
        assertThat(PageUrls.pageId("/pages/viewpage.action?pageId=1")).isEmpty();
        assertThat(PageUrls.pageId("file:///etc/passwd?pageId=1")).isEmpty();
        assertThat(PageUrls.pageId("https://wiki.example.org/pages/viewpage.action?pageId=0")).isEmpty();
        assertThat(PageUrls.pageId("https://wiki.example.org/pages/viewpage.action?pageId=99999999999999999999"))
                .isEmpty();
        assertThat(PageUrls.pageId("nicht eine url")).isEmpty();
        assertThat(PageUrls.pageId(null)).isEmpty();
    }

    @Test
    void sameServerComparesSchemeHostAndPort() {
        URI known = URI.create("https://wiki.example.org/pages/viewpage.action?pageId=1");

        assertThat(PageUrls.sameServer("https://WIKI.example.org:443/x/AQ", known)).isTrue();
        assertThat(PageUrls.sameServer("http://wiki.example.org/x/AQ", known)).isFalse();
        assertThat(PageUrls.sameServer("https://other.example.org/x/AQ", known)).isFalse();
        assertThat(PageUrls.sameServer("https://wiki.example.org:8443/x/AQ", known)).isFalse();
    }
}
