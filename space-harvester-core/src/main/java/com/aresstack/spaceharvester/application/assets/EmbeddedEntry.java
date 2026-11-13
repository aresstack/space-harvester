package com.aresstack.spaceharvester.application.assets;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Eintrag im Verzeichnis der enthaltenen Dateien eines Behälters (ADR 0026, Punkte 5 und 7). Jede Datei, die der Parser
 * des Behälters übergibt, bekommt einen Eintrag mit Kennung und Status, auch wenn sie defekt, verschlüsselt, leer, zu
 * tief verschachtelt oder ausgeschlossen ist. Was nur im Verzeichnis steht (Bild als Mail-Anhang, nicht zugelassener
 * Typ), trägt {@link ExtractionStatus#EXCLUDED} mit {@link StatusReason#MEDIA_TYPE_EXCLUDED}; Inline-Bilder,
 * Vorschaubilder und Makros eines Dokuments bekommen keinen Eintrag (Punkt 13). Das Verzeichnis gehört zu einem Stand des Behälters und einer Extraktion.
 *
 * @param id          Teilkennung im äußeren Behälter
 * @param sha256      Inhalts-Hash, nur wenn die Bytes vollständig gelesen sind
 * @param displayName Anzeigename; kann fehlen, doppelt sein oder Pfadzeichen enthalten und wird nie Kennung oder Pfad
 * @param mediaType   am Inhalt erkannter Medientyp
 * @param position    Zählerpfad in Lesereihenfolge wie {@code /1/3}, auch bei inhaltsbasierter Kennung
 * @param parent      Teilkennung des Elterneintrags; leer, wenn der äußere Behälter selbst das Elterndokument ist
 * @param depth       Verschachtelungstiefe ab 1 (direkt im äußeren Behälter)
 * @param size        gelesene Bytes, wenn bekannt
 * @param outcome     Status, Grund und Umfang der Extraktion dieses Eintrags
 */
public record EmbeddedEntry(PartId id, Optional<String> sha256, Optional<String> displayName,
                            Optional<String> mediaType, String position, Optional<PartId> parent, int depth,
                            Optional<Long> size, Outcome outcome) {

    private static final Pattern POSITION = Pattern.compile("(/[1-9][0-9]{0,8})+");

    public EmbeddedEntry {
        Objects.requireNonNull(id, "id");
        sha256 = sha256 == null ? Optional.empty() : sha256;
        displayName = displayName == null ? Optional.empty() : displayName.filter(name -> !name.isBlank());
        mediaType = mediaType == null ? Optional.empty() : mediaType.filter(type -> !type.isBlank());
        parent = parent == null ? Optional.empty() : parent;
        size = size == null ? Optional.empty() : size;
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(position, "position");
        if (!POSITION.matcher(position).matches()) {
            throw new IllegalArgumentException("Zählerpfad wie /1/3 erwartet");
        }
        int levels = (int) position.chars().filter(c -> c == '/').count();
        if (depth < 1 || depth != levels) {
            throw new IllegalArgumentException("Tiefe passt nicht zur Position");
        }
        if (depth == 1 && parent.isPresent() || depth > 1 && parent.isEmpty()) {
            throw new IllegalArgumentException("Elterneintrag passt nicht zur Tiefe");
        }
        if (size.filter(value -> value < 0).isPresent()) {
            throw new IllegalArgumentException("Größe darf nicht negativ sein");
        }
        if (id instanceof PartId.Content content) {
            if (sha256.isEmpty() || !sha256.get().equalsIgnoreCase(content.sha256())) {
                throw new IllegalArgumentException("Inhaltsbasierte Kennung braucht denselben Hash");
            }
            sha256 = Optional.of(content.sha256());
        }
        if (id instanceof PartId.Position byPosition && !byPosition.path().equals(position)) {
            throw new IllegalArgumentException("Kennung nach Position passt nicht zur Position");
        }
    }
}
