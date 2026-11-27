package com.aresstack.askai.research.domain.task;

/**
 * One output of a task (ADR 0001, section 4.4: findings, source references, verdicts, text). Artifacts are
 * appended as they are produced, so a failed or canceled task keeps its partial results on record.
 */
public final class TaskArtifact {

    public enum Kind {
        /** A finding a worker extracted, to be judged by the orchestrator. */
        FINDING,
        /** A reference to a source accepted through the research funnel (the id, never a second copy). */
        SOURCE_REFERENCE,
        /** A schema-bound judgement: sufficiency, verification, quality check. */
        VERDICT,
        /** Prose, e.g. a synthesis draft. */
        TEXT
    }

    private final String artifactId;
    private final Kind kind;
    private final String content;
    private final String projectRef;
    private final long createdAtMillis;

    /**
     * @param projectRef the project artifact this one points at or became (a source id, a section id), or
     *                   empty when it has none yet
     */
    public TaskArtifact(String artifactId, Kind kind, String content, String projectRef, long createdAtMillis) {
        if (artifactId == null || artifactId.trim().isEmpty()) {
            throw new IllegalArgumentException("artifactId must not be empty");
        }
        if (kind == null) {
            throw new IllegalArgumentException("artifact kind is required");
        }
        this.artifactId = artifactId.trim();
        this.kind = kind;
        this.content = content == null ? "" : content;
        this.projectRef = projectRef == null ? "" : projectRef.trim();
        this.createdAtMillis = createdAtMillis;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public Kind getKind() {
        return kind;
    }

    public String getContent() {
        return content;
    }

    public String getProjectRef() {
        return projectRef;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }
}
