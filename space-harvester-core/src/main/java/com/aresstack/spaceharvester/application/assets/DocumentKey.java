package com.aresstack.spaceharvester.application.assets;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.Objects;
import java.util.Optional;

/**
 * Kennung eines Dokuments (ADR 0026, Punkte 1 und 5): eine Seite, ein Anhang einer Seite oder eine darin enthaltene
 * Datei in jeder Tiefe. Ein Anhang behält die Kennung, die seine Quelle vergibt, auch wenn Download oder Extraktion
 * scheitern; eine enthaltene Datei trägt zusätzlich ihre {@link PartId} im äußeren Behälter. Für Seiten bleibt es beim
 * {@link PageKey}. Inhalts-Hash und Anzeigename gehören nicht dazu.
 *
 * @param page         Seite, unter der das Dokument steht und deren Rechte es hat
 * @param attachmentId Anhangskennung der Quelle, wenn das Dokument ein Anhang oder darin enthalten ist
 * @param part         Teilkennung im äußeren Behälter, wenn das Dokument eine enthaltene Datei ist
 */
public record DocumentKey(PageKey page, Optional<String> attachmentId, Optional<PartId> part) {

    public DocumentKey {
        Objects.requireNonNull(page, "page");
        attachmentId = attachmentId == null ? Optional.empty() : attachmentId;
        part = part == null ? Optional.empty() : part;
        if (attachmentId.filter(String::isBlank).isPresent()) {
            throw new IllegalArgumentException("Anhangskennung darf nicht leer sein");
        }
    }

    /** Die Seite selbst. */
    public static DocumentKey page(PageKey page) {
        return new DocumentKey(page, Optional.empty(), Optional.empty());
    }

    /** Ein Anhang der Seite. */
    public static DocumentKey attachment(PageKey page, String attachmentId) {
        return new DocumentKey(page, Optional.of(Objects.requireNonNull(attachmentId, "attachmentId")),
                Optional.empty());
    }

    /** Eine enthaltene Datei; der äußere Behälter ist die Seite selbst (Quelle {@code files}) oder ein Anhang. */
    public DocumentKey child(PartId part) {
        if (this.part.isPresent()) {
            throw new IllegalStateException("Teilkennungen gelten je äußerem Behälter, nicht verschachtelt");
        }
        return new DocumentKey(page, attachmentId, Optional.of(Objects.requireNonNull(part, "part")));
    }

    public boolean isPage() {
        return attachmentId.isEmpty() && part.isEmpty();
    }

    public boolean isEmbedded() {
        return part.isPresent();
    }
}
