package com.aresstack.enterpriseai.astu.knowledge;

/**
 * Ein Block der Quellstruktur (askai arch {@code SourceCapture.StructuralBlock}): Überschrift, Absatz, Listenpunkt,
 * Tabellenzeile, Zitat oder Code, mit dem Überschriftenpfad, unter dem er steht. Der Source-Adapter liefert die
 * Blöcke aus dem Dokumentbaum (HTML), damit die Zerlegung Tabellen, Zitate und Code als harte Grenzen behält, statt
 * sie aus dem Text zu raten.
 */
public final class KnowledgeStructuralBlock {

    /** Die Struktur des Dokuments; sie geht der semantischen Grenzerkennung vor. */
    public enum Kind { HEADING, PARAGRAPH, LIST_ITEM, TABLE_ROW, QUOTE, CODE }

    private final Kind kind;
    private final String headingPath;
    private final String text;

    /**
     * @param headingPath die Überschriften, getrennt durch {@code " > "}; bei {@link Kind#HEADING} einschließlich
     *                    dieser Überschrift
     */
    public KnowledgeStructuralBlock(Kind kind, String headingPath, String text) {
        if (kind == null) {
            throw new IllegalArgumentException("kind fehlt");
        }
        this.kind = kind;
        this.headingPath = headingPath == null ? "" : headingPath;
        this.text = text == null ? "" : text;
    }

    public Kind kind() {
        return kind;
    }

    public String headingPath() {
        return headingPath;
    }

    public String text() {
        return text;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof KnowledgeStructuralBlock)) {
            return false;
        }
        KnowledgeStructuralBlock that = (KnowledgeStructuralBlock) other;
        return kind == that.kind && headingPath.equals(that.headingPath) && text.equals(that.text);
    }

    @Override
    public int hashCode() {
        return (kind.hashCode() * 31 + headingPath.hashCode()) * 31 + text.hashCode();
    }

    /** Ohne Text. */
    @Override
    public String toString() {
        return "KnowledgeStructuralBlock{" + kind + ", '" + headingPath + "', chars=" + text.length() + "}";
    }
}
