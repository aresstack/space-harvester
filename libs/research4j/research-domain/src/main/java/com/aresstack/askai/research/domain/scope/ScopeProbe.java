package com.aresstack.askai.research.domain.scope;

/**
 * ONE transient test idea for the Weidezaun: "what if this term belonged to the mission?" —
 * deliberately DUMB (id + positive semantic text, nothing else). A probe carries no membership, no
 * importance, no user decision, and it NEVER becomes a {@link ScopeAnchor} automatically: only the
 * conversation (the user confirming or ruling it out) turns a probe's idea into a fence post.
 */
public final class ScopeProbe {

    private final String probeId;
    private final String semanticText;

    public ScopeProbe(String probeId, String semanticText) {
        if (probeId == null || probeId.trim().isEmpty()) {
            throw new IllegalArgumentException("probeId must not be empty");
        }
        if (semanticText == null || semanticText.trim().isEmpty()) {
            throw new IllegalArgumentException("semanticText must not be empty");
        }
        this.probeId = probeId.trim();
        this.semanticText = semanticText.trim();
    }

    public String getProbeId() {
        return probeId;
    }

    public String getSemanticText() {
        return semanticText;
    }
}
