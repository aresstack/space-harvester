package com.aresstack.spaceharvester.application.holdings;

import java.util.Objects;

/**
 * Eine Seite der Bestandsübersicht.
 *
 * @param pageId       Seiten-ID
 * @param parentPageId Elternseite im Bestandsbaum, nach dem sich die Zähler richten; {@code null} bei einer Wurzel,
 *                     auch wenn die Seite eine Elternseite nennt, die der Cache nicht kennt, oder an der ein
 *                     Elternzyklus aufgebrochen wurde (wie im Seitenbaum)
 * @param state        eigener Zustand
 * @param subtree      Zähler ihres Teilbaums einschließlich der Seite selbst; {@link HoldingCounts#ZERO} bei
 *                     {@link HoldingState#REMOVED}
 */
public record PageHolding(String pageId, String parentPageId, HoldingState state, HoldingCounts subtree) {

    public PageHolding {
        Objects.requireNonNull(pageId, "pageId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(subtree, "subtree");
    }

    /** Eine genannte Seite, die es in diesem Space nicht mehr gibt. */
    static PageHolding removed(String pageId) {
        return new PageHolding(pageId, null, HoldingState.REMOVED, HoldingCounts.ZERO);
    }
}
