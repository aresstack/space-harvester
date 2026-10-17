package com.aresstack.spaceharvester.application.sources;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Die vollständige Anhangsliste einer Seite (#9): Metadaten aller Anhänge, auch nicht eingebetteter, dazu die
 * nativen Listenantworten der Quelle Byte für Byte (Raw-First). Nie Dateiinhalte.
 *
 * @param pageId       ID der Seite in der Quelle
 * @param attachments  Anhänge in der Reihenfolge der Quelle
 * @param responses    Antwortkörper aller Listenseiten in Abrufreihenfolge, unverändert
 * @param mediaType    Medientyp der Antworten
 * @param nativeFormat Format und Abfrageform der Antworten; ein anderer Wert ist nicht vergleichbar
 * @param warnings     ausgelassene Einträge und warum, ohne Inhalte; leer, wenn die Liste vollständig ist
 * @param retrievedAt  Zeitpunkt des Abrufs
 */
public record PageAttachments(String pageId, List<PageAttachment> attachments, List<byte[]> responses,
                              String mediaType, String nativeFormat, List<String> warnings, Instant retrievedAt) {

    public PageAttachments {
        Objects.requireNonNull(pageId, "pageId");
        attachments = List.copyOf(attachments);
        responses = copies(responses);
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(nativeFormat, "nativeFormat");
        warnings = List.copyOf(warnings);
        Objects.requireNonNull(retrievedAt, "retrievedAt");
    }

    /** Kopien der Antwortkörper. */
    @Override
    public List<byte[]> responses() {
        return copies(responses);
    }

    /** {@code true}, wenn die Quelle keinen Eintrag auslassen musste. */
    public boolean complete() {
        return warnings.isEmpty();
    }

    private static List<byte[]> copies(List<byte[]> payloads) {
        List<byte[]> copies = new ArrayList<>(payloads.size());
        payloads.forEach(payload -> copies.add(payload.clone()));
        return List.copyOf(copies);
    }

    @Override
    public String toString() {
        // Nie die Antworten: sie nennen geschützte Dateinamen.
        return "PageAttachments[pageId=" + pageId + ", " + attachments.size() + " Anhänge, " + responses.size()
                + " Antworten, warnings=" + warnings.size() + "]";
    }
}
