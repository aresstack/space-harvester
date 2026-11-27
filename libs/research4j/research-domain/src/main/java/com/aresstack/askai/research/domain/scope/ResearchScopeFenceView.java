package com.aresstack.askai.research.domain.scope;

/**
 * The compact, authoritative view of the scope that the assistant is given at the START of every turn — the
 * "fence" it works inside.
 * <p>
 * This exists so the model never has to reconstruct the scope from the chat history. The host owns the
 * draft; the model reads this projection and proposes changes to it. Reconstruction from history is exactly
 * how decisions get quietly lost, and why a conversation drifts back into narrowing a question.
 * <p>
 * It is INPUT, not memory: it carries the current revision so a stale answer is recognisable, and it is
 * rendered fresh from the persisted draft each turn.
 */
public final class ResearchScopeFenceView {

    private ResearchScopeFenceView() {
    }

    public static String render(ResearchScopeDraft draft) {
        StringBuilder sb = new StringBuilder();
        sb.append("CURRENT RESEARCH SCOPE — revision ").append(draft.getRevision()).append('\n');
        if (draft.isEmpty()) {
            sb.append("\n(nothing has been scoped yet — this is the very beginning)\n");
            return sb.toString();
        }
        // A missing mission is stated EXPLICITLY (never silently omitted): live-gate 4 showed a
        // draft with four facets and no mission — every scope check stayed WEAK, and neither the
        // model nor the runtime could see why. This exact line is the runtime's detection marker.
        if (draft.getMission().isEmpty()) {
            sb.append("\nMISSION\n(none yet — record the user's goal with setMission)\n");
        } else {
            section(sb, "MISSION", draft.getMission());
        }
        list(sb, "DOMAINS", draft.getDomains());
        list(sb, "CONTEXTS", draft.getContexts());

        StringBuilder inScope = new StringBuilder();
        StringBuilder excluded = new StringBuilder();
        for (ScopeFacet facet : draft.getFacets()) {
            StringBuilder target = facet.isExcluded() ? excluded : inScope;
            target.append("- ").append(facet.getLabel())
                    .append(" (id=").append(facet.getFacetId()).append(") [")
                    .append(facet.getStatus());
            CoverageEmphasis emphasis = draft.emphasisOf(facet.getFacetId());
            if (emphasis != null) {
                target.append(", ").append(emphasis.getImportance())
                        .append(", ").append(emphasis.getResearchDepth());
                if (emphasis.hasShareHint()) {
                    target.append(", ~").append(emphasis.getOutputShareHint()).append("% of the result");
                }
            }
            target.append(']');
            if (!facet.getRationale().isEmpty()) {
                target.append(" — ").append(facet.getRationale());
            }
            target.append('\n');
        }
        block(sb, "IN SCOPE (facet ids are stable — refer to them when you change something)", inScope);
        block(sb, "RULED OUT (kept on record; do not offer these again)", excluded);
        list(sb, "EXCLUSIONS (in the user's words)", draft.getExclusions());
        list(sb, "PERSPECTIVES", draft.getPerspectives());
        list(sb, "CONSTRAINTS", draft.getConstraints());
        section(sb, "GEOGRAPHIC SCOPE", draft.getGeographicScope());
        section(sb, "TEMPORAL SCOPE", draft.getTemporalScope());
        list(sb, "TERMINOLOGY", draft.getTerminology());

        if (!draft.getCrossCuttingEmphasis().isEmpty()) {
            StringBuilder crossCutting = new StringBuilder();
            for (CrossCuttingEmphasis emphasis : draft.getCrossCuttingEmphasis()) {
                crossCutting.append("- ").append(emphasis.getDimension())
                        .append(" [").append(emphasis.getImportance()).append("]\n");
            }
            block(sb, "CROSS-CUTTING (applies to all facets)", crossCutting);
        }
        sb.append("\nDELIVERABLE\n").append(describeDeliverable(draft.getDeliverable()));
        if (!draft.getUnresolvedIssues().isEmpty()) {
            StringBuilder issues = new StringBuilder();
            for (UnresolvedScopeIssue issue : draft.getUnresolvedIssues()) {
                issues.append("- ").append(issue.getDescription())
                        .append(" (id=").append(issue.getIssueId())
                        .append(", ").append(issue.getSignificance()).append(")\n");
            }
            block(sb, "OPEN ISSUES (carried forward — they do NOT block anything)", issues);
        }
        return sb.toString();
    }

    private static String describeDeliverable(ResearchDeliverable deliverable) {
        StringBuilder sb = new StringBuilder();
        if (deliverable.hasTargetLength()) {
            sb.append("- ").append(deliverable.getTargetLengthMin()).append('-')
                    .append(deliverable.getTargetLengthMax()).append(' ')
                    .append(deliverable.getLengthUnit().name().toLowerCase(java.util.Locale.ROOT))
                    .append('\n');
        } else {
            sb.append("- size not stated yet\n");
        }
        SynthesisPolicy policy = deliverable.getSynthesisPolicy();
        sb.append(policy.isCategoryFirst() ? "- category-first\n" : "- entity-first\n");
        if (policy.isContrastRequired()) {
            sb.append("- contrast required\n");
        }
        sb.append("- repetitive entities: ").append(policy.getRepetitiveEntityPolicy())
                .append(", examples: ").append(policy.getExamplePolicy()).append('\n');
        return sb.toString();
    }

    private static void section(StringBuilder sb, String title, String value) {
        if (value != null && !value.isEmpty()) {
            sb.append('\n').append(title).append('\n').append(value).append('\n');
        }
    }

    private static void list(StringBuilder sb, String title, java.util.List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        sb.append('\n').append(title).append('\n');
        for (String value : values) {
            sb.append("- ").append(value).append('\n');
        }
    }

    private static void block(StringBuilder sb, String title, StringBuilder body) {
        if (body.length() > 0) {
            sb.append('\n').append(title).append('\n').append(body);
        }
    }
}
