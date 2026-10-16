package com.aresstack.enterpriseai.astu.knowledge;

import java.time.Instant;
import java.util.Optional;

/**
 * Änderungsinformation einer Ressource: Zeitpunkt der letzten Änderung und/oder eine quellenspezifische
 * Versionskennung (z. B. MediaWiki-Revision-ID, Confluence-Versionsnummer). Beides ist optional; eine Quelle
 * ohne Änderungsinformation liefert {@link #unknown()}.
 *
 * <p>Zwei Revisionen sind gleich, wenn Zeitpunkt und Version gleich sind. Damit kann die Indexierung
 * entscheiden, ob eine Ressource neu geladen werden muss.
 */
public final class KnowledgeRevision {

    private final Instant modifiedAt;
    private final String version;

    private KnowledgeRevision(Instant modifiedAt, String version) {
        this.modifiedAt = modifiedAt;
        this.version = version;
    }

    public static KnowledgeRevision unknown() {
        return new KnowledgeRevision(null, "");
    }

    /**
     * @param modifiedAt Zeitpunkt der letzten Änderung oder {@code null}, wenn unbekannt
     * @param version    quellenspezifische Version oder {@code null}/leer, wenn unbekannt
     */
    public static KnowledgeRevision of(Instant modifiedAt, String version) {
        return new KnowledgeRevision(modifiedAt, version == null ? "" : version.trim());
    }

    public static KnowledgeRevision modifiedAt(Instant modifiedAt) {
        return of(modifiedAt, null);
    }

    public static KnowledgeRevision version(String version) {
        return of(null, version);
    }

    public Optional<Instant> modifiedAt() {
        return Optional.ofNullable(modifiedAt);
    }

    /** Version oder {@code ""}, wenn unbekannt. */
    public String version() {
        return version;
    }

    public boolean isKnown() {
        return modifiedAt != null || !version.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof KnowledgeRevision)) {
            return false;
        }
        KnowledgeRevision that = (KnowledgeRevision) other;
        return (modifiedAt == null ? that.modifiedAt == null : modifiedAt.equals(that.modifiedAt))
                && version.equals(that.version);
    }

    @Override
    public int hashCode() {
        return 31 * (modifiedAt == null ? 0 : modifiedAt.hashCode()) + version.hashCode();
    }

    @Override
    public String toString() {
        return "KnowledgeRevision{modifiedAt=" + modifiedAt + ", version='" + version + "'}";
    }
}
