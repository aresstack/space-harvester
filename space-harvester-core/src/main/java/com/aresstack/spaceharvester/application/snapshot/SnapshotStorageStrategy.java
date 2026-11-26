package com.aresstack.spaceharvester.application.snapshot;

import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;

/** Decide what representation of an original is retained in a snapshot. */
public interface SnapshotStorageStrategy {

    boolean supportsSource(HarvestSource source);

    boolean supports(HarvestSource source, PageMetadata metadata);

    PageSnapshot capture(HarvestSource source, PageMetadata metadata);
}
