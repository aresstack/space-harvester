package com.aresstack.spaceharvester.application.jobs;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Der Bereich eines Auftrags in Worten (Angelo 27.11.2026: „es ist schon wichtig, was für ein Space ausgewählt wurde,
 * beziehungsweise was für ein Bereich genau“), für Aufträge, deren Bereich keine einzelne Partition ist: eine Auswahl
 * aus mehreren Spaces, eine ganze Quelle oder eine Seite samt Unterseiten. Die Auftragsliste zeigt ihn in der Spalte
 * „Space“, wenn der Auftrag keinen Space als Partition trägt.
 */
public final class JobArea {

    /** Parameter eines Auftrags: sein Bereich in Worten, etwa „DEV, OPS“ oder „alle Spaces“. */
    public static final String PARAMETER = "job.area";
    /** Der Bereich einer ganzen Quelle. */
    public static final String WHOLE_SOURCE = "alle Spaces";

    private JobArea() {
    }

    /** Die Parameter mit dem Bereich dazu; ohne Bereich ({@code null} oder leer) unverändert. */
    public static Map<String, String> with(Map<String, String> parameters, String area) {
        if (area == null || area.isBlank()) {
            return parameters;
        }
        Map<String, String> copy = new LinkedHashMap<>(parameters);
        copy.put(PARAMETER, area);
        return Map.copyOf(copy);
    }

    /** Der Bereich eines Auftrags, oder {@code null}. */
    public static String of(JobStatus status) {
        String area = status.parameters() == null ? null : status.parameters().get(PARAMETER);
        return area == null || area.isBlank() ? null : area;
    }
}
