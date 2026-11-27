package com.aresstack.knowledge4j.basket;

import com.aresstack.knowledge4j.passage.PassageView;
import com.aresstack.knowledge4j.source.SourceRef;

import java.util.List;

/**
 * Reads the passages of a source in the basket from the host's stock that owns it (the research stock of Ask AI, the
 * index of Space Harvester). The basket holds references only; this port is how the writing gets the text. The host
 * binds it to the run's rights and knowledge state like every other port.
 */
public interface BasketReader {

    /** The passages of one source in reading order; empty when the stock has none or the run may not see them. */
    List<PassageView> passages(SourceRef source);
}
