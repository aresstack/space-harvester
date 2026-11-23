package com.aresstack.spaceharvester.mainframe.dependency;

import java.util.List;
import java.util.Objects;

/**
 * Ein Knoten der Aufrufhierarchie („Callees“/„Caller“), wie {@code LeftDrawer.CallHierarchyData} in MainframeMate.
 *
 * @param label      angezeigter Text, z. B. „▶ SCHRITT1 → PGM=SORT  [Zeile 4]“
 * @param target     was ein Doppelklick öffnet: {@code ndv://LIB/OBJEKT}, {@code nat-jcl://STEPLIB/PROGRAMM},
 *                   {@code sysfunc://PGM} oder eine http(s)-Adresse; {@code null}, wenn nichts zu öffnen ist
 * @param recursive  ob der Knoten einen Aufruf wiederholt, der weiter oben schon steht (Rekursion)
 * @param line       Zeile der Aufrufstelle (1-basiert), {@code 0} wenn unbekannt
 * @param sourceFile in welcher Quelle die Zeile steht ({@code ndv://LIB/OBJEKT}); {@code null} = das offene Dokument
 * @param kind       Art des Verweises (CALLNAT, PERFORM, …) für Filter und Gruppen; {@code null} wenn ohne Art
 * @param children   Unterknoten
 */
public record CallNode(String label, String target, boolean recursive, int line, String sourceFile, String kind,
                       List<CallNode> children) {

    public CallNode {
        Objects.requireNonNull(label, "label");
        children = children == null ? List.of() : List.copyOf(children);
    }

    /** Wurzel ohne eigene Bedeutung, nur Halter der obersten Einträge. */
    public static CallNode root(String label, List<CallNode> children) {
        return new CallNode(label, null, false, 0, null, null, children);
    }

    /** Einfacher Eintrag ohne Zeile und Art. */
    public static CallNode of(String label, String target, List<CallNode> children) {
        return new CallNode(label, target, false, 0, null, null, children);
    }

    /** Zahl aller Knoten unterhalb (ohne diesen). */
    public int descendants() {
        int count = 0;
        for (CallNode child : children) {
            count += 1 + child.descendants();
        }
        return count;
    }
}
