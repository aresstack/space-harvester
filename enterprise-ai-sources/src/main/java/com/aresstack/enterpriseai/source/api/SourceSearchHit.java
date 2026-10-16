package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeRevision;

import java.net.URI;

/** Treffer der quelleneigenen Suche: Ressource, Titel und ein kurzer Klartext-Ausschnitt. */
public final class SourceSearchHit {

    private final KnowledgeResourceId resourceId;
    private final String title;
    private final String snippet;
    private final KnowledgeRevision revision;
    private final URI url;

    public SourceSearchHit(KnowledgeResourceId resourceId, String title, String snippet) {
        this(resourceId, title, snippet, KnowledgeRevision.unknown());
    }

    /**
     * @param revision Stand der Seite, wenn die Suche ihn mitliefert (z. B. Confluence-Version); damit überspringt die
     *                 suchgesteuerte Indexierung unveränderte Treffer, ohne sie zu laden
     */
    public SourceSearchHit(KnowledgeResourceId resourceId, String title, String snippet, KnowledgeRevision revision) {
        this(resourceId, title, snippet, revision, null);
    }

    /** @param url aufrufbare Adresse der Seite (Bookmark), {@code null}, wenn die Suche keine nennt */
    public SourceSearchHit(KnowledgeResourceId resourceId, String title, String snippet, KnowledgeRevision revision,
                           URI url) {
        if (resourceId == null) {
            throw new IllegalArgumentException("resourceId must not be null");
        }
        this.resourceId = resourceId;
        this.title = title == null ? "" : title.trim();
        this.snippet = snippet == null ? "" : snippet.trim();
        this.revision = revision == null ? KnowledgeRevision.unknown() : revision;
        this.url = url;
    }

    /** Aufrufbare Adresse (Bookmark); {@code null}, wenn unbekannt. */
    public URI url() {
        return url;
    }

    /** {@link #url()} als Text; {@code null}, wenn unbekannt. */
    public String urlText() {
        return url == null ? null : url.toString();
    }

    /** Stand laut Suche; {@link KnowledgeRevision#unknown()}, wenn die Quelle ihn nicht nennt. */
    public KnowledgeRevision revision() {
        return revision;
    }

    public KnowledgeResourceId resourceId() {
        return resourceId;
    }

    public String title() {
        return title;
    }

    /** Klartext ohne Markup; leer, wenn die Quelle keinen Ausschnitt liefert. */
    public String snippet() {
        return snippet;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SourceSearchHit)) {
            return false;
        }
        SourceSearchHit that = (SourceSearchHit) o;
        return resourceId.equals(that.resourceId) && title.equals(that.title) && snippet.equals(that.snippet);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * resourceId.hashCode() + title.hashCode()) + snippet.hashCode();
    }

    @Override
    public String toString() {
        return "SourceSearchHit{" + resourceId + ", '" + title + "'}";
    }
}
