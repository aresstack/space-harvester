package com.aresstack.enterpriseai.source.api;

/** List a single logical partition without changing the caller's configured partition identity. */
public interface SinglePartitionListingSource extends PartitionListingSource {
    String partitionKey();
}
