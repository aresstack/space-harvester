package com.aresstack.enterpriseai.proasteion.katagogion;

import java.time.Instant;

/**
 * A knowledge resource whose content a tool call handed to the model as context (provenance of the answer), with the
 * identities the application finds it again under. Only in-process callers see it; it never crosses the MCP wire and
 * holds no credentials.
 */
public final class McpContextResource {

    private final String sourceId;
    private final String resourceId;
    private final String title;
    private final String location;
    private final String version;
    private final Instant modifiedAt;

    /**
     * @param sourceId   id of the knowledge source (not blank)
     * @param resourceId stable id of the resource (not blank)
     * @param title      title of the resource; blank falls back to {@code resourceId}
     * @param location   browser address of the resource or {@code null}/blank if none is known
     * @param version    source-specific version or {@code null}/blank if unknown
     * @param modifiedAt time of the last change or {@code null} if unknown
     */
    public McpContextResource(String sourceId, String resourceId, String title, String location, String version,
                              Instant modifiedAt) {
        if (sourceId == null || sourceId.trim().isEmpty() || resourceId == null || resourceId.trim().isEmpty()) {
            throw new IllegalArgumentException("sourceId and resourceId must not be blank");
        }
        this.sourceId = sourceId.trim();
        this.resourceId = resourceId.trim();
        this.title = title == null || title.trim().isEmpty() ? this.resourceId : title.trim();
        this.location = location == null ? "" : location.trim();
        this.version = version == null ? "" : version.trim();
        this.modifiedAt = modifiedAt;
    }

    public String sourceId() {
        return sourceId;
    }

    public String resourceId() {
        return resourceId;
    }

    public String title() {
        return title;
    }

    /** Browser address or empty. */
    public String location() {
        return location;
    }

    /** Version or empty. */
    public String version() {
        return version;
    }

    /** Time of the last change or {@code null}. */
    public Instant modifiedAt() {
        return modifiedAt;
    }

    @Override
    public String toString() {
        // Keep titles and addresses out of logs.
        return "McpContextResource{" + sourceId + '}';
    }
}
