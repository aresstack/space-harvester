package com.aresstack.enterpriseai.source.api;

import java.util.List;

/** List the libraries available to the configured source account. */
public interface LibraryListingSource {

    List<String> libraries() throws KnowledgeSourceException;
}