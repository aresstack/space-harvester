package com.aresstack.enterpriseai.astu.knowledge;

import java.net.URI;
import java.util.Optional;

/**
 * Providerneutrale Beschreibung einer Wissensressource (Wiki-Seite, Confluence-Page, später Datei,
 * SharePoint-Dokument ...), ohne Inhalt. Der geladene Inhalt steht in {@link KnowledgeDocument}.
 *
 * <p>Trägt alles, was Source-Ports, Index und RAG-Kontext brauchen:
 * <ul>
 *   <li>stabile ID ({@link #id()}) und Quelle ({@link #sourceId()}),</li>
 *   <li>Titel und Content-Type des Originals (z. B. {@code text/html}),</li>
 *   <li>Änderungsinformationen ({@link #revision()}),</li>
 *   <li>Parent und Scope (z. B. Elternseite, Space oder Wiki-Kategorie),</li>
 *   <li>optional den aufrufbaren Ort ({@link #location()}) für anklickbare Quellenangaben,</li>
 *   <li>optionale Metadaten.</li>
 * </ul>
 * Keine Felder für Zugangsdaten; {@link #location()} darf keine Credentials enthalten (wird geprüft).
 */
public final class KnowledgeResource {

    /** Content-Type, wenn die Quelle keinen angibt. */
    public static final String DEFAULT_CONTENT_TYPE = "text/plain";

    private final KnowledgeResourceId id;
    private final SourceAddress sourceId;
    private final String title;
    private final String contentType;
    private final KnowledgeRevision revision;
    private final KnowledgeResourceId parentId;
    private final String scope;
    private final URI location;
    private final KnowledgeMetadata metadata;

    private KnowledgeResource(Builder builder) {
        this.id = builder.id;
        this.sourceId = builder.sourceId;
        this.title = builder.title == null || builder.title.trim().isEmpty() ? id.value() : builder.title.trim();
        this.contentType = builder.contentType == null || builder.contentType.trim().isEmpty()
                ? DEFAULT_CONTENT_TYPE : builder.contentType.trim();
        this.revision = builder.revision == null ? KnowledgeRevision.unknown() : builder.revision;
        this.parentId = builder.parentId;
        this.scope = builder.scope == null ? "" : builder.scope.trim();
        this.location = builder.location;
        this.metadata = builder.metadata == null ? KnowledgeMetadata.empty() : builder.metadata;
        if (id.equals(parentId)) {
            throw new IllegalArgumentException("Ressource " + id + " kann nicht ihr eigener Parent sein");
        }
    }

    public static Builder builder(KnowledgeResourceId id, SourceAddress sourceId) {
        return new Builder(id, sourceId);
    }

    /** Builder mit allen Werten dieser Ressource, z. B. um eine neue Revision abzuleiten. */
    public Builder toBuilder() {
        return new Builder(id, sourceId).title(title).contentType(contentType).revision(revision)
                .parentId(parentId).scope(scope).location(location).metadata(metadata);
    }

    public KnowledgeResourceId id() {
        return id;
    }

    public SourceAddress sourceId() {
        return sourceId;
    }

    /** Titel; ist keiner gesetzt, der Wert der ID. */
    public String title() {
        return title;
    }

    public String contentType() {
        return contentType;
    }

    public KnowledgeRevision revision() {
        return revision;
    }

    public Optional<KnowledgeResourceId> parentId() {
        return Optional.ofNullable(parentId);
    }

    /** Fachlicher Bereich innerhalb der Quelle (z. B. Space-Key, Kategorie) oder {@code ""}. */
    public String scope() {
        return scope;
    }

    /** Aufrufbarer Ort des Originals (z. B. Seiten-URL), sofern bekannt. */
    public Optional<URI> location() {
        return Optional.ofNullable(location);
    }

    public KnowledgeMetadata metadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof KnowledgeResource)) {
            return false;
        }
        KnowledgeResource that = (KnowledgeResource) other;
        return id.equals(that.id) && sourceId.equals(that.sourceId) && title.equals(that.title)
                && contentType.equals(that.contentType) && revision.equals(that.revision)
                && (parentId == null ? that.parentId == null : parentId.equals(that.parentId))
                && scope.equals(that.scope)
                && (location == null ? that.location == null : location.equals(that.location))
                && metadata.equals(that.metadata);
    }

    @Override
    public int hashCode() {
        int result = id.hashCode();
        result = 31 * result + sourceId.hashCode();
        result = 31 * result + title.hashCode();
        result = 31 * result + revision.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "KnowledgeResource{id=" + id + ", sourceId=" + sourceId + ", title='" + title + "', contentType="
                + contentType + ", " + revision + (parentId == null ? "" : ", parentId=" + parentId)
                + (scope.isEmpty() ? "" : ", scope=" + scope) + "}";
    }

    /** Builder; Pflicht sind nur ID und Source-ID. */
    public static final class Builder {

        private final KnowledgeResourceId id;
        private final SourceAddress sourceId;
        private String title;
        private String contentType;
        private KnowledgeRevision revision;
        private KnowledgeResourceId parentId;
        private String scope;
        private URI location;
        private KnowledgeMetadata metadata;

        private Builder(KnowledgeResourceId id, SourceAddress sourceId) {
            if (id == null) {
                throw new IllegalArgumentException("Resource-ID fehlt");
            }
            if (sourceId == null) {
                throw new IllegalArgumentException("Source-ID fehlt");
            }
            this.id = id;
            this.sourceId = sourceId;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public Builder revision(KnowledgeRevision revision) {
            this.revision = revision;
            return this;
        }

        public Builder parentId(KnowledgeResourceId parentId) {
            this.parentId = parentId;
            return this;
        }

        public Builder scope(String scope) {
            this.scope = scope;
            return this;
        }

        /** @throws IllegalArgumentException wenn die URI Benutzerinformationen (Credentials) enthält */
        public Builder location(URI location) {
            if (location != null && location.getRawUserInfo() != null) {
                throw new IllegalArgumentException("location darf keine Benutzerinformationen enthalten");
            }
            this.location = location;
            return this;
        }

        public Builder metadata(KnowledgeMetadata metadata) {
            this.metadata = metadata;
            return this;
        }

        public KnowledgeResource build() {
            return new KnowledgeResource(this);
        }
    }
}
