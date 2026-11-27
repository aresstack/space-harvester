package com.aresstack.spaceharvester.app.chat.artifact;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Jedes sichtbare Element einer Grafik bekommt eine feste Kennung (ADR 0045, Abschnitt 6); erfundene Grafiken. */
class SvgElementsTest {

    private static final String PLAN = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 50\">"
            + "<defs><linearGradient id=\"verlauf\"><stop offset=\"0\"/></linearGradient></defs>"
            + "<g id=\"beet\"><rect x=\"5\" y=\"5\" width=\"40\" height=\"20\" fill=\"#8c8\"/>"
            + "<text x=\"8\" y=\"18\">Bohnen <tspan>und Erbsen</tspan></text></g>"
            + "<circle cx=\"70\" cy=\"25\" r=\"10\" id=\"e7\"/></svg>";

    @Test
    void visibleElementsGetIdsAfterTheHighestExistingOne() {
        String svg = SvgElements.withIds(PLAN);
        assertThat(SvgElements.list(svg)).extracting(SvgElements.Item::id)
                .containsExactly("beet", "e8", "e9", "e7");
        assertThat(svg).as("Bausteine und Textteile bleiben ohne Kennung").doesNotContain("<stop id")
                .doesNotContain("<tspan id").contains("id=\"verlauf\"");
        assertThat(SvgElements.withIds(svg)).as("stabil").isEqualTo(svg);
        assertThat(SvgElements.listing(svg)).contains("beet Gruppe")
                .contains("  e8 Rechteck · x=5 y=5 width=40 height=20 fill=#8c8")
                .contains("  e9 Text · „Bohnen und Erbsen“").contains("e7 Kreis · cx=70 cy=25 r=10");
    }

    @Test
    void anUnreadableSvgStaysAsItIs() {
        assertThat(SvgElements.withIds("<svg><rect></svg>")).isEqualTo("<svg><rect></svg>");
        assertThat(SvgElements.withIds("<html/>")).isEqualTo("<html/>");
        assertThat(SvgElements.list("kaputt")).isEmpty();
        String doctype = "<!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><svg>&x;</svg>";
        assertThat(SvgElements.withIds(doctype)).as("kein DTD, keine externen Bezüge").isEqualTo(doctype);
    }

    @Test
    void replacingKeepsTheIdAndDeletingRemovesTheElement() {
        String svg = SvgElements.withIds(PLAN);
        String changed = SvgElements.replace(svg, "e8", "<rect x=\"5\" y=\"5\" width=\"60\" height=\"20\"/>"
                + "<line x1=\"0\" y1=\"0\" x2=\"9\" y2=\"9\"/>");
        assertThat(SvgElements.element(changed, "e8")).contains("width=\"60\"");
        assertThat(SvgElements.list(changed)).extracting(SvgElements.Item::id)
                .containsExactly("beet", "e8", "e10", "e9", "e7");
        String deleted = SvgElements.replace(changed, "beet", "");
        assertThat(SvgElements.list(deleted)).extracting(SvgElements.Item::id).containsExactly("e7");
        assertThatThrownBy(() -> SvgElements.replace(svg, "e99", "")).hasMessageContaining("e99");
        assertThatThrownBy(() -> SvgElements.replace(svg, "e8", "<rect")).hasMessageContaining("kein gültiges");
    }
}
