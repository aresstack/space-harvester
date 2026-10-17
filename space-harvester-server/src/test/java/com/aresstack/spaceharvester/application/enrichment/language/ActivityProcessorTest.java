package com.aresstack.spaceharvester.application.enrichment.language;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActivityProcessorTest {

    private final ActivityProcessor processor = new ActivityProcessor();

    @Test
    void findsAnActiveStatementWithActorAndAffectedObject() {
        String text = "Die API liefert JSON an den Kunden.";

        assertThat(processor.process(plain(text)).payloadJson()).isEqualTo("{\"activities\":["
                + "{\"predicate\":\"liefert\",\"lemma\":\"liefern\",\"voice\":\"active\",\"mood\":\"statement\","
                + "\"modal\":null,\"negated\":false,\"participants\":["
                + "{\"role\":\"ACTOR\",\"term\":\"API\",\"normalized\":\"api\",\"pronoun\":false},"
                + "{\"role\":\"AFFECTED_OBJECT\",\"term\":\"JSON\",\"normalized\":\"json\",\"pronoun\":false}],"
                + "\"section\":null,\"charStart\":0,\"charEnd\":" + (text.length() - 1) + "}]}");
    }

    @Test
    void readsAroundParentheticalInsertions() {
        assertThat(processor.process(plain("Die API (Application Programming Interface) liefert JSON.")).payloadJson())
                .contains("\"predicate\":\"liefert\"")
                .contains("[{\"role\":\"ACTOR\",\"term\":\"API\"")
                .contains("{\"role\":\"AFFECTED_OBJECT\",\"term\":\"JSON\"")
                .doesNotContain("Interface");
    }

    @Test
    void treatsTheSubjectOfAPassiveAsAffectedAndTheVonGroupAsActor() {
        assertThat(processor.process(plain("Der Beleg wird von der Sachbearbeitung geprüft.")).payloadJson())
                .contains("\"predicate\":\"wird … geprüft\",\"lemma\":\"prüfen\",\"voice\":\"passive\"")
                .contains("{\"role\":\"AFFECTED_OBJECT\",\"term\":\"Beleg\"")
                .contains("{\"role\":\"ACTOR\",\"term\":\"Sachbearbeitung\"");
    }

    @Test
    void recordsModalVerbsAndSeparatedParticles() {
        assertThat(processor.process(plain("Der Administrator muss den Dienst neu starten.")).payloadJson())
                .contains("\"predicate\":\"muss … starten\",\"lemma\":\"starten\",\"voice\":\"active\","
                        + "\"mood\":\"statement\",\"modal\":\"muss\"")
                .contains("{\"role\":\"ACTOR\",\"term\":\"Administrator\"")
                .contains("{\"role\":\"AFFECTED_OBJECT\",\"term\":\"Dienst\"");
        assertThat(processor.process(plain("Der Client ruft die Seite auf.")).payloadJson())
                .contains("\"predicate\":\"ruft … auf\",\"lemma\":\"aufrufen\"")
                .contains("{\"role\":\"ACTOR\",\"term\":\"Client\"")
                .contains("{\"role\":\"AFFECTED_OBJECT\",\"term\":\"Seite\"");
    }

    @Test
    void recognizesInstructionsWithoutSubject() {
        assertThat(processor.process(plain("Server neu starten.")).payloadJson())
                .contains("\"predicate\":\"starten\",\"lemma\":\"starten\",\"voice\":\"active\","
                        + "\"mood\":\"instruction\"")
                .contains("[{\"role\":\"AFFECTED_OBJECT\",\"term\":\"Server\"");
        assertThat(processor.process(plain("Bei Problemen den Dienst über die Weboberfläche neu starten."))
                .payloadJson()).contains("\"mood\":\"instruction\"")
                .contains("[{\"role\":\"AFFECTED_OBJECT\",\"term\":\"Dienst\"")
                .contains("{\"role\":\"INSTRUMENT\",\"term\":\"Weboberfläche\"");
        assertThat(processor.process(plain("Die Server starten.")).payloadJson())
                .contains("\"mood\":\"statement\"").contains("{\"role\":\"ACTOR\",\"term\":\"Server\"");
        assertThat(processor.process(plain("Starten Sie den Dienst.")).payloadJson())
                .contains("\"mood\":\"instruction\"").contains("[{\"role\":\"AFFECTED_OBJECT\",\"term\":\"Dienst\"")
                .doesNotContain("\"term\":\"Sie\"");
    }

    @Test
    void splitsSubordinateClausesAndMarksNegation() {
        String json = processor.process(plain("Der Export scheitert, wenn der Server nicht antwortet.")).payloadJson();

        assertThat(json).contains("\"predicate\":\"scheitert\",\"lemma\":\"scheitern\",\"voice\":\"active\","
                        + "\"mood\":\"statement\",\"modal\":null,\"negated\":false,\"participants\":["
                        + "{\"role\":\"ACTOR\",\"term\":\"Export\"")
                .contains("\"predicate\":\"antwortet\",\"lemma\":\"antworten\",\"voice\":\"active\","
                        + "\"mood\":\"statement\",\"modal\":null,\"negated\":true,\"participants\":["
                        + "{\"role\":\"ACTOR\",\"term\":\"Server\"");
    }

    @Test
    void carriesTheSubjectIntoACoordinatedClause() {
        String json = processor.process(plain("Der Dienst startet und speichert die Daten.")).payloadJson();

        assertThat(json).contains("\"lemma\":\"starten\"")
                .contains("\"predicate\":\"speichert\",\"lemma\":\"speichern\",\"voice\":\"active\","
                        + "\"mood\":\"statement\",\"modal\":null,\"negated\":false,\"participants\":["
                        + "{\"role\":\"ACTOR\",\"term\":\"Dienst\",\"normalized\":\"dienst\",\"pronoun\":false},"
                        + "{\"role\":\"AFFECTED_OBJECT\",\"term\":\"Daten\"");
    }

    @Test
    void findsRecipientsInstrumentsAndPronounsWithoutResolvingThem() {
        assertThat(processor.process(plain("Der Dienst sendet dem Kunden eine Rechnung.")).payloadJson())
                .contains("{\"role\":\"RECIPIENT\",\"term\":\"Kunden\"")
                .contains("{\"role\":\"AFFECTED_OBJECT\",\"term\":\"Rechnung\"");
        assertThat(processor.process(plain("Er speichert die Daten mit einem Skript.")).payloadJson())
                .contains("{\"role\":\"ACTOR\",\"term\":\"Er\",\"normalized\":\"er\",\"pronoun\":true}")
                .contains("{\"role\":\"INSTRUMENT\",\"term\":\"Skript\"");
        assertThat(processor.process(plain("Die Stelle prüft ihn jede Nacht.")).payloadJson())
                .contains("{\"role\":\"AFFECTED_OBJECT\",\"term\":\"ihn\",\"normalized\":\"ihn\",\"pronoun\":true}")
                .doesNotContain("Nacht");
    }

    @Test
    void takesHabenWithoutFullVerbAsPredicateButLeavesCopulasToProperties() {
        assertThat(processor.process(plain("Der Dienst hat Zugriff auf die Daten.")).payloadJson())
                .contains("\"predicate\":\"hat\",\"lemma\":\"haben\"")
                .contains("{\"role\":\"AFFECTED_OBJECT\",\"term\":\"Zugriff\"");
        assertThat(processor.process(plain("Der Dienst ist erreichbar. Die Seite ist veraltet.")).payloadJson())
                .isEqualTo("{\"activities\":[]}");
    }

    @Test
    void limitsTheNumberOfEntriesWithAWarning() {
        EnrichmentOutput output = processor.process(plain("Der Dienst startet.\n".repeat(1001)));

        assertThat(output.payloadJson().split("\"predicate\":")).hasSize(ActivityProcessor.MAX_ACTIVITIES + 1);
        assertThat(output.warnings()).containsExactly("Nur die ersten 1000 von 1001 Handlungen");
    }

    private static PageText plain(String text) {
        return new PageText("t/v1", text, List.of());
    }
}
