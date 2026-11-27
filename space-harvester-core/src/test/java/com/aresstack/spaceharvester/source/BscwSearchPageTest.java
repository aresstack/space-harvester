package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Die Trefferseite der BSCW-Schnellsuche, mit erfundenen Daten im Aufbau der Weboberfläche. */
final class BscwSearchPageTest {

    /**
     * Eine Trefferzeile wie in der Weboberfläche: Kopfzeile mit Name, darunter der Ordnerpfad ({@code ind_sub}). Die
     * Pfadglieder kommen paarweise als Objektnummer und Name; das letzte Glied ist der Treffer selbst.
     */
    static String row(String folder, String id, String href, String name, List<String> path, String modified) {
        StringBuilder html = new StringBuilder();
        html.append("<tr class=\"highlight eachSecondObjectRow\" id=\"row").append(folder).append('_').append(id)
                .append("\">\n<td class=\"cInfo bTop\"><a href=\"").append(id).append("?op=inf\">i</a></td>\n")
                .append("<td class=\"box cName large bTop\"><div class=\"ovxy\"><span id=\"name").append(folder)
                .append('_').append(id).append("\"><a href=\"").append(href).append("\">").append(name)
                .append("</a> [0.1]</span></div></td>\n<td class=\"box cdate bTop\"><div class=\"ovxy\" "
                        + "data-func=\"tooltip\">").append(modified).append("</div></td>\n</tr>\n")
                .append("<tr class=\"sr1\" id=\"row").append(folder).append('_').append(id).append("\"><td>")
                .append("<table summary=\"\" class=\"ind_sub\"><tr><td class=\"box cDescr\"><div>");
        for (int i = 0; i + 1 < path.size(); i += 2) {
            html.append("<span><a href=\"").append(path.get(i)).append("\">").append(path.get(i + 1))
                    .append("</a></span> / ");
        }
        html.append("<span><a href=\"").append(href).append("\">").append(name).append("</a></span>")
                .append("</div></td></tr></table></td></tr>\n");
        return html.toString();
    }

    @Test
    void readsTokenAndHitsWithNamePathDateAndKind() {
        String html = "<input type=\"hidden\" name=\"bscw_v_post\" value=\"a+b/c\" />"
                + row("900", "11", "d11/Plan%20A.docx", "Plan &amp; Ablauf", List.of("2", "Team", "5", "Ablage"),
                "2026-03-01 10:00")
                + row("900", "12", "12", "Protokolle", List.of("2", "Team"), "2026-02-01 08:00")
                + row("900", "11", "d11/Plan%20A.docx", "Plan &amp; Ablauf", List.of("2", "Team"), "2026-03-01 10:00");

        assertThat(BscwSearchPage.token(html)).isEqualTo("a+b/c");
        List<BscwSearchPage.Hit> hits = BscwSearchPage.hits(html, 10);
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0)).isEqualTo(new BscwSearchPage.Hit("11", "Plan & Ablauf", true,
                List.of("Team", "Ablage"), "2026-03-01 10:00"));
        assertThat(hits.get(1).document()).isFalse();
        assertThat(hits.get(1).folders()).containsExactly("Team");
        assertThat(BscwSearchPage.hits(html, 1)).hasSize(1);
    }

    @Test
    void aPageWithoutTokenOrHitsIsEmpty() {
        assertThat(BscwSearchPage.token("<html></html>")).isNull();
        assertThat(BscwSearchPage.hits("<html></html>", 10)).isEmpty();
        assertThat(BscwSearchPage.unescape("a &#228; &#xFC; &unknown; &")).isEqualTo("a ä ü &unknown; &");
    }
}
