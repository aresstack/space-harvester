package com.aresstack.spaceharvester.application.enrichment.statements;

/** Was eine {@link Relation} sagt. */
public enum RelationKind {
    /** Eine dauerhafte Eigenschaft einer Erwähnung („die zuständige Sachbearbeitung“: zuständig). */
    ATTRIBUTE,
    /** Ein Zustand einer Erwähnung („der eingereichte Beleg“: eingereicht). */
    STATE,
    /** Eine Handlung geschieht vor einer anderen, nach einem ausdrücklichen Signalwort („anschließend“, „danach“). */
    BEFORE;

    /** Die Art mit diesem Namen oder {@code null}. */
    public static RelationKind parse(String name) {
        if (name == null) {
            return null;
        }
        for (RelationKind kind : values()) {
            if (kind.name().equals(name.trim())) {
                return kind;
            }
        }
        return null;
    }
}
