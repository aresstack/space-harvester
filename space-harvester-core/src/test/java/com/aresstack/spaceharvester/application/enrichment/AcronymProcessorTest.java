package com.aresstack.spaceharvester.application.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.List;
import org.junit.jupiter.api.Test;

class AcronymProcessorTest {

    private final AcronymProcessor processor = new AcronymProcessor();

    @Test
    void findsAcronymsWithCountsSectionsAndMatchingExpansions() {
        String text = "Intro\nDie Application Programming Interface (API) nutzt REST.\nBetrieb\nDie API und zwei APIs.";
        PageText page = new PageText("t/v1", text, List.of(new PageText.Section("Intro", 1, 0, 39),
                new PageText.Section("Betrieb", 2, text.indexOf("Betrieb"), text.length())));

        EnrichmentOutput output = processor.process(page);

        int first = text.indexOf("API");
        int second = text.indexOf("API", text.indexOf("Betrieb"));
        int plural = text.indexOf("APIs");
        assertThat(output.payloadJson()).isEqualTo("{\"acronyms\":["
                + "{\"acronym\":\"API\",\"normalized\":\"API\",\"count\":3,"
                + "\"expansion\":\"Application Programming Interface\","
                + "\"expansions\":[\"Application Programming Interface\"],"
                + "\"section\":\"Intro\",\"charStart\":" + first + ",\"occurrences\":[{\"start\":" + first
                + ",\"end\":" + (first + 3) + "},{\"start\":" + second + ",\"end\":" + (second + 3) + "},{\"start\":"
                + plural + ",\"end\":" + (plural + 4) + "}]},"
                + "{\"acronym\":\"REST\",\"normalized\":\"REST\",\"count\":1,\"expansion\":null,\"expansions\":[],"
                + "\"section\":\"Intro\",\"charStart\":" + text.indexOf("REST") + ",\"occurrences\":[{\"start\":"
                + text.indexOf("REST") + ",\"end\":" + (text.indexOf("REST") + 4) + "}]}],\"identifiers\":[]}");
        assertThat(output.warnings()).isEmpty();
    }

    @Test
    void takesAnExpansionAfterTheAcronymOnlyWhenTheInitialsMatch() {
        assertThat(processor.process(plain("Das SSO (Single Sign-On) und das CI (keine Ahnung).")).payloadJson())
                .contains("{\"acronym\":\"SSO\",\"normalized\":\"SSO\",\"count\":1,\"expansion\":\"Single Sign-On\"")
                .contains("{\"acronym\":\"CI\",\"normalized\":\"CI\",\"count\":1,\"expansion\":null");
    }

    @Test
    void ignoresWordsThatAreNotAcronyms() {
        assertThat(processor.process(plain("Ein Satz mit A, Ab, aB, ÄNDERUNGSPROTOKOLLE und 2026.")).payloadJson())
                .isEqualTo("{\"acronyms\":[],\"identifiers\":[]}");
    }

    @Test
    void acceptsDigitsAndUmlauts() {
        assertThat(processor.process(plain("S3 ist kein Akronym, aber K8S und ÜBV sind welche.")).payloadJson())
                .contains("\"acronym\":\"K8S\"").contains("\"acronym\":\"ÜBV\"").doesNotContain("\"S3\"");
    }

    /** Wie {@code AcronymForms} im EAC: gemischte Schreibung, wenn die Großbuchstaben mindestens die Hälfte stellen. */
    @Test
    void mixedCaseCountsWhenCapitalsAreAtLeastHalfTheLetters() {
        PageAcronyms acronyms = read("Die Muster GmbH baut IoT-Geräte, aber kein Ab oder iPhone.");

        assertThat(acronyms.acronyms()).extracting(PageAcronyms.Acronym::acronym).containsExactly("GmbH", "IoT");
    }

    @Test
    void dottedAcronymsAreOneAcronymWithoutDots() {
        String text = "Die U.S.A. und die USA sind dasselbe.";
        EnrichmentOutput output = processor.process(plain(text));

        assertThat(output.payloadJson()).contains("\"normalized\":\"USA\",\"count\":2");
    }

    @Test
    void identifiersWithManyDigitsOrUnderscoresAreTheirOwnKind() {
        String text = "Der Wert MAX_SIZE gilt für ABC-1234 und X12Y7, B2B bleibt ein Akronym.";
        EnrichmentOutput output = processor.process(plain(text));

        assertThat(output.payloadJson()).contains("\"acronym\":\"B2B\"")
                .contains("\"identifier\":\"MAX_SIZE\"").contains("\"identifier\":\"X12Y7\"")
                .doesNotContain("\"acronym\":\"MAX\"").doesNotContain("\"acronym\":\"SIZE\"");
    }

    @Test
    void codeAddressesFileNamesAndShoutingLinesAreLeftOut() {
        String text = "Siehe `API_KEY` und `CSV`.\n```\nSELECT FROM DUAL\n```\nhttps://example.org/REST/API und "
                + "info@ZDV.example.org, Datei REPORT.pdf.\nDIESE ZEILE IST LAUT UND NENNT KEIN ABC\nNur LAN zählt.";
        PageAcronyms acronyms = read(text);

        assertThat(acronyms.acronyms()).extracting(PageAcronyms.Acronym::acronym).containsExactly("LAN");
    }

    @Test
    void everyDistinctExpansionIsKept() {
        String text = "Die Projektgruppe (PG) tagt. Die PG (Programmiergruppe) auch.";
        EnrichmentOutput output = processor.process(plain(text));

        assertThat(output.payloadJson()).contains("\"expansion\":\"Projektgruppe\","
                + "\"expansions\":[\"Projektgruppe\",\"Programmiergruppe\"]");
    }

    @Test
    void limitsTheNumberOfEntriesWithAWarning() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < AcronymProcessor.MAX_ACRONYMS + 5; i++) {
            text.append((char) ('A' + i / 26 % 26)).append((char) ('A' + i % 26)).append("X und ");
        }
        EnrichmentOutput output = processor.process(plain(text.toString()));

        assertThat(output.payloadJson().split("\"acronym\":")).hasSize(AcronymProcessor.MAX_ACRONYMS + 1);
        assertThat(output.warnings()).containsExactly("Nur die ersten 500 von 505 Akronymen");
    }

    @Test
    void occurrencesPointIntoTheTextIncludingUmlautsAndSupplementaryCharacters() {
        String text = "Grüße 😀 an ÜBV, die ÜBVs und \"ÜBV\"; auch ÜBVX. Ein HTTP-Aufruf nutzt HTTP.";
        PageAcronyms acronyms = read(text);

        assertThat(acronyms.marks()).extracting(mark -> text.substring(mark.occurrence().start(),
                mark.occurrence().end())).containsExactly("ÜBV", "ÜBVs", "ÜBV", "ÜBVX", "HTTP", "HTTP");
        assertThat(acronyms.acronyms()).extracting(PageAcronyms.Acronym::acronym, PageAcronyms.Acronym::count)
                .containsExactly(tuple("HTTP", 2), tuple("ÜBV", 3), tuple("ÜBVX", 1));
    }

    @Test
    void limitsTheNumberOfOccurrencesButKeepsCounting() {
        // Mit Kleinwörtern, sonst wäre die Zeile eine Versalzeile ohne Akronyme.
        String text = "API und ".repeat(AcronymProcessor.MAX_OCCURRENCES + 3);
        EnrichmentOutput output = processor.process(plain(text));

        assertThat(output.payloadJson()).contains("\"count\":" + (AcronymProcessor.MAX_OCCURRENCES + 3));
        assertThat(output.payloadJson().split("\"start\":")).hasSize(AcronymProcessor.MAX_OCCURRENCES + 1);
        assertThat(output.warnings()).containsExactly("Fundstellen nur für die ersten 5000 Vorkommen im Text");
    }

    @Test
    void keepsTheFirstOccurrencesInTextOrderAcrossAcronyms() {
        PageAcronyms acronyms = read("ZZZ und ".repeat(AcronymProcessor.MAX_OCCURRENCES) + "AAA");

        assertThat(acronyms.acronyms()).extracting(PageAcronyms.Acronym::acronym, PageAcronyms.Acronym::count,
                a -> a.occurrences().size()).containsExactly(
                tuple("AAA", 1, 0),
                tuple("ZZZ", AcronymProcessor.MAX_OCCURRENCES, AcronymProcessor.MAX_OCCURRENCES));
    }

    @Test
    void expansionsAreAlignedLikeTheEac() {
        assertThat(expansion("Die Zentrale Datenverarbeitung (ZDV) rechnet.")).isEqualTo("Zentrale Datenverarbeitung");
        assertThat(expansion("Das Bundesamt für Sicherheit in der Informationstechnik (BSI) prüft."))
                .isEqualTo("Bundesamt für Sicherheit in der Informationstechnik");
        assertThat(expansion("Jede API (Application Programming Interface, siehe unten) hat Grenzen."))
                .isEqualTo("Application Programming Interface");
        // Ziffern müssen wie im EAC in der Ausschreibung vorkommen.
        assertThat(expansion("Business to Business (B2B) wächst.")).isNull();
        // Die Ausschreibung bleibt im selben Satz und enthält das Akronym nicht selbst.
        assertThat(expansion("Das ist ein Interface. Neu (API) ist es.")).isNull();
        assertThat(expansion("ABC (ABC Beta Corp) zahlt.")).isNull();
    }

    @Test
    void mentionsCarryKindAndExpansionInTextOrder() {
        String text = "Die Local Area Network (LAN) und MAX_SIZE; zwei LANs.";

        assertThat(processor.mentions(text)).extracting(AcronymProcessor.Mention::term,
                AcronymProcessor.Mention::acronym, AcronymProcessor.Mention::expansion).containsExactly(
                tuple("LAN", true, "Local Area Network"), tuple("MAX_SIZE", false, null),
                tuple("LAN", true, "Local Area Network"));
    }

    private String expansion(String text) {
        List<PageAcronyms.Acronym> acronyms = read(text).acronyms();
        return acronyms.isEmpty() ? null : acronyms.get(0).expansion();
    }

    private PageAcronyms read(String text) {
        return PageAcronyms.read(PageAcronymsTest.KEY, List.of(PageAcronymsTest.current(plain(text), processor)),
                plain(text));
    }

    private static PageText plain(String text) {
        return new PageText("t/v1", text, List.of());
    }
}
