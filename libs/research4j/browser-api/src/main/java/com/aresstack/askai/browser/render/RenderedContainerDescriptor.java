package com.aresstack.askai.browser.render;

import java.util.Collections;
import java.util.List;

/**
 * One container of the rendered page: NEUTRAL browser facts only (structure, text/link statistics,
 * geometry, visibility, computed colors, separation styling, structural signature). The sidecar
 * measures — it never decides which container "is the content"; that judgement belongs to the
 * Java-8 analysis module. Ids are snapshot-local ({@code container-0001}) and NEVER stable
 * selectors: any later reference must also carry the snapshotId and is stale otherwise.
 */
public final class RenderedContainerDescriptor {

    // --- identity and hierarchy
    public final String containerId;
    /** Empty for a root container. */
    public final String parentContainerId;
    public final List<String> childContainerIds;
    public final int siblingIndex;
    public final int domDepth;

    // --- DOM semantics
    public final String tagName;
    public final String elementId;
    public final List<String> classTokens;
    public final String role;
    public final String ariaLabel;
    /** Coarse semantic markers: MAIN, NAV, HEADER, FOOTER, ASIDE, ARTICLE, SECTION, FORM, LIST. */
    public final List<String> semanticFlags;

    // --- text structure
    public final String textExcerpt;
    public final int totalTextLength;
    public final int linkTextLength;
    public final int nonLinkTextLength;
    public final int headingCount;
    public final int paragraphCount;

    // --- link structure
    public final int linkCount;
    public final int sameHostLinkCount;
    public final int sameRegistrableDomainLinkCount;
    public final int subdomainLinkCount;
    public final int externalDomainLinkCount;
    public final int javascriptOrActionLinkCount;

    // --- geometry and visibility
    public final boolean visible;
    public final RenderedBox boundingBox;
    /** Ratio (0..1) of this container's area that lies inside the viewport. */
    public final double viewportIntersectionRatio;
    public final boolean containsViewportCenter;
    /** Distances of the box center to the viewport center, normalized by viewport size (0..). */
    public final double horizontalCenterDistance;
    public final double verticalCenterDistance;

    // --- visual styling
    public final RenderedColor computedBackgroundColor;
    /** Transparent backgrounds resolved through the ancestor chain to the VISIBLE color. */
    public final RenderedColor effectiveBackgroundColor;
    public final double backgroundDistanceToParent;
    public final double backgroundDistanceToPage;
    /** e.g. "1px solid rgb(220,220,220)" or empty. */
    public final String borderSummary;
    public final double borderRadius;
    public final String boxShadow;
    public final double padding;
    public final double margin;

    // --- repeated structure
    public final DomStructureSignature structureSignature;
    /** Siblings (same parent) sharing this structure signature, EXCLUDING this container. */
    public final int similarSiblingCount;

    private RenderedContainerDescriptor(Builder b) {
        this.containerId = b.containerId;
        this.parentContainerId = b.parentContainerId;
        this.childContainerIds = Collections.unmodifiableList(b.childContainerIds);
        this.siblingIndex = b.siblingIndex;
        this.domDepth = b.domDepth;
        this.tagName = b.tagName;
        this.elementId = b.elementId;
        this.classTokens = Collections.unmodifiableList(b.classTokens);
        this.role = b.role;
        this.ariaLabel = b.ariaLabel;
        this.semanticFlags = Collections.unmodifiableList(b.semanticFlags);
        this.textExcerpt = b.textExcerpt;
        this.totalTextLength = b.totalTextLength;
        this.linkTextLength = b.linkTextLength;
        this.nonLinkTextLength = b.nonLinkTextLength;
        this.headingCount = b.headingCount;
        this.paragraphCount = b.paragraphCount;
        this.linkCount = b.linkCount;
        this.sameHostLinkCount = b.sameHostLinkCount;
        this.sameRegistrableDomainLinkCount = b.sameRegistrableDomainLinkCount;
        this.subdomainLinkCount = b.subdomainLinkCount;
        this.externalDomainLinkCount = b.externalDomainLinkCount;
        this.javascriptOrActionLinkCount = b.javascriptOrActionLinkCount;
        this.visible = b.visible;
        this.boundingBox = b.boundingBox;
        this.viewportIntersectionRatio = b.viewportIntersectionRatio;
        this.containsViewportCenter = b.containsViewportCenter;
        this.horizontalCenterDistance = b.horizontalCenterDistance;
        this.verticalCenterDistance = b.verticalCenterDistance;
        this.computedBackgroundColor = b.computedBackgroundColor;
        this.effectiveBackgroundColor = b.effectiveBackgroundColor;
        this.backgroundDistanceToParent = b.backgroundDistanceToParent;
        this.backgroundDistanceToPage = b.backgroundDistanceToPage;
        this.borderSummary = b.borderSummary;
        this.borderRadius = b.borderRadius;
        this.boxShadow = b.boxShadow;
        this.padding = b.padding;
        this.margin = b.margin;
        this.structureSignature = b.structureSignature;
        this.similarSiblingCount = b.similarSiblingCount;
    }

    public static Builder builder(String containerId) {
        return new Builder(containerId);
    }

    public static final class Builder {
        private final String containerId;
        private String parentContainerId = "";
        private List<String> childContainerIds = Collections.emptyList();
        private int siblingIndex;
        private int domDepth;
        private String tagName = "";
        private String elementId = "";
        private List<String> classTokens = Collections.emptyList();
        private String role = "";
        private String ariaLabel = "";
        private List<String> semanticFlags = Collections.emptyList();
        private String textExcerpt = "";
        private int totalTextLength;
        private int linkTextLength;
        private int nonLinkTextLength;
        private int headingCount;
        private int paragraphCount;
        private int linkCount;
        private int sameHostLinkCount;
        private int sameRegistrableDomainLinkCount;
        private int subdomainLinkCount;
        private int externalDomainLinkCount;
        private int javascriptOrActionLinkCount;
        private boolean visible = true;
        private RenderedBox boundingBox = new RenderedBox(0, 0, 0, 0);
        private double viewportIntersectionRatio;
        private boolean containsViewportCenter;
        private double horizontalCenterDistance;
        private double verticalCenterDistance;
        private RenderedColor computedBackgroundColor = RenderedColor.TRANSPARENT;
        private RenderedColor effectiveBackgroundColor = RenderedColor.TRANSPARENT;
        private double backgroundDistanceToParent;
        private double backgroundDistanceToPage;
        private String borderSummary = "";
        private double borderRadius;
        private String boxShadow = "";
        private double padding;
        private double margin;
        private DomStructureSignature structureSignature = new DomStructureSignature("");
        private int similarSiblingCount;

        private Builder(String containerId) {
            this.containerId = containerId;
        }

        public Builder hierarchy(String parentId, List<String> childIds, int siblingIndex,
                                 int domDepth) {
            this.parentContainerId = parentId == null ? "" : parentId;
            this.childContainerIds = childIds;
            this.siblingIndex = siblingIndex;
            this.domDepth = domDepth;
            return this;
        }

        public Builder semantics(String tagName, String elementId, List<String> classTokens,
                                 String role, String ariaLabel, List<String> semanticFlags) {
            this.tagName = tagName == null ? "" : tagName;
            this.elementId = elementId == null ? "" : elementId;
            this.classTokens = classTokens;
            this.role = role == null ? "" : role;
            this.ariaLabel = ariaLabel == null ? "" : ariaLabel;
            this.semanticFlags = semanticFlags;
            return this;
        }

        public Builder text(String excerpt, int totalLength, int linkTextLength,
                            int nonLinkTextLength, int headingCount, int paragraphCount) {
            this.textExcerpt = excerpt == null ? "" : excerpt;
            this.totalTextLength = totalLength;
            this.linkTextLength = linkTextLength;
            this.nonLinkTextLength = nonLinkTextLength;
            this.headingCount = headingCount;
            this.paragraphCount = paragraphCount;
            return this;
        }

        public Builder links(int linkCount, int sameHost, int sameRegistrable, int subdomain,
                             int external, int javascriptOrAction) {
            this.linkCount = linkCount;
            this.sameHostLinkCount = sameHost;
            this.sameRegistrableDomainLinkCount = sameRegistrable;
            this.subdomainLinkCount = subdomain;
            this.externalDomainLinkCount = external;
            this.javascriptOrActionLinkCount = javascriptOrAction;
            return this;
        }

        public Builder geometry(boolean visible, RenderedBox box, double viewportIntersectionRatio,
                                boolean containsViewportCenter, double horizontalCenterDistance,
                                double verticalCenterDistance) {
            this.visible = visible;
            this.boundingBox = box;
            this.viewportIntersectionRatio = viewportIntersectionRatio;
            this.containsViewportCenter = containsViewportCenter;
            this.horizontalCenterDistance = horizontalCenterDistance;
            this.verticalCenterDistance = verticalCenterDistance;
            return this;
        }

        public Builder colors(RenderedColor computed, RenderedColor effective,
                              double distanceToParent, double distanceToPage) {
            this.computedBackgroundColor = computed;
            this.effectiveBackgroundColor = effective;
            this.backgroundDistanceToParent = distanceToParent;
            this.backgroundDistanceToPage = distanceToPage;
            return this;
        }

        public Builder separation(String borderSummary, double borderRadius, String boxShadow,
                                  double padding, double margin) {
            this.borderSummary = borderSummary == null ? "" : borderSummary;
            this.borderRadius = borderRadius;
            this.boxShadow = boxShadow == null ? "" : boxShadow;
            this.padding = padding;
            this.margin = margin;
            return this;
        }

        public Builder structure(DomStructureSignature signature, int similarSiblingCount) {
            this.structureSignature = signature;
            this.similarSiblingCount = similarSiblingCount;
            return this;
        }

        public RenderedContainerDescriptor build() {
            return new RenderedContainerDescriptor(this);
        }
    }
}
