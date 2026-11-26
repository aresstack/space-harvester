package com.aresstack.enterpriseai.source.api;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;

/** Deliver original files to disk without materializing them in memory. */
public interface FileDownloadSource {
    /** Return the media type, or empty if the type is not wanted. Zero maxBytes means no caller-imposed limit. */
    Optional<String> downloadOriginal(String localId, Path target, Predicate<String> wanted, long maxBytes)
            throws KnowledgeSourceException;
}
