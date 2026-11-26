package com.aresstack.spaceharvester.application.evidence;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Technische Prüfung eines Quellstellenbelegs (ADR 0032, Fortschreibung vom 26.11.2026): Die Codestellen müssen in der
 * genannten Revision vorhanden sein, und Code, Abhängigkeiten und Analyseversion dürfen sich seitdem nicht geändert
 * haben. Die fachliche Bedeutung prüft nur ein Mensch; diese Prüfung bestätigt nie etwas fachlich.
 *
 * <p>Ändert sich etwas, ist der Beleg {@link Outcome#POSSIBLY_STALE möglicherweise veraltet}, nicht falsch: Die Regel
 * kann weiter gelten, muss aber neu bestätigt werden. Fehlt ein Dokument oder nennt der Beleg Zeilen, die es in der
 * unveränderten Revision nicht gibt, ist er {@link Outcome#NOT_VERIFIABLE nicht prüfbar}. Es wird nie geraten.
 */
public final class SourceLocationCheck {

    private SourceLocationCheck() {
    }

    /** Ergebnis der Prüfung. */
    public enum Outcome {
        /** Alle Stellen vorhanden, nichts geändert. */
        CURRENT,
        /** Code, Abhängigkeit oder Analyseversion haben sich geändert; eine frühere Bestätigung gilt nicht mehr. */
        POSSIBLY_STALE,
        /** Ein Dokument fehlt oder ist nicht lesbar, oder eine Stelle liegt außerhalb ihres Dokuments. */
        NOT_VERIFIABLE
    }

    /** Gründe; jeder gehört zu genau einem Ergebnis. */
    public enum Reason {
        /** Programm oder Dokument einer Codestelle fehlt oder ist für den Aufrufer nicht lesbar. */
        DOCUMENT_UNAVAILABLE(Outcome.NOT_VERIFIABLE),
        /** Eine Codestelle reicht in der unveränderten Revision über das Dokumentende hinaus. */
        LINES_OUT_OF_RANGE(Outcome.NOT_VERIFIABLE),
        /** Das Programm hat eine andere Revision. */
        PROGRAM_CHANGED(Outcome.POSSIBLY_STALE),
        /** Ein anderes Dokument einer Codestelle (Copybook, weiteres Programm) hat eine andere Revision. */
        SITE_CHANGED(Outcome.POSSIBLY_STALE),
        /** Eine aufgelöste Abhängigkeit hat eine andere Revision oder löst jetzt auf ein anderes Dokument auf. */
        DEPENDENCY_CHANGED(Outcome.POSSIBLY_STALE),
        /** Eine aufgelöste Abhängigkeit wird jetzt nicht mehr gefunden. */
        DEPENDENCY_GONE(Outcome.POSSIBLY_STALE),
        /** Eine negative Probe (damals nicht gefunden) wird jetzt gefunden. */
        DEPENDENCY_NOW_FOUND(Outcome.POSSIBLY_STALE),
        /** Das Programm wird jetzt mit anderem Prozessor oder anderer Version analysiert, oder gar nicht mehr. */
        ANALYSIS_VERSION_CHANGED(Outcome.POSSIBLY_STALE);

        private final Outcome outcome;

        Reason(Outcome outcome) {
            this.outcome = outcome;
        }

        public Outcome outcome() {
            return outcome;
        }
    }

    /**
     * Ergebnis mit allen Gründen.
     *
     * @param outcome schwerstes Ergebnis der Gründe; {@link Outcome#CURRENT} ohne Gründe
     * @param reasons alle gefundenen Gründe
     */
    public record Result(Outcome outcome, Set<Reason> reasons) {

        public Result {
            Objects.requireNonNull(outcome, "outcome");
            reasons = reasons.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(reasons));
            if (outcome != outcomeOf(reasons)) {
                throw new IllegalArgumentException("Ergebnis " + outcome + " passt nicht zu " + reasons);
            }
        }

        static Result of(Set<Reason> reasons) {
            return new Result(outcomeOf(reasons), reasons);
        }

        private static Outcome outcomeOf(Set<Reason> reasons) {
            if (reasons.stream().anyMatch(reason -> reason.outcome() == Outcome.NOT_VERIFIABLE)) {
                return Outcome.NOT_VERIFIABLE;
            }
            return reasons.isEmpty() ? Outcome.CURRENT : Outcome.POSSIBLY_STALE;
        }

        public boolean current() {
            return outcome == Outcome.CURRENT;
        }
    }

    /** Prüft den Beleg gegen den jetzigen Stand. */
    public static Result check(SourceLocationEvidence evidence, CurrentSources sources) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(sources, "sources");
        Set<Reason> reasons = EnumSet.noneOf(Reason.class);
        DocumentKey program = evidence.program();
        Optional<CurrentSources.Current> current = sources.document(program);
        if (current.isEmpty()) {
            reasons.add(Reason.DOCUMENT_UNAVAILABLE);
        } else if (!current.get().revision().equals(evidence.revision())) {
            reasons.add(Reason.PROGRAM_CHANGED);
        }
        for (SourceLocationEvidence.CodeSite site : evidence.sites()) {
            Optional<CurrentSources.Current> document = site.document().equals(program) ? current
                    : sources.document(site.document());
            if (document.isEmpty()) {
                reasons.add(Reason.DOCUMENT_UNAVAILABLE);
            } else if (!document.get().revision().equals(site.revision())) {
                if (!site.document().equals(program)) {
                    reasons.add(Reason.SITE_CHANGED);
                }
            } else if (site.lastLine() > document.get().lineCount()) {
                reasons.add(Reason.LINES_OUT_OF_RANGE);
            }
        }
        for (SourceLocationEvidence.Dependency dependency : evidence.dependencies()) {
            Optional<CurrentSources.Resolved> now = sources.dependency(program, dependency.name());
            if (dependency.negativeProbe()) {
                if (now.isPresent()) {
                    reasons.add(Reason.DEPENDENCY_NOW_FOUND);
                }
            } else if (now.isEmpty()) {
                reasons.add(Reason.DEPENDENCY_GONE);
            } else if (!now.get().document().equals(dependency.document().orElseThrow())
                    || !now.get().revision().equals(dependency.revision().orElseThrow())) {
                reasons.add(Reason.DEPENDENCY_CHANGED);
            }
        }
        if (!sources.analysisVersion(program).map(evidence.analysisVersion()::equals).orElse(false)) {
            reasons.add(Reason.ANALYSIS_VERSION_CHANGED);
        }
        return Result.of(reasons);
    }
}
