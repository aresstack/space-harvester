package com.aresstack.spaceharvester.deck.betaview.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BetaViewHtmlTest {

    @Test
    void readsPageLinesWithEscapesAndBrackets() {
        String json = "{\"page\":1,\"text\":[\"ZEILE 1\",\"WERT [A]\\/B \\\"x\\\"\",\"\\u00c4NDERUNG\"],\"more\":[1]}";
        assertThat(BetaViewHtml.pageText(json)).isEqualTo("ZEILE 1\nWERT [A]/B \"x\"\nÄNDERUNG\n");
    }

    @Test
    void withoutTextTheAnswerStays() {
        assertThat(BetaViewHtml.pageText("{\"error\":\"x\"}")).isEqualTo("{\"error\":\"x\"}");
    }

    @Test
    void readsHiddenInputsAndCsrf() {
        assertThat(BetaViewHtml.hiddenInputs("<input type='hidden' name='a' value='1'><input type=\"hidden\" "
                + "value=\"2\" name=\"b\">")).containsEntry("a", "1").containsEntry("b", "2");
        assertThat(BetaViewHtml.csrfToken("{\"csrfToken\":{\"field\":\"csrfToken\",\"name\":\"csrfToken\","
                + "\"value\":\"abc\"}}")).contains("abc");
    }

    @Test
    void recognisesTheLoginForm() {
        assertThat(BetaViewHtml.looksLikeLoginForm("<form action=\"login.action\"><input name=\"password\" "
                + "type=\"password\"></form>")).isTrue();
        assertThat(BetaViewHtml.looksLikeLoginForm("<h1>Willkommen</h1>")).isFalse();
    }
}
