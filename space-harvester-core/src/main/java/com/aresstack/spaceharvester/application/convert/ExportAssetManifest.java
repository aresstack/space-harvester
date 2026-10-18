package com.aresstack.spaceharvester.application.convert;

import java.util.List;
import java.util.Objects;

/**
 * Die Bilder eines Exportdatenbestands ({@code assets.json}, Format {@value #FORMAT}): jede Bildreferenz einer Seite mit
 * dem abgelegten Stand im Asset-Speicher (Version, Größe, {@code sha256}) oder dem Grund, warum es fehlt. Die Bytes
 * liegen nur im Asset-Speicher, SHA-256-adressiert; das unveränderte Raw-JSON der Seiten bleibt unberührt.
 *
 * @param complete ob jede gefundene Referenz abgelegt ist und jede Seite geprüft werden konnte
 */
public record ExportAssetManifest(boolean complete, List<Entry> assets) {

    public static final String FORMAT = "space-harvester/export-assets/v1";

    public ExportAssetManifest {
        assets = List.copyOf(assets);
    }

    /** Die Einträge einer Seite in ihrer Reihenfolge. */
    public List<Entry> forPage(String pageId) {
        return assets.stream().filter(entry -> entry.pageId().equals(pageId)).toList();
    }

    /**
     * Eine Bildreferenz.
     *
     * @param attachmentId ID im Asset-Speicher: die des Anhangs, sonst eine aus der Adresse; {@code null} bei Mermaid
     * @param sha256       {@code sha256:<hex>} der abgelegten Bytes; {@code null}, wenn nichts abgelegt ist
     * @param reason       warum nicht abgelegt, sonst {@code null}; nennt keine Seiten-IDs oder Titel
     */
    public record Entry(String pageId, Reference reference, String attachmentId, String version, String title,
                        String mediaType, long size, String sha256, Status status, String reason) {

        public Entry {
            Objects.requireNonNull(pageId, "pageId");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(status, "status");
            title = title == null ? "" : title;
        }

        public boolean stored() {
            return status == Status.STORED && sha256 != null;
        }
    }

    /**
     * Wo die Referenz in der Seite steht.
     *
     * @param attachmentId bei {@link Kind#ATTACHMENT}: ID des Anhangs
     * @param filename     bei {@link Kind#ATTACHMENT}: Dateiname, wie ihn das Storage-Format referenziert
     * @param src          bei {@link Kind#VIEW_IMAGE}: Adresse in der Lese-Ansicht, ohne Anmeldedaten
     * @param index        bei {@link Kind#MERMAID}: das wievielte Mermaid-Diagramm der Seite, ab 0
     * @param source       bei {@link Kind#MERMAID}: der Diagrammtext
     */
    public record Reference(Kind kind, String attachmentId, String filename, String src, Integer index,
                            String source) {

        public Reference {
            Objects.requireNonNull(kind, "kind");
        }

        public static Reference attachment(String attachmentId, String filename) {
            return new Reference(Kind.ATTACHMENT, attachmentId, filename, null, null, null);
        }

        public static Reference viewImage(String src) {
            return new Reference(Kind.VIEW_IMAGE, null, null, src, null, null);
        }

        public static Reference mermaid(int index, String source) {
            return new Reference(Kind.MERMAID, null, null, null, index, source);
        }
    }

    /** Art der Referenz; in {@code assets.json} klein mit Bindestrich ({@code view-image}). */
    public enum Kind {
        ATTACHMENT("attachment"), VIEW_IMAGE("view-image"), MERMAID("mermaid");

        private final String json;

        Kind(String json) {
            this.json = json;
        }

        public String json() {
            return json;
        }

        public static Kind ofJson(String value) {
            for (Kind kind : values()) {
                if (kind.json.equals(value)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Unbekannte Art " + value);
        }
    }

    /** Stand einer Referenz; nur {@code STORED} gilt in Konvertierungen als vorhanden, alles andere als fehlend. */
    public enum Status {
        /** Abgelegt (bei Mermaid: Diagrammtext vorhanden). */
        STORED,
        /** Die Quelle kennt die Datei nicht (mehr). */
        MISSING,
        /** Die Anmeldung darf die Datei nicht lesen. */
        FORBIDDEN,
        /** Größer als die Grenze des Asset-Speichers. */
        TOO_LARGE,
        /** Quelle nicht erreichbar, gedrosselt, fremde Adresse oder Ablage voll. */
        UNAVAILABLE
    }
}
