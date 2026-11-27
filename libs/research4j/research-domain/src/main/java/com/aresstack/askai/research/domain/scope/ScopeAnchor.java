package com.aresstack.askai.research.domain.scope;

/**
 * ONE negotiated fence post of the semantic Weidezaun — canonical scope state, persisted with the
 * draft's revision (never a second source of truth beside it; only the VECTOR is a derived,
 * rebuildable projection kept elsewhere).
 * <p>
 * Invariants (agreed for Z1):
 * <ul>
 * <li>The anchor id is STABLE and derived from the facet ({@code anchor-<facetId>}): confirm/exclude
 *     refine THIS post's membership, they never spawn a second, contradictory post.</li>
 * <li>{@link #getSemanticText()} carries ONLY the positive semantic description (v1: the cleaned
 *     facet label). Membership, importance, research depth, rationale ("the user does not want X")
 *     are metadata and must never leak into the embedding text.</li>
 * <li>A membership change keeps the text — the existing vector stays valid. Only a MEANING change
 *     (new semantic text) invalidates the vector, which the derived index detects by text hash.</li>
 * </ul>
 * Transient probe ideas ({@code ScopeProbe}, Z3) are typologically separate and never become
 * anchors without the user's confirmation.
 */
public final class ScopeAnchor {

    /** Which side of the fence this post marks. PROVISIONAL is a hypothesis, not a boundary. */
    public enum Membership {
        IN,
        PROVISIONAL,
        OUT
    }

    private final String anchorId;
    private final String facetId;
    private final String semanticText;
    private final Membership membership;

    public ScopeAnchor(String anchorId, String facetId, String semanticText, Membership membership) {
        if (anchorId == null || anchorId.trim().isEmpty()) {
            throw new IllegalArgumentException("anchorId must not be empty");
        }
        if (facetId == null || facetId.trim().isEmpty()) {
            throw new IllegalArgumentException("facetId must not be empty");
        }
        this.anchorId = anchorId.trim();
        this.facetId = facetId.trim();
        this.semanticText = semanticText == null ? "" : semanticText.trim();
        this.membership = membership == null ? Membership.PROVISIONAL : membership;
    }

    /** The STABLE anchor id a facet's single v1 post carries: {@code anchor-<facetId>}. */
    public static String anchorIdFor(String facetId) {
        return "anchor-" + (facetId == null ? "" : facetId.trim());
    }

    /** The deterministic fence side of a facet status — the v1 mapping the migration also uses. */
    public static Membership membershipOf(ScopeFacet.Status status) {
        if (status == ScopeFacet.Status.CONFIRMED) {
            return Membership.IN;
        }
        if (status == ScopeFacet.Status.EXCLUDED) {
            return Membership.OUT;
        }
        return Membership.PROVISIONAL;
    }

    public String getAnchorId() {
        return anchorId;
    }

    public String getFacetId() {
        return facetId;
    }

    /** ONLY the positive semantic description — never decisions, weights or negations. */
    public String getSemanticText() {
        return semanticText;
    }

    public Membership getMembership() {
        return membership;
    }

    /** The SAME post on a different fence side; the text (and with it the vector) stays valid. */
    public ScopeAnchor withMembership(Membership newMembership) {
        return new ScopeAnchor(anchorId, facetId, semanticText, newMembership);
    }

    /** The SAME post with a refined MEANING — the derived vector becomes stale (detected by hash). */
    public ScopeAnchor withSemanticText(String newSemanticText) {
        return new ScopeAnchor(anchorId, facetId, newSemanticText, membership);
    }
}
