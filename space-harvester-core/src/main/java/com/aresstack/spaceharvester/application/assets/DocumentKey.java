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
        // Steuerzeichen trennen in value() und hash() die Felder; in einer Kennung der Quelle haben sie nichts zu tun.
        if (attachmentId.filter(id -> id.chars().anyMatch(Character::isISOControl)).isPresent()) {
            throw new IllegalArgumentException("Anhangskennung enthält Steuerzeichen");
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

    /**
     * Undurchsichtiger Schlüssel (Lucene-ID, Map-Schlüssel), wie im Volltextindex seit AP4b: Quelle, Seite, Anhang und
     * {@link PartId#value()}, getrennt durch {@code \0}; fehlende Teile bleiben weg. Niemand liest daraus Felder
     * zurück: in Daten stehen immer {@code pageId}, {@code attachmentId} und {@code partId} getrennt (ADR 0026,
     * Schlüsselform).
     */
    public String value() {
        StringBuilder value = new StringBuilder(page.sourceId()).append(SEPARATOR).append(page.pageId());
        attachmentId.ifPresent(id -> value.append(SEPARATOR).append(id));
        part.ifPresent(id -> value.append(SEPARATOR).append(id.value()));
        return value.toString();
    }

    /**
     * Pfadsicherer Name nur aus {@code [0-9a-z-]}: leer für die Seite (sie bleibt an ihrem Ort), für einen Anhang
     * {@code a-<SHA-256 der Anhangskennung in UTF-8>}, für eine enthaltene Datei dazu {@code -} und
     * {@link PartId#fileName()}. Die Kennung der Quelle wird nie selbst ein Pfad.
     */
    public String fileName() {
        if (attachmentId.isEmpty()) {
            return part.map(PartId::fileName).orElse("");
        }
        String attachment = ATTACHMENT_PREFIX + sha256(attachmentId.get());
        return part.map(id -> attachment + '-' + id.fileName()).orElse(attachment);
    }

    /**
     * Ort der Analysen dieses Dokuments relativ zum Ort seiner Seite (ADR 0026, Ablagemuster): leer für die Seite,
     * {@code documents/a-…} für einen Anhang, {@code documents/a-…/parts/<PartId#fileName()>} für eine enthaltene
     * Datei; nur {@code [0-9a-z-]} und {@code /}. Ohne Anhang (Quelle {@code files}) {@code documents/parts/…}.
     */
    public String storagePath() {
        if (isPage()) {
            return "";
        }
        StringBuilder path = new StringBuilder(DOCUMENTS);
        attachmentId.ifPresent(id -> path.append('/').append(ATTACHMENT_PREFIX).append(sha256(id)));
        part.ifPresent(id -> path.append("/parts/").append(id.fileName()));
        return path.toString();
    }

    /**
     * Name für flache Ablagen nach Hash (etwa der Graph, ADR 0026, Schlüsselform Punkt 7): SHA-256 (64 Hexziffern) über
     * Quelle, Seite, Anhangskennung und {@link PartId#value()}, je durch {@code \n} getrennt; fehlende Felder entfallen
     * samt Trenner. Die Seite behält so genau ihren heutigen Namen {@code sha256(quelle \n seite)}.
     */
    public String hash() {
        StringBuilder input = new StringBuilder(page.sourceId()).append('\n').append(page.pageId());
        attachmentId.ifPresent(id -> input.append('\n').append(id));
        part.ifPresent(id -> input.append('\n').append(id.value()));
        return sha256(input.toString());
    }

    private static final char SEPARATOR = '\u0000';
    private static final String ATTACHMENT_PREFIX = "a-";
    private static final String DOCUMENTS = "documents";

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
