package com.aresstack.research4j.basket;

import com.aresstack.knowledge4j.access.FetchedContent;
import com.aresstack.knowledge4j.access.SourceAccessException;
import com.aresstack.knowledge4j.source.SourceRef;

/**
 * The host's stock, as the research hands it a source for the basket. The basket holds references only; the host
 * keeps the text (its index, its research stock) so that its {@link com.aresstack.knowledge4j.basket.BasketReader}
 * can give the passages to the writing later. A source that already lives in the host's stock (an internal document)
 * is kept as it is.
 */
@FunctionalInterface
public interface SourceKeeper {

    /** Keeps the source and returns the reference the basket holds; usually the content's own reference. */
    SourceRef keep(FetchedContent content) throws SourceAccessException;
}
