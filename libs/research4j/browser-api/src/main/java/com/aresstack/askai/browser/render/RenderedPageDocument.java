package com.aresstack.askai.browser.render;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The NEUTRAL structured capture of one rendered page: container hierarchy, per-container text and
 * link statistics, geometry, visibility and computed colors — measured by the Java-21 sidecar,
 * interpreted exclusively by the Java-8 analysis. Container/link ids are valid ONLY within this
 * snapshot ({@link #snapshotId}/{@link #snapshotGeneration}); resolving them against a different
 * DOM state must be rejected as stale. The capture is bounded ({@link #captureTruncated} and
 * {@link #captureWarnings} report every applied limit) and internally consistent: geometry and
 * structure come from ONE DOM state, verified via {@link #documentFingerprint}.
 */
public final class RenderedPageDocument {

    public final String snapshotId;
    /** Monotonic per session; a new navigation or re-capture bumps it. */
    public final long snapshotGeneration;
    public final String pageUrl;
    public final String pageTitle;
    public final RenderedPageViewport viewport;
    public final RenderedPageFingerprint documentFingerprint;
    public final List<String> rootContainerIds;
    public final List<RenderedContainerDescriptor> containers;
    public final List<RenderedLinkDescriptor> links;
    public final boolean captureTruncated;
    public final List<String> captureWarnings;

    private final Map<String, RenderedContainerDescriptor> containersById;

    public RenderedPageDocument(String snapshotId, long snapshotGeneration, String pageUrl,
                                String pageTitle, RenderedPageViewport viewport,
                                RenderedPageFingerprint documentFingerprint,
                                List<String> rootContainerIds,
                                List<RenderedContainerDescriptor> containers,
                                List<RenderedLinkDescriptor> links, boolean captureTruncated,
                                List<String> captureWarnings) {
        this.snapshotId = snapshotId;
        this.snapshotGeneration = snapshotGeneration;
        this.pageUrl = pageUrl == null ? "" : pageUrl;
        this.pageTitle = pageTitle == null ? "" : pageTitle;
        this.viewport = viewport;
        this.documentFingerprint = documentFingerprint;
        this.rootContainerIds = Collections.unmodifiableList(rootContainerIds);
        this.containers = Collections.unmodifiableList(containers);
        this.links = Collections.unmodifiableList(links);
        this.captureTruncated = captureTruncated;
        this.captureWarnings = Collections.unmodifiableList(captureWarnings);
        Map<String, RenderedContainerDescriptor> byId =
                new LinkedHashMap<String, RenderedContainerDescriptor>();
        for (RenderedContainerDescriptor container : containers) {
            byId.put(container.containerId, container);
        }
        this.containersById = Collections.unmodifiableMap(byId);
    }

    /** @return the container, or null when unknown IN THIS snapshot. */
    public RenderedContainerDescriptor container(String containerId) {
        return containersById.get(containerId);
    }

    /**
     * Guard against stale references: a (snapshotId, containerId) pair from ANOTHER snapshot must
     * never be resolved against this one.
     */
    public boolean owns(String snapshotId, String containerId) {
        return this.snapshotId.equals(snapshotId) && containersById.containsKey(containerId);
    }
}
