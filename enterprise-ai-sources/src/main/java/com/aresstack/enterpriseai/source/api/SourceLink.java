package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;

/** Verweis von einer Ressource auf eine andere Ressource derselben Quelle. */
public final class SourceLink {

    private final KnowledgeResourceId target;
    private final String label;

    /**
     * @param target Ziel-Ressource derselben Quelle
     * @param label  sichtbarer Name des Ziels (z. B. Seitentitel); leer, wenn unbekannt
     */
    public SourceLink(KnowledgeResourceId target, String label) {
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        this.target = target;
        this.label = label == null ? "" : label.trim();
    }

    public KnowledgeResourceId target() {
        return target;
    }

    public String label() {
        return label;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SourceLink)) {
            return false;
        }
        SourceLink that = (SourceLink) o;
        return target.equals(that.target) && label.equals(that.label);
    }

    @Override
    public int hashCode() {
        return 31 * target.hashCode() + label.hashCode();
    }

    @Override
    public String toString() {
        return "SourceLink{" + target + (label.isEmpty() ? "" : ", '" + label + "'") + "}";
    }
}
