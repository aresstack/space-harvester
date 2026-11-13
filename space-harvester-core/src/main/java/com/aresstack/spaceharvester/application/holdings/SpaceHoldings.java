package com.aresstack.spaceharvester.application.holdings;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Bestandsübersicht eines Space: sein Stand und je Seite der eigene Zustand samt Zählern ihres Teilbaums.
 *
 * @param space Stand und Zähler des ganzen Space, auch bei einer Teilübersicht
 * @param pages Seiten nach Seiten-ID; bei einer Teilübersicht nur die genannten Seiten samt ihrer Vorfahren
 */
public record SpaceHoldings(SpaceSummary space, Map<String, PageHolding> pages) {

    public SpaceHoldings {
        Objects.requireNonNull(space, "space");
        pages = Collections.unmodifiableMap(new TreeMap<>(pages));
    }
}
