package com.aresstack.spaceharvester.application.enrichment.language;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import java.util.List;
import org.junit.jupiter.api.Test;

class RelationProcessorTest {

    private final RelationProcessor processor = new RelationProcessor();

    @Test
    void derivesSubjectPredicateObjectFromActivities() {
        String text = "Die Sachbearbeitung erstellt den Antrag.";

        assertThat(processor.process(plain(text)).payloadJson()).isEqualTo("{\"relations\":["
                + "{\"type\":\"spo\",\"subject\":\"Sachbearbeitung\",\"subjectNormalized\":\"sachbearbeitung\","
                + "\"predicate\":\"erstellen\",\"object\":\"Antrag\",\"objectNormalized\":\"antrag\","
                + "\"negated\":false,\"cue\":null,\"section\":null,\"charStart\":0,\"charEnd\":39}]}");
    }

    @Test
    void takesTheActorOfAPassiveAsSubjectAndSkipsPassivesWithoutActor() {
        assertThat(processor.process(plain("Der Beleg wird von der Sachbearbeitung geprüft.")).payloadJson())
                .contains("\"type\":\"spo\",\"subject\":\"Sachbearbeitung\",\"subjectNormalized\":\"sachbearbeitung\","
                        + "\"predicate\":\"prüfen\",\"object\":\"Beleg\"");
        assertThat(processor.process(plain("Der Beleg wird geprüft.")).payloadJson())
                .isEqualTo("{\"relations\":[]}");
    }

    @Test
    void findsIsAWithIndefiniteArticleOrBarePluralAndNegation() {
        String json = processor.process(plain("Ein Antrag ist ein Dokument mit Unterschrift. Belege sind Dokumente."
                + " Der Entwurf ist kein Antrag. Der Antrag ist der Beleg.")).payloadJson();

        assertThat(json)
                .contains("{\"type\":\"is_a\",\"subject\":\"Antrag\",\"subjectNormalized\":\"antrag\","
                        + "\"predicate\":\"is_a\",\"object\":\"Dokument\",\"objectNormalized\":\"dokument\","
                        + "\"negated\":false")
                .contains("\"subject\":\"Belege\",\"subjectNormalized\":\"belege\",\"predicate\":\"is_a\","
                        + "\"object\":\"Dokumente\"")
                .contains("\"subject\":\"Entwurf\",\"subjectNormalized\":\"entwurf\",\"predicate\":\"is_a\","
                        + "\"object\":\"Antrag\",\"objectNormalized\":\"antrag\",\"negated\":true")
                .doesNotContain("\"object\":\"Beleg\"");
    }

    @Test
    void findsPartOfAndHasPartWithCoordinatedObjects() {
        String json = processor.process(plain("Das Modul enthält drei Komponenten und eine Schnittstelle. "
                + "Der Cache besteht aus Seiten, Bäumen sowie Metadaten. Der Index ist ein Teil des Caches. "
                + "Die Suche gehört zum Harvester.")).payloadJson();

        assertThat(json)
                .contains("\"type\":\"has_part\",\"subject\":\"Modul\",\"subjectNormalized\":\"modul\","
                        + "\"predicate\":\"has_part\",\"object\":\"Komponenten\"")
                .contains("\"subject\":\"Modul\",\"subjectNormalized\":\"modul\",\"predicate\":\"has_part\","
                        + "\"object\":\"Schnittstelle\"")
                .contains("\"predicate\":\"has_part\",\"object\":\"Seiten\"")
                .contains("\"predicate\":\"has_part\",\"object\":\"Bäumen\"")
                .contains("\"predicate\":\"has_part\",\"object\":\"Metadaten\"")
                .contains("\"type\":\"part_of\",\"subject\":\"Index\",\"subjectNormalized\":\"index\","
                        + "\"predicate\":\"part_of\",\"object\":\"Caches\"")
                .contains("\"type\":\"part_of\",\"subject\":\"Suche\",\"subjectNormalized\":\"suche\","
                        + "\"predicate\":\"part_of\",\"object\":\"Harvester\"");
    }

    @Test
    void findsSynonymsOnlyWithAcronymOrCueWord() {
        String json = processor.process(plain("Die API (Application Programming Interface) liefert JSON. "
                + "Der Harvester (auch Sammler genannt) läuft. Der Server (Produktion) läuft.")).payloadJson();

        assertThat(json)
                .contains("{\"type\":\"synonym\",\"subject\":\"API\",\"subjectNormalized\":\"api\","
                        + "\"predicate\":\"synonym\",\"object\":\"Application Programming Interface\"")
                .contains("\"type\":\"synonym\",\"subject\":\"Harvester\",\"subjectNormalized\":\"harvester\","
                        + "\"predicate\":\"synonym\",\"object\":\"Sammler\"")
                .doesNotContain("\"object\":\"Produktion\"");
    }

    @Test
    void ordersActivitiesOnlyByExplicitCues() {
        String json = processor.process(plain("Der Harvester ruft die Seiten ab. Anschließend werden die "
                + "Anreicherungen berechnet. Der Dienst prüft die Daten, bevor er sie speichert. Nachdem der "
                + "Export endet, startet der Bericht. Der Export läuft. Der Bericht startet.")).payloadJson();

        assertThat(json)
                .contains("{\"type\":\"before\",\"subject\":\"abrufen\",\"subjectNormalized\":\"abrufen\","
                        + "\"predicate\":\"before\",\"object\":\"berechnen\",\"objectNormalized\":\"berechnen\","
                        + "\"negated\":false,\"cue\":\"Anschließend\"")
                .contains("\"subject\":\"prüfen\",\"subjectNormalized\":\"prüfen\",\"predicate\":\"before\","
                        + "\"object\":\"speichern\",\"objectNormalized\":\"speichern\",\"negated\":false,"
                        + "\"cue\":\"bevor\"")
                .contains("\"subject\":\"enden\",\"subjectNormalized\":\"enden\",\"predicate\":\"before\","
                        + "\"object\":\"starten\",\"objectNormalized\":\"starten\",\"negated\":false,"
                        + "\"cue\":\"Nachdem\"");
        assertThat(json.split("\"type\":\"before\"")).hasSize(4);
    }

    @Test
    void limitsTheNumberOfEntriesWithAWarning() {
        EnrichmentOutput output = processor.process(plain("Ein Antrag ist ein Dokument.\n".repeat(1001)));

        assertThat(output.payloadJson().split("\"type\":")).hasSize(RelationProcessor.MAX_RELATIONS + 1);
        assertThat(output.warnings()).containsExactly("Nur die ersten 1000 von 1001 Relationen");
    }

    private static PageText plain(String text) {
        return new PageText("t/v1", text, List.of());
    }
}
