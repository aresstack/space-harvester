package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;

/** Resolve a directly visited native resource for optional navigation-driven acquisition. */
public interface NavigationResourceSource {

    /** Return null when the resource is excluded by the configured content selection. */
    KnowledgeResourceId navigationResource(String nativePath) throws KnowledgeSourceException;
}