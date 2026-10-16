package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;

/**
 * Ein Bereich, in den sich eine Quelle gliedert und den der Benutzer an- und abhaken kann (Confluence: ein Space).
 * {@link #key()} ist quellennativ und nur für den Adapter bedeutsam; {@link #title()} ist der Name für Menschen.
 * {@link #homeResource()} ist die Startseite des Bereichs (Confluence: {@code homepage}) mit derselben kanonischen
 * Ressourcen-ID wie in Suche und Index, {@code null} ohne Startseite. {@link #startPoint()} ist der Startpunkt für
 * {@link SourceScope}, unter dem {@link KnowledgeSourcePort#discover} die Ressourcen des Bereichs liefert (Confluence:
 * {@code space:KEY}); leer, wenn die Quelle das nicht kann.
 */
public final class SourcePartition {

    private final String key;
    private final String title;
    private final KnowledgeResourceId homeResource;
    private final String startPoint;

    public SourcePartition(String key, String title) {
        this(key, title, null, "");
    }

    public SourcePartition(String key, String title, KnowledgeResourceId homeResource, String startPoint) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        this.key = key.trim();
        this.title = title == null || title.trim().isEmpty() ? this.key : title.trim();
        this.homeResource = homeResource;
        this.startPoint = startPoint == null ? "" : startPoint.trim();
    }

    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    /** Die Startseite des Bereichs oder {@code null}. */
    public KnowledgeResourceId homeResource() {
        return homeResource;
    }

    /** Startpunkt für die Ressourcen des Bereichs; leer = keiner. */
    public String startPoint() {
        return startPoint;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof SourcePartition && key.equals(((SourcePartition) other).key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return "SourcePartition[" + key + ", " + title + (homeResource == null ? "" : ", " + homeResource) + "]";
    }
}
