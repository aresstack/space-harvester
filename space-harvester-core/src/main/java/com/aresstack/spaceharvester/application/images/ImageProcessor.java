package com.aresstack.spaceharvester.application.images;

import com.aresstack.spaceharvester.application.assets.ImageDescriber;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Der Prozessor {@code images} (ADR 0021) in der Anreicherungsablage. Er rechnet nie selbst, weder beim Abgleich noch
 * bei {@code recompute} ({@link #appliesTo} ist immer {@code false}): Bilder kennt der Seitentext nicht, und das Modell
 * wird nur auf Abruf gefragt. Sein Ergebnis schreibt {@link PageImageAnalysis#describe}. Er legt fest, wann ein
 * gespeichertes Ergebnis gilt: Die {@link #version()} enthält Beschreiber und Modell, ein Modellwechsel macht es also
 * veraltet, ebenso ein geänderter Seitentext.
 */
public final class ImageProcessor implements EnrichmentProcessor {

    /** Version der Logik, ohne Beschreiber und Modell. */
    static final String LOGIC = "1";

    private final Supplier<ImageDescriber> describer;

    /** @param describer der eingestellte Beschreiber oder {@code null} */
    public ImageProcessor(Supplier<ImageDescriber> describer) {
        this.describer = Objects.requireNonNull(describer, "describer");
    }

    @Override
    public String id() {
        return ImagePayload.PROCESSOR;
    }

    @Override
    public String version() {
        return version(describer.get());
    }

    /** Die Version für diesen Beschreiber: Logik, Beschreiber samt Analyseversion, Modell. */
    static String version(ImageDescriber model) {
        if (model == null || !model.available()) {
            return LOGIC + "|none";
        }
        return LOGIC + "|" + model.id() + "|" + model.modelVersion();
    }

    @Override
    public EnrichmentOutput process(PageText text) {
        // Nie aufgerufen (appliesTo); ohne Bilder gäbe es nichts zu beschreiben.
        return EnrichmentOutput.of("{\"schema\":\"" + ImagePayload.SCHEMA + "\",\"images\":[]}");
    }

    @Override
    public boolean appliesTo(PageKey key) {
        return false;
    }
}
