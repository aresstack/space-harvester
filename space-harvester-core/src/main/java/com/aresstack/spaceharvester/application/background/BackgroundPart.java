package com.aresstack.spaceharvester.application.background;

import java.util.Map;
import java.util.Objects;

/**
 * Ein Unterauftrag einer Hintergrundgruppe, wie ihn {@link BackgroundKind#work()} bekommt.
 *
 * @param jobId        der Auftrag
 * @param groupId      die Gruppe ({@value BackgroundJobs#GROUP}), etwa für ein gemeinsames Budget aller Spaces
 * @param kind         die Art ({@value BackgroundJobs#KIND})
 * @param sourceId     die Quelle
 * @param partitionKey der Space oder {@code null}
 * @param parameters   die Angaben der Art, ohne die Schlüssel von {@link BackgroundJobs}
 * @param resumed      {@code true}, wenn der Auftrag fortgesetzt wird (nach Anhalten, Fehler oder Neustart), nicht
 *                     zum ersten Mal läuft
 * @param automatic    {@code true}, wenn {@link BackgroundJobs#resumeInterrupted()} ihn nach einem Neustart von selbst
 *                     fortsetzt, nicht ein Benutzer; eine Art kann dann etwa verlangen, dass der Eigentümer derselbe ist
 */
public record BackgroundPart(String jobId, String groupId, String kind, String sourceId, String partitionKey,
                             Map<String, String> parameters, boolean resumed, boolean automatic) {

    public BackgroundPart {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sourceId, "sourceId");
        parameters = Map.copyOf(parameters);
    }

    /** Ohne Angabe, ob von selbst fortgesetzt. */
    public BackgroundPart(String jobId, String groupId, String kind, String sourceId, String partitionKey,
                          Map<String, String> parameters, boolean resumed) {
        this(jobId, groupId, kind, sourceId, partitionKey, parameters, resumed, false);
    }

    /** Eine Angabe der Art oder {@code null}. */
    public String parameter(String key) {
        return parameters.get(key);
    }

    /**
     * Ein Unterauftrag vor dem Start, je Space einer.
     *
     * @param partitionKey Space oder {@code null}
     * @param parameters   Angaben der Art; die Schlüssel beginnen nie mit {@value BackgroundJobs#PREFIX}
     */
    public record Draft(String sourceId, String partitionKey, Map<String, String> parameters) {

        public Draft {
            Objects.requireNonNull(sourceId, "sourceId");
            parameters = Map.copyOf(parameters);
            for (String key : parameters.keySet()) {
                if (key.startsWith(BackgroundJobs.PREFIX)) {
                    throw new IllegalArgumentException("Schlüssel " + key + " ist für BackgroundJobs reserviert");
                }
            }
        }

        public Draft(String sourceId, String partitionKey) {
            this(sourceId, partitionKey, Map.of());
        }
    }
}
