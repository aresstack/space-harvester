package com.aresstack.spaceharvester.application.deadlines;

import com.aresstack.spaceharvester.application.deadlines.DeadlineAssessment.Deadline;
import com.aresstack.spaceharvester.application.deadlines.DeadlineAssessment.DeviationConfirmed;
import com.aresstack.spaceharvester.application.deadlines.DeadlineAssessment.ExceptionCheckClosure;
import com.aresstack.spaceharvester.application.deadlines.DeadlineAssessment.ExceptionEvidence;
import com.aresstack.spaceharvester.application.deadlines.DeadlineAssessment.Proof;
import com.aresstack.spaceharvester.application.deadlines.DeadlineAssessment.ProvisionalReason;
import com.aresstack.spaceharvester.application.deadlines.DeadlineAssessment.StandardConfirmed;
import com.aresstack.spaceharvester.application.deadlines.DeadlineAssessment.StandardProvisional;
import com.aresstack.spaceharvester.application.extraction.AnalysisSection;
import com.aresstack.spaceharvester.application.extraction.DocumentRef;
import com.aresstack.spaceharvester.application.extraction.ExtractedField;
import com.aresstack.spaceharvester.application.extraction.FieldStatus;
import com.aresstack.spaceharvester.application.extraction.Qualifier;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Prüft einen Sachverhalt nach Grundsatz und Ausnahme (ADR 0035, Abschnitt 6): deterministisch und ohne Seiteneffekte.
 * Es zählen nur belegte Dokumentfakten (EXPLICIT oder DERIVED und gegen den Text geprüft); ein CONFLICT entscheidet
 * nichts. Eine Ausnahme verdrängt den Grundsatz nur, wenn Sachverhaltsbezug, Wirksamkeit und Rechtswirkung nachgewiesen
 * sind; ein Antrag ist nie eine Genehmigung. Die Standardfrist ist nur nach ausdrücklichem Abschluss der
 * Ausnahmenprüfung bestätigt. Das Entstehungsjahr kommt nie aus dem Erlassdatum, sondern nur als belegte Angabe.
 */
public final class DeadlineRuleEvaluator {

    private final DeadlineRuleCatalog catalog;

    public DeadlineRuleEvaluator(DeadlineRuleCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /**
     * Eine Prüfung.
     *
     * @param document            Dokument samt Version
     * @param matter              der Sachverhalt mit seinen Feldern
     * @param originYear          belegtes Entstehungsjahr des Geschäftsvorfalls, sonst leer (nie das Erlassdatum)
     * @param knowledge           Zustand des Rechtswissens
     * @param knowledgeGeneration Stand des Rechtswissens
     * @param closure             Abschluss der Ausnahmenprüfung durch den Benutzer, sonst leer
     */
    public record Request(DocumentRef document, AnalysisSection matter, Optional<Integer> originYear,
                          LegalKnowledgeRepository.State knowledge, String knowledgeGeneration,
                          Optional<ExceptionCheckClosure> closure) {

        public Request {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(matter, "matter");
            originYear = originYear == null ? Optional.empty() : originYear;
            Objects.requireNonNull(knowledge, "knowledge");
            knowledgeGeneration = knowledgeGeneration == null ? "" : knowledgeGeneration;
            closure = closure == null ? Optional.empty() : closure;
        }
    }

    /**
     * Ergebnis je Sachverhalt.
     *
     * @param assessment leer, wenn keine Grundregel bestimmbar ist (etwa Kontoart fehlt oder widerspricht sich)
     * @param missing    fehlende Merkmale (Schlüssel)
     * @param conflicts  widersprüchliche Merkmale oder Ausnahmen
     */
    public record Result(String matterId, Optional<DeadlineAssessment> assessment, List<String> missing,
                         List<String> conflicts) {

        public Result {
            assessment = assessment == null ? Optional.empty() : assessment;
            missing = List.copyOf(missing);
            conflicts = List.copyOf(conflicts);
        }
    }

    public Result evaluate(Request request) {
        AnalysisSection matter = request.matter();
        List<String> missing = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        if (matter.fields().stream().anyMatch(f -> f.key().equals(DeadlineFieldKeys.BOOK)
                && f.status() == FieldStatus.CONFLICT)) {
            conflicts.add(DeadlineFieldKeys.BOOK);
            return new Result(matter.matterId(), Optional.empty(), missing, conflicts);
        }
        Set<String> books = values(matter, DeadlineFieldKeys.BOOK);
        if (books.size() != 1) {
            (books.isEmpty() ? missing : conflicts).add(DeadlineFieldKeys.BOOK);
            return new Result(matter.matterId(), Optional.empty(), missing, conflicts);
        }
        String book = books.iterator().next();
        Optional<DeadlineRule> standard = catalog.rules().stream()
                .filter(r -> r.kind() == DeadlineRule.Kind.STANDARD && r.book().equals(book)).findFirst();
        if (standard.isEmpty()) {
            missing.add("regel:" + book);
            return new Result(matter.matterId(), Optional.empty(), missing, conflicts);
        }
        if (request.originYear().isEmpty()) {
            missing.add("entstehungsjahr");
        }
        Set<String> categories = values(matter, DeadlineFieldKeys.ACCOUNT_CATEGORY);
        List<ExtractedField> granted = individual(matter, Qualifier.GRANTED);
        List<ExtractedField> requested = individual(matter, Qualifier.REQUESTED);

        List<ExceptionEvidence> candidates = new ArrayList<>();
        List<DeviationConfirmed> deviations = new ArrayList<>();
        for (DeadlineRule rule : catalog.rules()) {
            if (rule.kind() != DeadlineRule.Kind.EXCEPTION || !rule.book().equals(book)
                    || !rule.displaces().equals(Optional.of(standard.get().id()))) {
                continue;
            }
            ExceptionEvidence evidence = evidence(rule, categories, granted, requested, request.originYear());
            if (evidence == null) {
                continue;
            }
            if (evidence.proven()) {
                deviations.add(new DeviationConfirmed(request.document(), matter.matterId(), rule.ref(),
                        deadline(rule, request.originYear(), granted), standard.get().ref(), evidence));
            } else {
                candidates.add(evidence);
            }
        }
        if (deviations.size() == 1) {
            return new Result(matter.matterId(), Optional.of(deviations.getFirst()), missing, conflicts);
        }
        if (deviations.size() > 1) {
            deviations.forEach(d -> conflicts.add("ausnahme:" + d.rule().id()));
            deviations.forEach(d -> candidates.add(d.evidence()));
        }

        Set<ProvisionalReason> reasons = EnumSet.noneOf(ProvisionalReason.class);
        if (request.knowledge() != LegalKnowledgeRepository.State.READY) {
            reasons.add(ProvisionalReason.KNOWLEDGE_EMPTY);
        }
        if (!closes(request) || !candidates.isEmpty()) {
            reasons.add(ProvisionalReason.UNCHECKED_EXCEPTIONS);
        }
        if (request.originYear().isEmpty()) {
            reasons.add(ProvisionalReason.NO_ORIGIN_DATE);
        }
        Deadline deadline = deadline(standard.get(), request.originYear(), List.of());
        DeadlineAssessment assessment = reasons.isEmpty()
                ? new StandardConfirmed(request.document(), matter.matterId(), standard.get().ref(), deadline,
                request.closure().orElseThrow())
                : new StandardProvisional(request.document(), matter.matterId(), standard.get().ref(), deadline,
                reasons, candidates);
        return new Result(matter.matterId(), Optional.of(assessment), missing, conflicts);
    }

    /** Ob der Abschluss genau zu diesem Dokumentstand, Sachverhalt, Katalog und Wissensstand gehört. */
    private boolean closes(Request request) {
        return request.closure().filter(c -> c.document().equals(request.document())
                && c.matterId().equals(request.matter().matterId())
                && c.catalogVersion().equals(catalog.version())
                && c.knowledgeGeneration().equals(request.knowledgeGeneration())).isPresent();
    }

    /** Beleg einer Ausnahme oder {@code null}, wenn das Dokument nichts auf sie hindeutet. */
    private static ExceptionEvidence evidence(DeadlineRule rule, Set<String> categories, List<ExtractedField> granted,
                                              List<ExtractedField> requested, Optional<Integer> originYear) {
        if (rule.individual()) {
            if (!granted.isEmpty()) {
                return new ExceptionEvidence(rule.ref(), Proof.PROVEN, Proof.PROVEN, Proof.PROVEN, quotes(granted));
            }
            if (!requested.isEmpty()) {
                // Antrag ist keine Genehmigung: die Rechtswirkung ist nicht nachgewiesen.
                return new ExceptionEvidence(rule.ref(), Proof.PROVEN, Proof.OPEN, Proof.NOT_PROVEN,
                        quotes(requested));
            }
            return null;
        }
        if (rule.categories().stream().noneMatch(categories::contains)) {
            return null;
        }
        return new ExceptionEvidence(rule.ref(), Proof.PROVEN, effectiveness(rule, originYear), Proof.PROVEN,
                List.of(rule.legalBasis() + ": " + rule.wording()));
    }

    /** In Kraft zum Entstehungsjahr? Ohne belegtes Jahr oder Datum der Fassung offen, im Jahr der Fassung auch. */
    private static Proof effectiveness(DeadlineRule rule, Optional<Integer> originYear) {
        if (originYear.isEmpty() || rule.validFrom().isEmpty()) {
            return Proof.OPEN;
        }
        int from = rule.validFrom().get().getYear();
        return originYear.get() > from ? Proof.PROVEN : originYear.get() < from ? Proof.NOT_PROVEN : Proof.OPEN;
    }

    private static Deadline deadline(DeadlineRule rule, Optional<Integer> originYear, List<ExtractedField> granted) {
        if (rule.formula() == DeadlineRule.Formula.AS_GRANTED) {
            return new Deadline(rule.formula().text() + ": " + String.join("; ", granted.stream()
                    .map(ExtractedField::rawValue).toList()), Optional.empty());
        }
        return new Deadline(rule.formula().text(), originYear.flatMap(rule.formula()::concrete));
    }

    /** Belegte Werte eines Schlüssels; Unbelegtes und Widersprüchliches zählt nicht. */
    private static Set<String> values(AnalysisSection matter, String key) {
        Set<String> values = new LinkedHashSet<>();
        for (ExtractedField field : matter.fields()) {
            if (field.key().equals(key) && usable(field)) {
                values.add(field.value());
            }
        }
        return values;
    }

    private static List<ExtractedField> individual(AnalysisSection matter, Qualifier qualifier) {
        return matter.fields().stream()
                .filter(f -> (f.key().equals(DeadlineFieldKeys.DEADLINE_EXCEPTION)
                        || f.key().equals(DeadlineFieldKeys.DEADLINE_EXTENSION))
                        && f.qualifier() == qualifier && usable(f))
                .toList();
    }

    private static boolean usable(ExtractedField field) {
        return field.verified() && (field.status() == FieldStatus.EXPLICIT || field.status() == FieldStatus.DERIVED);
    }

    private static List<String> quotes(List<ExtractedField> fields) {
        return fields.stream().map(f -> f.source().excerpt().isEmpty() ? f.rawValue() : f.source().excerpt())
                .toList();
    }
}
