package com.aresstack.askai.research.domain.scope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The vocabulary of scope changes. Deliberately EXPRESSIVE in both directions: a turn can widen the area
 * (add a facet, add a domain), keep several directions open at once, confirm, re-weight, downgrade or rule
 * something out. Scoping is not a funnel towards one question, so the operations must not be a funnel either.
 * <p>
 * Every operation is small and total: applying it to any draft is defined, and nothing it does not mention
 * is touched.
 */
public final class ScopePatchOperations {

    private ScopePatchOperations() {
    }

    public static ScopePatchOperation setMission(final String mission) {
        return new ScopePatchOperation() {
            public String kind() {
                return "setMission";
            }

            public void applyTo(ResearchScopeDraft.Builder builder) {
                builder.mission(mission);
            }

            public String describe() {
                return "mission = " + mission;
            }
        };
    }

    /** Add a NEW aspect (or refine one with the same id) — the normal way an area grows. */
    public static ScopePatchOperation addFacet(final String facetId, final String label,
                                               final String rationale) {
        return facetOperation("addFacet", facetId, label, ScopeFacet.Status.PROVISIONAL, rationale);
    }

    /** The user backed this aspect — it stays in scope. */
    public static ScopePatchOperation confirmFacet(final String facetId, final String rationale) {
        return facetOperation("confirmFacet", facetId, "", ScopeFacet.Status.CONFIRMED, rationale);
    }

    /** The user ruled it out. The facet REMAINS on record with its reason; it is never deleted. */
    public static ScopePatchOperation excludeFacet(final String facetId, final String rationale) {
        return facetOperation("excludeFacet", facetId, "", ScopeFacet.Status.EXCLUDED, rationale);
    }

    private static ScopePatchOperation facetOperation(final String kind, final String facetId,
                                                      final String label, final ScopeFacet.Status status,
                                                      final String rationale) {
        return new ScopePatchOperation() {
            public String kind() {
                return kind;
            }

            public void applyTo(ResearchScopeDraft.Builder builder) {
                // A label is only needed when the facet is new; for confirm/exclude the existing one wins,
                // which is exactly what putFacet's refine semantics do. A NEW facet without a label at
                // least gets a humanized id ("esp-idf" -> "Esp Idf") — raw ids were leaking into the UI.
                builder.putFacet(new ScopeFacet(facetId,
                        label == null || label.trim().isEmpty() ? humanizeId(facetId) : label,
                        status, rationale));
            }

            public String describe() {
                return kind + " " + facetId + (rationale == null || rationale.isEmpty()
                        ? "" : " (" + rationale + ")");
            }
        };
    }

    /**
     * The emergency label for a facet created without one: "esp-idf" → "Esp Idf". Readable at
     * least — the real label remains the caller's job. (The runtime keeps its own copy of this
     * fallback; the two processes share no code.)
     */
    static String humanizeId(String facetId) {
        StringBuilder sb = new StringBuilder();
        for (String token : facetId.split("[-_]+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(token.charAt(0))).append(token.substring(1));
        }
        return sb.length() == 0 ? facetId : sb.toString();
    }

    /** Weight ONE facet: how much it matters, how deeply to dig, optionally a share hint. */
    public static ScopePatchOperation setFacetEmphasis(final String facetId,
                                                       final CoverageEmphasis.Importance importance,
                                                       final CoverageEmphasis.ResearchDepth depth,
                                                       final int outputShareHint) {
        return new ScopePatchOperation() {
            public String kind() {
                return "setFacetEmphasis";
            }

            public void applyTo(ResearchScopeDraft.Builder builder) {
                builder.putCoverageEmphasis(
                        new CoverageEmphasis(facetId, importance, depth, outputShareHint));
            }

            public String describe() {
                return "emphasis " + facetId + " = " + importance + "/" + depth;
            }
        };
    }

    /** Weight a dimension that runs across all facets (e.g. regulation, data protection). */
    public static ScopePatchOperation setCrossCuttingEmphasis(final String dimension,
                                                              final CoverageEmphasis.Importance importance) {
        return new ScopePatchOperation() {
            public String kind() {
                return "setCrossCuttingEmphasis";
            }

            public void applyTo(ResearchScopeDraft.Builder builder) {
                builder.putCrossCuttingEmphasis(new CrossCuttingEmphasis(dimension, importance));
            }

            public String describe() {
                return "cross-cutting " + dimension + " = " + importance;
            }
        };
    }

    public static ScopePatchOperation setDeliverable(final ResearchDeliverable deliverable) {
        return new ScopePatchOperation() {
            public String kind() {
                return "setDeliverable";
            }

            public void applyTo(ResearchScopeDraft.Builder builder) {
                builder.deliverable(deliverable);
            }

            public String describe() {
                return "deliverable = " + (deliverable.hasTargetLength()
                        ? deliverable.getTargetLengthMin() + "-" + deliverable.getTargetLengthMax() + " "
                                + deliverable.getLengthUnit()
                        : "unspecified size");
            }
        };
    }

    public static ScopePatchOperation addTerminology(final String term) {
        return listOperation("addTerminology", term, ListTarget.TERMINOLOGY);
    }

    /** Something the user explicitly does NOT want — kept in their words, not as a negated facet. */
    public static ScopePatchOperation addExclusion(final String exclusion) {
        return listOperation("addExclusion", exclusion, ListTarget.EXCLUSION);
    }

    /**
     * The user takes a PLAIN exclusion back (e.g. the ✕ on a blacklist chip). Only the string list —
     * an EXCLUDED facet stays on record forever and is not touched by this.
     */
    public static ScopePatchOperation removeExclusion(final String exclusion) {
        return new ScopePatchOperation() {
            public String kind() {
                return "removeExclusion";
            }

            public void applyTo(ResearchScopeDraft.Builder builder) {
                builder.removeExclusion(exclusion);
            }

            public String describe() {
                return "removeExclusion " + exclusion;
            }
        };
    }

    public static ScopePatchOperation addDomain(final String domain) {
        return listOperation("addDomain", domain, ListTarget.DOMAIN);
    }

    public static ScopePatchOperation addContext(final String context) {
        return listOperation("addContext", context, ListTarget.CONTEXT);
    }

    public static ScopePatchOperation addPerspective(final String perspective) {
        return listOperation("addPerspective", perspective, ListTarget.PERSPECTIVE);
    }

    public static ScopePatchOperation addConstraint(final String constraint) {
        return listOperation("addConstraint", constraint, ListTarget.CONSTRAINT);
    }

    public static ScopePatchOperation setGeographicScope(final String value) {
        return scalarOperation("setGeographicScope", value, true);
    }

    public static ScopePatchOperation setTemporalScope(final String value) {
        return scalarOperation("setTemporalScope", value, false);
    }

    /** Record an uncertainty — "I don't know that" is a valid, first-class turn result. */
    public static ScopePatchOperation addUnresolvedIssue(final UnresolvedScopeIssue issue) {
        return new ScopePatchOperation() {
            public String kind() {
                return "addUnresolvedIssue";
            }

            public void applyTo(ResearchScopeDraft.Builder builder) {
                builder.putUnresolvedIssue(issue);
            }

            public String describe() {
                return "open: " + issue.getDescription() + " [" + issue.getSignificance() + "]";
            }
        };
    }

    /** The uncertainty is answered; unlike a facet it carries no decision worth keeping on record. */
    public static ScopePatchOperation resolveIssue(final String issueId) {
        return new ScopePatchOperation() {
            public String kind() {
                return "resolveIssue";
            }

            public void applyTo(ResearchScopeDraft.Builder builder) {
                builder.resolveUnresolvedIssue(issueId);
            }

            public String describe() {
                return "resolved: " + issueId;
            }
        };
    }

    private enum ListTarget { TERMINOLOGY, EXCLUSION, DOMAIN, CONTEXT, PERSPECTIVE, CONSTRAINT }

    private static ScopePatchOperation listOperation(final String kind, final String value,
                                                     final ListTarget target) {
        return new ScopePatchOperation() {
            public String kind() {
                return kind;
            }

            public void applyTo(ResearchScopeDraft.Builder builder) {
                switch (target) {
                    case TERMINOLOGY:
                        builder.addTerminology(value);
                        break;
                    case EXCLUSION:
                        builder.addExclusion(value);
                        break;
                    case DOMAIN:
                        builder.addDomain(value);
                        break;
                    case CONTEXT:
                        builder.addContext(value);
                        break;
                    case PERSPECTIVE:
                        builder.addPerspective(value);
                        break;
                    case CONSTRAINT:
                        builder.addConstraint(value);
                        break;
                    default:
                        break;
                }
            }

            public String describe() {
                return kind + " " + value;
            }
        };
    }

    private static ScopePatchOperation scalarOperation(final String kind, final String value,
                                                       final boolean geographic) {
        return new ScopePatchOperation() {
            public String kind() {
                return kind;
            }

            public void applyTo(ResearchScopeDraft.Builder builder) {
                if (geographic) {
                    builder.geographicScope(value);
                } else {
                    builder.temporalScope(value);
                }
            }

            public String describe() {
                return kind + " = " + value;
            }
        };
    }
}
