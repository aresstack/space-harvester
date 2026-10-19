package com.aresstack.spaceharvester.application.facts;

import com.aresstack.spaceharvester.application.enrichment.AcronymProcessor;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.language.EntityProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.PropertyProcessor;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.List;

/** Echte Ergebnisse der Prozessoren für Faktentests. */
public final class FactTestResults {

    public static final String TEXT = "Die Application Programming Interface (API) ist nicht erreichbar. "
            + "Die API liefert Daten. Der Dienst bleibt verfügbar.";
    /** Zwei Eigenschaften, eine verneint. */
    public static final String PROPERTIES = "Der Server ist nicht verfügbar. Der Dienst bleibt verfügbar.";

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

    public static Enrichment result(PageKey key, EnrichmentProcessor processor, String text) {
        PageText page = new PageText("test/v1", text, List.of());
        EnrichmentOutput output = processor.process(page);
        return new Enrichment(key, processor.id(), processor.version(), page.inputSha256(), "7",
                Enrichment.Status.OK, output.payloadJson(), output.warnings(), null,
                Instant.parse("2026-10-18T08:00:00Z"));
    }
}
