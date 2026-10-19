package com.aresstack.spaceharvester.application.convert;

import com.aresstack.spaceharvester.application.facts.MetadataFact;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Lesender Port der Analysedaten für den Analyse-Export (#359): je Seite die gespeicherten Ergebnisse aller
 * Anreicherungsprozessoren und die daraus abgeleiteten Fakten. Liest nur; rechnet nichts nach und ändert keine Ablage.
 */
public interface PageAnalyses {

    /** Ohne Ablage: keine Prozessoren, jede Seite ohne Ergebnisse. */
    PageAnalyses NONE = new PageAnalyses() {
        @Override
        public List<Processor> processors() {
            return List.of();
        }

        @Override
        public PageAnalysis of(PageSnapshot snapshot) {
            return new PageAnalysis(null, List.of(), List.of());
        }
    };

    /** Die aktuellen Prozessoren in Ausführungsreihenfolge. */
    List<Processor> processors();

    /** Analysedaten einer Seite, Aktualität gegenüber genau diesem Snapshot (der exportierten Version). */
    PageAnalysis of(PageSnapshot snapshot);

    /** Ein Prozessor mit seiner aktuellen Version. */
    record Processor(String id, String version) {

        public Processor {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(version, "version");
        }
    }

    /**
     * Analysedaten einer Seite.
     *
     * @param inputSha256 Eingabe-Hash des Snapshots, wie ihn die Prozessoren bekämen, oder {@code null}, wenn die
     *                    Payload nicht projizierbar ist
     * @param sections    ein Abschnitt je Prozessor: erst die aktuellen in Ausführungsreihenfolge, dann gespeicherte
     *                    Ergebnisse nicht mehr vorhandener Prozessoren nach Kennung
     * @param facts       die abgeleiteten Fakten der Seite
     */
    record PageAnalysis(String inputSha256, List<Section> sections, List<MetadataFact> facts) {

        public PageAnalysis {
            sections = List.copyOf(sections);
            facts = List.copyOf(facts);
        }
    }

    /**
     * Ergebnis eines Prozessors für eine Seite.
     *
     * @param processorId      Kennung des Prozessors
     * @param currentVersion   aktuelle Version des Prozessors oder {@code null}, wenn es ihn nicht mehr gibt
     * @param state            Zustand gegenüber dem Snapshot
     * @param processorVersion Version, mit der gerechnet wurde, oder {@code null} bei {@link State#MISSING}
     * @param inputSha256      Eingabe-Hash des Ergebnisses oder {@code null} bei {@link State#MISSING}
     * @param revision         Revision der Payload, aus der das Ergebnis stammt, oder {@code null}
     * @param computedAt       Zeitpunkt der Berechnung oder {@code null} bei {@link State#MISSING}
     * @param payloadJson      Ergebnis als JSON-Objekt, unverändert aus der Ablage, oder {@code null} bei
     *                         {@link State#MISSING}
     * @param warnings         Hinweise des Prozessors
     * @param errorCode        fester Fehlercode eines fehlgeschlagenen Ergebnisses oder {@code null}
     */
    record Section(String processorId, String currentVersion, State state, String processorVersion,
                   String inputSha256, String revision, Instant computedAt, String payloadJson,
                   List<String> warnings, String errorCode) {

        public Section {
            Objects.requireNonNull(processorId, "processorId");
            Objects.requireNonNull(state, "state");
            warnings = List.copyOf(warnings);
        }

        /** Ein Prozessor ohne gespeichertes Ergebnis für die Seite. */
        public static Section missing(Processor processor) {
            return new Section(processor.id(), processor.version(), State.MISSING, null, null, null, null, null,
                    List.of(), null);
        }
    }

    /** Zustand eines Ergebnisses gegenüber der exportierten Version. */
    enum State {
        /** Aus genau dieser Version mit der aktuellen Prozessorversion berechnet. */
        CURRENT,
        /** Aus einer anderen Version oder mit einer anderen Prozessorversion berechnet. */
        STALE,
        /** Berechnung gescheitert; {@code errorCode} nennt den Grund. */
        FAILED,
        /** Kein Ergebnis gespeichert. */
        MISSING
    }
}
