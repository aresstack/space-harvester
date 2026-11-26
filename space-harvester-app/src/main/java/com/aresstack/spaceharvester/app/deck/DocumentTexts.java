package com.aresstack.spaceharvester.app.deck;

import com.aresstack.spaceharvester.application.assets.DocumentText;
import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import com.aresstack.spaceharvester.source.TikaDocumentText;

import java.util.Objects;

/**
 * Der Tika-Adapter für Vorschauen außerhalb der Quellen (Dateien und E-Akte im Deck, Dateianhänge im Chat, Dokumente
 * im Chat-Browser), mit denselben Grenzen wie die Quellen ({@code space-harvester.assets.extraction.*}). Die App setzt
 * die Grenzen bei jedem Start der Laufzeit; bis dahin gelten die Vorgaben. Früher hatte die Vorschau eigene feste
 * 50 MB und Tika-Vorgaben (Angelo 26.11.2026: keine Magic Numbers).
 */
public final class DocumentTexts {

    private static volatile Current current = new Current(ExtractionLimits.DEFAULTS, TikaDocumentText.shared());

    private record Current(ExtractionLimits limits, DocumentText text) {
    }

    private DocumentTexts() {
    }

    /** Übernimmt die eingestellten Grenzen; dieselben Grenzen noch einmal ändern nichts. */
    public static void limits(ExtractionLimits limits) {
        Objects.requireNonNull(limits, "limits");
        if (!limits.equals(current.limits())) {
            current = new Current(limits, new TikaDocumentText(limits));
        }
    }

    /** Die eingestellten Grenzen. */
    public static ExtractionLimits limits() {
        return current.limits();
    }

    /** Der Adapter mit den eingestellten Grenzen. */
    public static DocumentText text() {
        return current.text();
    }
}
