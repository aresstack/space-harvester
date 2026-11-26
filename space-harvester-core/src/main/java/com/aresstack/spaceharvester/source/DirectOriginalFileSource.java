package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;

/** Optional source adapter capability for processing an admitted original in place. */
interface DirectOriginalFileSource {

    <T> Optional<T> readOriginalInPlace(String localId, Predicate<String> wanted, long maxBytes,
                                        Reader<T> reader) throws KnowledgeSourceException;

    @FunctionalInterface
    interface Reader<T> {
        T read(Path original, KnowledgeResource resource) throws IOException;
    }
}
