package com.aresstack.spaceharvester.application.facts;

import com.aresstack.spaceharvester.application.enrichment.AcronymProcessor;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.language.ActivityProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.EntityProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.PropertyProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.RelationProcessor;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.List;

/** Echte Ergebnisse der Prozessoren für Faktentests. */
public final class FactTestResults {

    public static final String TEXT = "Die Application Programming Interface (API) ist nicht erreichbar. "
            + "Die API liefert Daten. Der Dienst bleibt verfügbar.";
    /** Zwei Eigenschaften, eine verneint. */
    public static final String PROPERTIES = "Der Server ist nicht verfügbar. Der Dienst bleibt verfügbar.";

    /** Eine Handlung mit Modalverb, eine verneinte. */
    public static final String ACTIVITIES = "Der Administrator muss den Dienst neu starten. "
            + "Der Client ruft die Seite nicht auf.";
    /** Zwei Oberbegriffe, einer verneint, und ein Teil. */
    public static final String RELATIONS = "Ein Antrag ist ein Dokument mit Unterschrift. Der Entwurf ist kein Antrag. "
            + "Der Index ist ein Teil des Caches.";

    private FactTestResults() {
    }

    public static Enrichment acronyms(PageKey key, String text) {
        return result(key, new AcronymProcessor(), text);
    }

    public static Enrichment entities(PageKey key, String text) {
        return result(key, new EntityProcessor(), text);
    }

    public static Enrichment properties(PageKey key, String text) {
        return result(key, new PropertyProcessor(), text);
    }

    public static Enrichment activities(PageKey key, String text) {
        return result(key, new ActivityProcessor(), text);
    }

    public static Enrichment relations(PageKey key, String text) {
        return result(key, new RelationProcessor(), text);
    }

    public static Enrichment result(PageKey key, EnrichmentProcessor processor, String text) {
        PageText page = new PageText("test/v1", text, List.of());
        EnrichmentOutput output = processor.process(page);
        return new Enrichment(key, processor.id(), processor.version(), page.inputSha256(), "7",
                Enrichment.Status.OK, output.payloadJson(), output.warnings(), null,
                Instant.parse("2026-10-18T08:00:00Z"));
    }

    /** Ein Ergebnis von {@code processorId} mit {@code count} gleichförmigen Einträgen, je Eintrag ein Fakt. */
    public static Enrichment many(PageKey key, String processorId, int count) {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                entries.append(',');
            }
            entries.append(switch (processorId) {
                case EnrichmentFacts.ACRONYMS -> "{\"acronym\":\"A" + i + "\",\"count\":1}";
                case EnrichmentFacts.ENTITIES -> "{\"term\":\"T" + i + "\",\"normalized\":\"t" + i + "\",\"count\":1}";
                case EnrichmentFacts.PROPERTIES -> "{\"subject\":\"S" + i + "\",\"subjectNormalized\":\"s" + i
                        + "\",\"property\":\"frei\",\"normalized\":\"frei\"}";
                case EnrichmentFacts.RELATIONS -> "{\"type\":\"IS_A\",\"subject\":\"S" + i + "\",\"object\":\"O\"}";
                default -> throw new IllegalArgumentException(processorId);
            });
        }
        return new Enrichment(key, processorId, "1", "sha256:many", "7", Enrichment.Status.OK,
                "{\"" + processorId + "\":[" + entries + "]}", List.of(), null, Instant.parse("2026-10-18T08:00:00Z"));
    }
}
