package com.aresstack.spaceharvester.application.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Eingegebene Adressen, in die beim Kopieren Unicode-Bindestriche oder unsichtbare Zeichen geraten sind. */
class PageAddressesNormalizeTest {

    @Test
    void unicodeDashesInTheHostBecomeHyphens() {
        for (char dash : new char[] {'‐', '‑', '‒', '–', '—', '−', '﹣', '－'}) {
            assertThat(PageAddresses.normalize("https://wiki" + dash + "intern.example.org/spaces/DEV/pages/123/Plan"))
                    .as("U+%04X", (int) dash)
                    .isEqualTo("https://wiki-intern.example.org/spaces/DEV/pages/123/Plan");
        }
    }

    @Test
    void pathTitleAndPercentEncodingStayAsTheyAre() {
        assertThat(PageAddresses.normalize(" https://wiki‑intern.example.org:8443/display/DEV/Plan–B?x=1#Ziel "))
                .isEqualTo("https://wiki-intern.example.org:8443/display/DEV/Plan–B?x=1#Ziel");
        assertThat(PageAddresses.normalize("https://wiki.example.org/spaces/DEV/pages/7/Nachtl%C3%A4ufe"))
                .isEqualTo("https://wiki.example.org/spaces/DEV/pages/7/Nachtl%C3%A4ufe");
    }

    @Test
    void invisibleCharactersDisappearAndUmlautHostsBecomeAscii() {
        assertThat(PageAddresses.normalize("​https://wi­ki.example.org/display/DEV﻿"))
                .isEqualTo("https://wiki.example.org/display/DEV");
        assertThat(PageAddresses.normalize("https://nutzer@wiki.bücher.example/display/DEV"))
                .isEqualTo("https://nutzer@wiki.xn--bcher-kva.example/display/DEV");
    }

    @Test
    void otherAddressesOnlyLoseOuterWhitespace() {
        assertThat(PageAddresses.normalize("  confluence:wiki.example.org/page/7 ")).isEqualTo(
                "confluence:wiki.example.org/page/7");
        assertThat(PageAddresses.normalize("space-harvester://wiki/page/7?version=3"))
                .isEqualTo("space-harvester://wiki/page/7?version=3");
        assertThat(PageAddresses.normalize(null)).isNull();
    }
}
