package com.aresstack.askai.research.domain.scope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE working result of scoping: the fenced-in investigation area the user and the assistant work out
 * together. It is deliberately NOT a research question, NOT an outline and NOT a set of search queries — a
 * single question is at most a PROJECTION of this draft, and the other two belong to later phases.
 * <p>
 * The draft is immutable and carries a {@code revision}: every accepted change produces the next revision,
 * so the scoping history stays reconstructable and the application (never the model) owns continuity. Use
 * {@link #builder()} for a new draft and {@link #toBuilder()} to derive the next revision.
 */
public final class ResearchScopeDraft {

    private final long revision;
    private final String mission;
    private final List<String> domains;
    private final List<String> contexts;
    private final List<ScopeFacet> facets;
    /**
     * The semantic fence posts — canonical state, RECONCILED against the facets on every build():
     * each facet owns exactly one anchor (v1). A missing anchor is derived deterministically from
     * the facet (id {@code anchor-<facetId>}, text = label, membership from status) — which is also
     * the complete, AI-free v1→v2 migration. A declared anchor keeps its (possibly richer) semantic
     * text; only its membership follows the facet. Anchors without a facet are dropped.
     */
    private final List<ScopeAnchor> anchors;
    private final List<String> exclusions;
    private final List<String> perspectives;
    private final List<String> constraints;
    private final String geographicScope;
    private final String temporalScope;
    private final List<String> terminology;
    private final List<UnresolvedScopeIssue> unresolvedIssues;
    private final List<CoverageEmphasis> coverageEmphasis;
    private final List<CrossCuttingEmphasis> crossCuttingEmphasis;
    private final ResearchDeliverable deliverable;

    private ResearchScopeDraft(Builder builder) {
        this.revision = builder.revision;
        this.mission = safe(builder.mission);
        this.domains = copy(builder.domains);
        this.contexts = copy(builder.contexts);
        this.facets = copy(builder.facets);
        this.anchors = reconcileAnchors(builder.facets, builder.anchors);
        this.exclusions = copy(builder.exclusions);
        this.perspectives = copy(builder.perspectives);
        this.constraints = copy(builder.constraints);
        this.geographicScope = safe(builder.geographicScope);
        this.temporalScope = safe(builder.temporalScope);
        this.terminology = copy(builder.terminology);
        this.unresolvedIssues = copy(builder.unresolvedIssues);
        this.coverageEmphasis = copy(builder.coverageEmphasis);
        this.crossCuttingEmphasis = copy(builder.crossCuttingEmphasis);
        this.deliverable = builder.deliverable == null
                ? ResearchDeliverable.unspecified() : builder.deliverable;
    }

    /** An empty draft at revision 0 — a scoping conversation that has not produced anything yet. */
    public static ResearchScopeDraft empty() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A builder seeded with this draft; {@link Builder#nextRevision()} produces the following revision. */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.revision = revision;
        builder.mission = mission;
        builder.domains = new ArrayList<String>(domains);
        builder.contexts = new ArrayList<String>(contexts);
        builder.facets = new ArrayList<ScopeFacet>(facets);
        builder.anchors = new ArrayList<ScopeAnchor>(anchors);
        builder.exclusions = new ArrayList<String>(exclusions);
        builder.perspectives = new ArrayList<String>(perspectives);
        builder.constraints = new ArrayList<String>(constraints);
        builder.geographicScope = geographicScope;
        builder.temporalScope = temporalScope;
        builder.terminology = new ArrayList<String>(terminology);
        builder.unresolvedIssues = new ArrayList<UnresolvedScopeIssue>(unresolvedIssues);
        builder.coverageEmphasis = new ArrayList<CoverageEmphasis>(coverageEmphasis);
        builder.crossCuttingEmphasis = new ArrayList<CrossCuttingEmphasis>(crossCuttingEmphasis);
        builder.deliverable = deliverable;
        return builder;
    }

    public long getRevision() {
        return revision;
    }

    /** What the user actually wants to find out, in their own words — the anchor for everything else. */
    public String getMission() {
        return mission;
    }

    /** The subject areas the mission lives in (e.g. "Wearables", "Medizintechnik"). */
    public List<String> getDomains() {
        return domains;
    }

    /** The situations/settings of interest (e.g. "klinische Nutzung", "Alltag"). */
    public List<String> getContexts() {
        return contexts;
    }

    /** The investigation area, aspect by aspect, each with its own status. */
    public List<ScopeFacet> getFacets() {
        return facets;
    }

    /** What is explicitly OUT — in the user's words, not as negated facets. */
    public List<String> getExclusions() {
        return exclusions;
    }

    /** Through whose eyes it should be looked at (e.g. "Patient", "Zulassungsbehörde"). */
    public List<String> getPerspectives() {
        return perspectives;
    }

    /** Boundary conditions of the work itself (e.g. "nur frei zugängliche Quellen"). */
    public List<String> getConstraints() {
        return constraints;
    }

    public String getGeographicScope() {
        return geographicScope;
    }

    public String getTemporalScope() {
        return temporalScope;
    }

    /** Terms that matter for this scope — later a starting point for search vocabulary, not queries. */
    public List<String> getTerminology() {
        return terminology;
    }

    /**
     * Open points that do NOT block the scope: they are carried forward, never resolved by guessing, and
     * never a gate — the user may confirm the scope with any number of them open.
     */
    public List<UnresolvedScopeIssue> getUnresolvedIssues() {
        return unresolvedIssues;
    }

    public List<CoverageEmphasis> getCoverageEmphasis() {
        return coverageEmphasis;
    }

    public List<CrossCuttingEmphasis> getCrossCuttingEmphasis() {
        return crossCuttingEmphasis;
    }

    public ResearchDeliverable getDeliverable() {
        return deliverable;
    }

    /** The facet with this id, or {@code null}. */
    /** The reconciled fence posts, one per facet, in facet order. */
    public List<ScopeAnchor> getAnchors() {
        return anchors;
    }

    /** The facet's single (v1) fence post, or {@code null} for an unknown facet id. */
    public ScopeAnchor anchorOf(String facetId) {
        for (ScopeAnchor anchor : anchors) {
            if (anchor.getFacetId().equals(facetId)) {
                return anchor;
            }
        }
        return null;
    }

    /** See {@link #anchors}: facet-driven, deterministic, keeps declared richer texts, drops orphans. */
    private static List<ScopeAnchor> reconcileAnchors(List<ScopeFacet> facets,
                                                      List<ScopeAnchor> declared) {
        List<ScopeAnchor> reconciled = new ArrayList<ScopeAnchor>();
        for (ScopeFacet facet : facets) {
            ScopeAnchor existing = null;
            for (ScopeAnchor anchor : declared) {
                if (anchor.getFacetId().equals(facet.getFacetId())) {
                    existing = anchor;
                    break;
                }
            }
            ScopeAnchor.Membership membership = ScopeAnchor.membershipOf(facet.getStatus());
            if (existing == null) {
                reconciled.add(new ScopeAnchor(ScopeAnchor.anchorIdFor(facet.getFacetId()),
                        facet.getFacetId(), facet.getLabel(), membership));
            } else {
                reconciled.add(existing.getMembership() == membership
                        ? existing : existing.withMembership(membership));
            }
        }
        return java.util.Collections.unmodifiableList(reconciled);
    }

    public ScopeFacet facet(String facetId) {
        if (facetId == null) {
            return null;
        }
        for (ScopeFacet facet : facets) {
            if (facet.getFacetId().equals(facetId.trim())) {
                return facet;
            }
        }
        return null;
    }

    /** The emphasis recorded for this facet, or {@code null} when the user weighted nothing. */
    public CoverageEmphasis emphasisOf(String facetId) {
        if (facetId == null) {
            return null;
        }
        for (CoverageEmphasis emphasis : coverageEmphasis) {
            if (emphasis.getTargetFacetId().equals(facetId.trim())) {
                return emphasis;
            }
        }
        return null;
    }

    /** The facets the user RULED OUT — kept on record; the exclusions UI projects these too. */
    public List<ScopeFacet> excludedFacets() {
        List<ScopeFacet> excluded = new ArrayList<ScopeFacet>();
        for (ScopeFacet facet : facets) {
            if (facet.isExcluded()) {
                excluded.add(facet);
            }
        }
        return Collections.unmodifiableList(excluded);
    }

    /** The facets that are IN right now (provisional or confirmed) — excluded ones are kept, not deleted. */
    public List<ScopeFacet> includedFacets() {
        List<ScopeFacet> included = new ArrayList<ScopeFacet>();
        for (ScopeFacet facet : facets) {
            if (!facet.isExcluded()) {
                included.add(facet);
            }
        }
        return Collections.unmodifiableList(included);
    }

    /** Nothing said yet — an untouched draft. */
    public boolean isEmpty() {
        return mission.isEmpty() && domains.isEmpty() && contexts.isEmpty() && facets.isEmpty()
                && exclusions.isEmpty() && perspectives.isEmpty() && constraints.isEmpty()
                && geographicScope.isEmpty() && temporalScope.isEmpty() && terminology.isEmpty()
                && unresolvedIssues.isEmpty() && coverageEmphasis.isEmpty()
                && crossCuttingEmphasis.isEmpty() && !deliverable.hasTargetLength();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null || values.isEmpty()
                ? Collections.<T>emptyList()
                : Collections.unmodifiableList(new ArrayList<T>(values));
    }

    /** Builds a draft; list setters replace, the {@code add*} helpers keep what is already there. */
    public static final class Builder {

        private long revision;
        private String mission = "";
        private List<String> domains = new ArrayList<String>();
        private List<String> contexts = new ArrayList<String>();
        private List<ScopeFacet> facets = new ArrayList<ScopeFacet>();
        private List<ScopeAnchor> anchors = new ArrayList<ScopeAnchor>();
        private List<String> exclusions = new ArrayList<String>();
        private List<String> perspectives = new ArrayList<String>();
        private List<String> constraints = new ArrayList<String>();
        private String geographicScope = "";
        private String temporalScope = "";
        private List<String> terminology = new ArrayList<String>();
        private List<UnresolvedScopeIssue> unresolvedIssues = new ArrayList<UnresolvedScopeIssue>();
        private List<CoverageEmphasis> coverageEmphasis = new ArrayList<CoverageEmphasis>();
        private List<CrossCuttingEmphasis> crossCuttingEmphasis = new ArrayList<CrossCuttingEmphasis>();
        private ResearchDeliverable deliverable = ResearchDeliverable.unspecified();

        public Builder revision(long value) {
            this.revision = value;
            return this;
        }

        /** The next revision — every accepted change produces one. */
        public Builder nextRevision() {
            this.revision = revision + 1;
            return this;
        }

        public Builder mission(String value) {
            this.mission = value;
            return this;
        }

        public Builder domains(List<String> values) {
            this.domains = values == null ? new ArrayList<String>() : new ArrayList<String>(values);
            return this;
        }

        public Builder contexts(List<String> values) {
            this.contexts = values == null ? new ArrayList<String>() : new ArrayList<String>(values);
            return this;
        }

        public Builder facets(List<ScopeFacet> values) {
            this.facets = values == null ? new ArrayList<ScopeFacet>() : new ArrayList<ScopeFacet>(values);
            return this;
        }

        /**
         * Declare a persisted anchor (codec path / a future richer semantic text). Reconciliation in
         * build() aligns its membership with the facet and derives anything missing — the builder
         * never has to keep the two lists consistent by hand.
         */
        public Builder putAnchor(ScopeAnchor anchor) {
            if (anchor == null) {
                return this;
            }
            for (int index = 0; index < anchors.size(); index++) {
                if (anchors.get(index).getFacetId().equals(anchor.getFacetId())) {
                    anchors.set(index, anchor);
                    return this;
                }
            }
            anchors.add(anchor);
            return this;
        }

        /**
         * Add a facet, or REFINE the one with the same id — a repeated statement about the same aspect must
         * never create a duplicate, and must never lose the earlier rationale.
         */
        public Builder putFacet(ScopeFacet facet) {
            if (facet == null) {
                return this;
            }
            for (int index = 0; index < facets.size(); index++) {
                ScopeFacet existing = facets.get(index);
                if (existing.getFacetId().equals(facet.getFacetId())) {
                    // Refine semantics: the EXISTING label wins (the user's wording is not
                    // overwritten by a later operation) — UNLESS it is only the (humanized) id,
                    // i.e. an emergency label from a label-less creation: a real label arriving
                    // later must be able to replace it, or the emergency sticks forever.
                    boolean emergencyLabel = existing.getLabel().equals(existing.getFacetId())
                            || existing.getLabel().equals(
                                    ScopePatchOperations.humanizeId(existing.getFacetId()));
                    boolean betterLabelArrived = emergencyLabel
                            && !facet.getLabel().isEmpty()
                            && !facet.getLabel().equals(existing.getLabel());
                    ScopeFacet base = betterLabelArrived
                            ? new ScopeFacet(existing.getFacetId(), facet.getLabel(),
                                    existing.getStatus(), existing.getRationale())
                            : existing;
                    facets.set(index, base.with(facet.getStatus(), facet.getRationale()));
                    return this;
                }
            }
            facets.add(facet);
            return this;
        }

        public Builder exclusions(List<String> values) {
            this.exclusions = values == null ? new ArrayList<String>() : new ArrayList<String>(values);
            return this;
        }

        public Builder addExclusion(String value) {
            return addDistinct(exclusions, value);
        }

        /** Take a plain exclusion back OUT (exact wording; unknown values are a no-op). */
        public Builder removeExclusion(String value) {
            if (value != null) {
                exclusions.remove(value.trim());
            }
            return this;
        }

        public Builder addDomain(String value) {
            return addDistinct(domains, value);
        }

        public Builder addContext(String value) {
            return addDistinct(contexts, value);
        }

        public Builder addPerspective(String value) {
            return addDistinct(perspectives, value);
        }

        public Builder addConstraint(String value) {
            return addDistinct(constraints, value);
        }

        public Builder perspectives(List<String> values) {
            this.perspectives = values == null ? new ArrayList<String>() : new ArrayList<String>(values);
            return this;
        }

        public Builder constraints(List<String> values) {
            this.constraints = values == null ? new ArrayList<String>() : new ArrayList<String>(values);
            return this;
        }

        public Builder geographicScope(String value) {
            this.geographicScope = value;
            return this;
        }

        public Builder temporalScope(String value) {
            this.temporalScope = value;
            return this;
        }

        public Builder terminology(List<String> values) {
            this.terminology = values == null ? new ArrayList<String>() : new ArrayList<String>(values);
            return this;
        }

        public Builder addTerminology(String value) {
            return addDistinct(terminology, value);
        }

        public Builder unresolvedIssues(List<UnresolvedScopeIssue> values) {
            this.unresolvedIssues = values == null
                    ? new ArrayList<UnresolvedScopeIssue>()
                    : new ArrayList<UnresolvedScopeIssue>(values);
            return this;
        }

        /** Add or REPLACE an open point by its id — restating the same uncertainty is not a new one. */
        public Builder putUnresolvedIssue(UnresolvedScopeIssue issue) {
            if (issue == null) {
                return this;
            }
            for (int index = 0; index < unresolvedIssues.size(); index++) {
                if (unresolvedIssues.get(index).getIssueId().equals(issue.getIssueId())) {
                    unresolvedIssues.set(index, issue);
                    return this;
                }
            }
            unresolvedIssues.add(issue);
            return this;
        }

        /** Convenience for plain wording; the id is DERIVED from the text, so restating it never duplicates. */
        public Builder addUnresolvedIssue(String description) {
            if (description == null || description.trim().isEmpty()) {
                return this;
            }
            return putUnresolvedIssue(new UnresolvedScopeIssue(issueIdFor(description), description,
                    null, UnresolvedScopeIssue.Significance.SIGNIFICANT));
        }

        /** An answered point disappears — unlike a facet it carries no decision worth keeping. */
        public Builder resolveUnresolvedIssue(String issueId) {
            if (issueId == null) {
                return this;
            }
            for (int index = 0; index < unresolvedIssues.size(); index++) {
                if (unresolvedIssues.get(index).getIssueId().equals(issueId.trim())) {
                    unresolvedIssues.remove(index);
                    return this;
                }
            }
            return this;
        }

        public Builder coverageEmphasis(List<CoverageEmphasis> values) {
            this.coverageEmphasis = values == null
                    ? new ArrayList<CoverageEmphasis>() : new ArrayList<CoverageEmphasis>(values);
            return this;
        }

        /** Set (or replace) the emphasis of ONE facet — the latest statement about it wins. */
        public Builder putCoverageEmphasis(CoverageEmphasis emphasis) {
            if (emphasis == null) {
                return this;
            }
            for (int index = 0; index < coverageEmphasis.size(); index++) {
                if (coverageEmphasis.get(index).getTargetFacetId()
                        .equals(emphasis.getTargetFacetId())) {
                    coverageEmphasis.set(index, emphasis);
                    return this;
                }
            }
            coverageEmphasis.add(emphasis);
            return this;
        }

        public Builder crossCuttingEmphasis(List<CrossCuttingEmphasis> values) {
            this.crossCuttingEmphasis = values == null
                    ? new ArrayList<CrossCuttingEmphasis>() : new ArrayList<CrossCuttingEmphasis>(values);
            return this;
        }

        /** Set (or replace) the weight of ONE cross-cutting dimension. */
        public Builder putCrossCuttingEmphasis(CrossCuttingEmphasis emphasis) {
            if (emphasis == null) {
                return this;
            }
            for (int index = 0; index < crossCuttingEmphasis.size(); index++) {
                if (crossCuttingEmphasis.get(index).getDimension().equals(emphasis.getDimension())) {
                    crossCuttingEmphasis.set(index, emphasis);
                    return this;
                }
            }
            crossCuttingEmphasis.add(emphasis);
            return this;
        }

        public Builder deliverable(ResearchDeliverable value) {
            this.deliverable = value;
            return this;
        }

        public ResearchScopeDraft build() {
            return new ResearchScopeDraft(this);
        }

        /** A stable, DERIVED id (no randomness): the domain must stay deterministic and testable. */
        static String issueIdFor(String description) {
            StringBuilder slug = new StringBuilder("issue-");
            String source = description.trim().toLowerCase(java.util.Locale.ROOT);
            for (int index = 0; index < source.length() && slug.length() < 46; index++) {
                char character = source.charAt(index);
                if (Character.isLetterOrDigit(character)) {
                    slug.append(character);
                } else if (slug.charAt(slug.length() - 1) != '-') {
                    slug.append('-');
                }
            }
            return slug.toString();
        }

        private Builder addDistinct(List<String> target, String value) {
            if (value != null && !value.trim().isEmpty() && !target.contains(value.trim())) {
                target.add(value.trim());
            }
            return this;
        }
    }

    /** Facet ids by label — used by adapters that only know the user's wording. */
    public Map<String, String> facetIdsByLabel() {
        Map<String, String> byLabel = new LinkedHashMap<String, String>();
        for (ScopeFacet facet : facets) {
            byLabel.put(facet.getLabel(), facet.getFacetId());
        }
        return Collections.unmodifiableMap(byLabel);
    }
}
