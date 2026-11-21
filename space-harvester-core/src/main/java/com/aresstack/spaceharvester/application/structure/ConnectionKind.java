package com.aresstack.spaceharvester.application.structure;

/** Art einer Kante, in der Reihenfolge, in der sie gesucht und ausgegeben werden. */
public enum ConnectionKind {
    /** Die Elternseite laut Kopfdaten der Ausgangsseite. */
    PARENT(Basis.EXPLICIT),
    /** Eine Unterseite: ihre Kopfdaten nennen die Ausgangsseite als Elternseite. */
    CHILD(Basis.EXPLICIT),
    /** Die Ausgangsseite verweist auf die Zielseite. */
    LINKS_TO(Basis.EXPLICIT),
    /** Die Zielseite verweist auf die Ausgangsseite. */
    LINKED_FROM(Basis.EXPLICIT),
    /**
     * Ein Begriff der Ausgangsseite steht in einer bestätigten Beziehung zu einem Begriff, der auf der Zielseite
     * vorkommt. Die Beziehungsart (auch „verwandt“) steht in der Herkunft.
     */
    TERM_RELATION(Basis.CONFIRMED);

    private final Basis basis;

    ConnectionKind(Basis basis) {
        this.basis = basis;
    }

    /** Worauf Kanten dieser Art beruhen. */
    public Basis basis() {
        return basis;
    }
}
