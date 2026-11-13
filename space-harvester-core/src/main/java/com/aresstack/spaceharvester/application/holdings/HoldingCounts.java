package com.aresstack.spaceharvester.application.holdings;

/**
 * Zähler eines Teilbaums einschließlich seiner Wurzel, oder eines ganzen Space.
 *
 * @param pages    alle Seiten, gesperrte eingeschlossen
 * @param local    Seiten mit lokalem, aktuellem Inhalt
 * @param outdated Seiten mit lokalem, aber veraltetem Inhalt
 * @param locked   gesperrte Seiten
 */
public record HoldingCounts(int pages, int local, int outdated, int locked) {

    /** Nichts. */
    public static final HoldingCounts ZERO = new HoldingCounts(0, 0, 0, 0);

    public HoldingCounts {
        if (pages < 0 || local < 0 || outdated < 0 || locked < 0) {
            throw new IllegalArgumentException("Zähler dürfen nicht negativ sein");
        }
    }

    /** Die Zähler einer einzelnen Seite in diesem Zustand; {@link HoldingState#REMOVED} zählt nicht. */
    public static HoldingCounts of(HoldingState state) {
        return switch (state) {
            case REMOTE -> new HoldingCounts(1, 0, 0, 0);
            case LOCAL -> new HoldingCounts(1, 1, 0, 0);
            case OUTDATED -> new HoldingCounts(1, 0, 1, 0);
            case LOCKED -> new HoldingCounts(1, 0, 0, 1);
            case REMOVED -> ZERO;
        };
    }

    public HoldingCounts plus(HoldingCounts other) {
        return new HoldingCounts(pages + other.pages, local + other.local, outdated + other.outdated,
                locked + other.locked);
    }

    /** Lokal vorhanden, aktuell oder veraltet. */
    public int stored() {
        return local + outdated;
    }
}
