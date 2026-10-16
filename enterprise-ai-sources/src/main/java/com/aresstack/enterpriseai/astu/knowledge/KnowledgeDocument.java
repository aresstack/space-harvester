package com.aresstack.enterpriseai.astu.knowledge;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;

/**
 * Der geladene, indexierbare Inhalt einer {@link KnowledgeResource}: die kanonische Textfassung, aus der Chunks
 * und damit der Index abgeleitet werden.
 *
 * <p>Vertrag für Source-Adapter: {@link #text()} ist Klartext; Struktur wird in leichtgewichtigem Markdown
 * ausgedrückt – Überschriften als {@code #}-Zeilen, Absätze durch Leerzeilen getrennt, Listenpunkte als
 * {@code - }/{@code 1. }-Zeilen, Code in {@code ```}-Blöcken. HTML, Wiki-Markup oder Confluence-Storage-Format
 * wandelt der Adapter vorher um. Der Text wird beim Erzeugen auf Unicode-NFC und {@code \n}-Zeilenenden
 * normalisiert (Unicode-Leerzeichen wie U+00A0 werden zu {@code ' '}), damit gleiche Inhalte gleiche Chunks und
 * gleiche {@link #contentHash()} ergeben.
 *
 * <p>Wer die Struktur aus dem Dokumentbaum kennt (Confluence, MediaWiki), liefert zusätzlich {@link #blocks()} (askai
 * {@code SourceCapture.StructuralBlock}); die Zerlegung nimmt dann diese Blöcke und rät die Struktur nicht aus dem
 * Text. Leer heißt: nur der Text ist bekannt.
 */
public final class KnowledgeDocument {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final KnowledgeResource resource;
    private final String text;
    private final String contentHash;
    private final java.util.List<KnowledgeStructuralBlock> blocks;

    private KnowledgeDocument(KnowledgeResource resource, String text,
                              java.util.List<KnowledgeStructuralBlock> blocks) {
        this.resource = resource;
        this.text = text;
        this.contentHash = sha256Hex(text);
        this.blocks = blocks;
    }

    public static KnowledgeDocument of(KnowledgeResource resource, String text) {
        return of(resource, text, null);
    }

    /** Mit den Strukturblöcken aus dem Dokumentbaum; ihr Text wird wie {@link #text()} normalisiert. */
    public static KnowledgeDocument of(KnowledgeResource resource, String text,
                                       java.util.List<KnowledgeStructuralBlock> blocks) {
        if (resource == null) {
            throw new IllegalArgumentException("Ressource fehlt");
        }
        java.util.List<KnowledgeStructuralBlock> normalized = new java.util.ArrayList<KnowledgeStructuralBlock>();
        if (blocks != null) {
            for (KnowledgeStructuralBlock block : blocks) {
                if (block != null) {
                    normalized.add(new KnowledgeStructuralBlock(block.kind(), normalize(block.headingPath()),
                            normalize(block.text())));
                }
            }
        }
        return new KnowledgeDocument(resource, normalize(text), java.util.Collections.unmodifiableList(normalized));
    }

    /** Die Strukturblöcke in Lesereihenfolge; leer, wenn die Quelle nur Text liefert. */
    public java.util.List<KnowledgeStructuralBlock> blocks() {
        return blocks;
    }

    /** NFC, {@code \n}-Zeilenenden, Unicode-Leerzeichen als {@code ' '}; {@code null} wird zu {@code ""}. */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        return KnowledgeText.unifySpaces(Normalizer.normalize(unified, Normalizer.Form.NFC));
    }

    public KnowledgeResource resource() {
        return resource;
    }

    public KnowledgeResourceId id() {
        return resource.id();
    }

    public String text() {
        return text;
    }

    public boolean isBlank() {
        return KnowledgeText.isBlank(text);
    }

    /** SHA-256 (hex) des normalisierten Texts; erkennt unveränderte Inhalte ohne Volltextvergleich. */
    public String contentHash() {
        return contentHash;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof KnowledgeDocument)) {
            return false;
        }
        KnowledgeDocument that = (KnowledgeDocument) other;
        return resource.equals(that.resource) && text.equals(that.text);
    }

    @Override
    public int hashCode() {
        return 31 * resource.hashCode() + contentHash.hashCode();
    }

    /** Ohne Volltext, nur Länge und Hash. */
    @Override
    public String toString() {
        return "KnowledgeDocument{" + resource.id() + ", chars=" + text.length() + ", contentHash="
                + contentHash.substring(0, 12) + "}";
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", ex);
        }
    }
}
