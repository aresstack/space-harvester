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
import com.aresstack.spaceharvester.application.extraction.DocumentAnalysis;
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
import java.util.regex.Pattern;

/**
 * Prüft einen Sachverhalt nach Grundsatz und Ausnahme (ADR 0035, Abschnitt 6): deterministisch und ohne Seiteneffekte.
 * Es zählen nur belegte Dokumentfakten (EXPLICIT oder DERIVED und gegen den Text geprüft); ein CONFLICT entscheidet
 * nichts. Eine Ausnahme verdrängt den Grundsatz nur, wenn Sachverhaltsbezug, Wirksamkeit und Rechtswirkung nachgewiesen
 * sind; ein Antrag ist nie eine Genehmigung. Die Standardfrist ist nur nach ausdrücklichem Abschluss der
 * Ausnahmenprüfung bestätigt. Das Entstehungsjahr kommt nie aus dem Erlassdatum, sondern nur als belegte Angabe.
 *
 * <p>Eine Einzelgenehmigung (§ 60 Abs. 1 Satz 2 BHO, Nr. 8 Satz 5 VO/VW-RiB) wirkt nur, wenn das Dokument vom
 * Bundesministerium der Finanzen stammt (Angelo, 23.11.2026). Spricht ein anderer Aussteller die Verlängerung aus oder
 * beruft er sich nur auf eine Genehmigung des BMF, bleibt die Rechtswirkung offen: „Genehmigungsbefugnis ungeklärt“
 * bzw. „Genehmigung behauptet, Nachweis prüfen“.
 */
public final class DeadlineRuleEvaluator {

    /** Wer Einzelgenehmigungen erteilen darf: das BMF (§ 60 Abs. 1 Satz 2 BHO, Nr. 8 Satz 5 VO/VW-RiB). */
    private static final Pattern GRANTING_AUTHORITY = Pattern.compile(
            "(?i)\\bBMF\\b|Bundesministerium\\s+der\\s+Finanzen|Bundesfinanzministerium");
    static final String ISSUER_UNPROVEN = "Aussteller nicht belegt; Genehmigungsbefugnis ungeklärt";
    static final String ISSUER_NOT_COMPETENT = "Genehmigungsbefugnis ungeklärt: Aussteller ist nicht das BMF";
    static final String GRANT_CLAIMED = "Genehmigung des BMF behauptet, Nachweis prüfen";

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
     * @param issuer              belegter Aussteller des Dokuments ({@link #issuer(DocumentAnalysis)}), sonst leer
     */
    public record Request(DocumentRef document, AnalysisSection matter, Optional<Integer> originYear,
                          LegalKnowledgeRepository.State knowledge, String knowledgeGeneration,
                          Optional<ExceptionCheckClosure> closure, Optional<String> issuer) {

        /** Ohne belegten Aussteller: Eine Einzelgenehmigung bleibt dann offen. */
        public Request(DocumentRef document, AnalysisSection matter, Optional<Integer> originYear,
                       LegalKnowledgeRepository.State knowledge, String knowledgeGeneration,
                       Optional<ExceptionCheckClosure> closure) {
            this(document, matter, originYear, knowledge, knowledgeGeneration, closure, Optional.empty());
        }

        public Request {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(matter, "matter");
            originYear = originYear == null ? Optional.empty() : originYear;
            Objects.requireNonNull(knowledge, "knowledge");
            knowledgeGeneration = knowledgeGeneration == null ? "" : knowledgeGeneration;
            closure = closure == null ? Optional.empty() : closure;
            issuer = issuer == null ? Optional.empty() : issuer.filter(i -> !i.isBlank());
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
            ExceptionEvidence evidence = evidence(rule, categories, granted, requested, request.originYear(),
                    request.issuer());
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
                                              List<ExtractedField> requested, Optional<Integer> originYear,
                                              Optional<String> issuer) {
        if (rule.individual()) {
            if (!granted.isEmpty()) {
                // Der Sachverhaltsbezug folgt aus dem Abschnitt; die Rechtswirkung aus der Befugnis des Ausstellers.
                Optional<String> doubt = competence(issuer, granted);
                List<String> evidence = new ArrayList<>(quotes(granted));
                doubt.ifPresent(evidence::add);
                return new ExceptionEvidence(rule.ref(), Proof.PROVEN, Proof.PROVEN,
                        doubt.isEmpty() ? Proof.PROVEN : Proof.OPEN, evidence);
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

    /** Leer, wenn das BMF selbst genehmigt; sonst der Grund, warum die Rechtswirkung offen bleibt. */
    private static Optional<String> competence(Optional<String> issuer, List<ExtractedField> granted) {
        if (issuer.filter(i -> GRANTING_AUTHORITY.matcher(i).find()).isPresent()) {
            return Optional.empty();
        }
        if (quotes(granted).stream().anyMatch(q -> GRANTING_AUTHORITY.matcher(q).find())) {
            return Optional.of(GRANT_CLAIMED);
        }
        return Optional.of(issuer.isEmpty() ? ISSUER_UNPROVEN : ISSUER_NOT_COMPETENT + " („" + issuer.get() + "“)");
    }

    /**
     * Der belegte Aussteller eines Dokuments aus allen Abschnitten; leer, wenn keiner belegt ist oder belegte
     * Angaben voneinander abweichen.
     */
    public static Optional<String> issuer(DocumentAnalysis analysis) {
        Set<String> issuers = new LinkedHashSet<>();
        analysis.sections().forEach(section -> issuers.addAll(values(section, DeadlineFieldKeys.ISSUER)));
        return issuers.size() == 1 ? Optional.of(issuers.iterator().next()) : Optional.empty();
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
