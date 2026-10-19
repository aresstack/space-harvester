package com.aresstack.spaceharvester.application.enrichment.statements;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Die Satzanalyse aus Issue #108 ohne Ablage (unverändert aus dem Enterprise AI Client): die Textprojektion in Absätze
 * und Sätze zerlegen, den {@link StatementExtractor} je Absatz vorschlagen lassen, behalten, was der
 * {@link StatementValidator} annimmt, und jede übrige Handlung und Relation der optionalen Ja/Nein-Prüfung vorlegen.
 * Verneinte Aussagen fallen weg (mit jeder Folge, die sie braucht); unentschiedene bleiben
 * {@link ExtractionStatus#UNCERTAIN}; unaufgelöste Bezüge werden nicht geprüft. Blockiert auf Modellaufrufen.
 */
public final class StatementAnalyzer {

    /** Version der Kette selbst. */
    public static final String VERSION = "1";

    private final SentenceSegmenter segmenter;
    private final StatementExtractor extractor;
    private final PredicateCheck predicates;
    private final StatementValidator validator;
    private final StatementVerifier verifier;

    public StatementAnalyzer(SentenceSegmenter segmenter, StatementExtractor extractor, PredicateCheck predicates,
                             StatementVerifier verifier) {
        this.segmenter = Objects.requireNonNull(segmenter, "segmenter");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.predicates = predicates == null ? PredicateCheck.none() : predicates;
        this.validator = new StatementValidator(this.predicates);
        this.verifier = verifier == null ? StatementVerifier.none() : verifier;
    }

    public boolean isAvailable() {
        return extractor.isAvailable();
    }

    /** Alles, was das Ergebnis ändert: Kette, Extraktor, Prüfung, Satz- und Wortartmodell, Grenzen. */
    public String version(StatementSettings settings) {
        return VERSION + ";extractor=" + extractor.version() + ";check="
                + (settings.verify() ? verifier.version() : "none") + ";sentences=" + segmenter.id() + ";pos="
                + predicates.id() + ";rules=" + StatementValidator.VERSION + ";terms=" + TermNormalizer.VERSION
                + ";paragraphs=" + settings.maxParagraphs() + "/" + settings.maxParagraphChars();
    }

    /** Analysiert einen ganzen Text ohne Abschnitte (Tests, Auswertung). */
    public DocumentStatements analyze(String documentText, StatementSettings settings) {
        return analyze(ParagraphText.split(documentText, segmenter, settings.maxParagraphChars()), settings);
    }

    /** Analysiert die Textprojektion einer Seite. */
    public DocumentStatements analyze(PageText text, StatementSettings settings) {
        return analyze(ParagraphText.split(text, segmenter, settings.maxParagraphChars()), settings);
    }

    private DocumentStatements analyze(List<ParagraphText> units, StatementSettings settings) {
        int skipped = Math.max(0, units.size() - settings.maxParagraphs());
        return analyze(units.subList(0, units.size() - skipped), skipped, settings.verify());
    }

    /** Analysiert gegebene Einheiten. */
    public DocumentStatements analyze(List<ParagraphText> units, int skipped, boolean verify) {
        Collector all = new Collector();
        int analysed = 0;
        int failed = 0;
        for (ParagraphText unit : units) {
            StatementCandidates candidates;
            try {
                candidates = extractor.extract(unit);
            } catch (StatementExtractionException e) {
                failed++;
                continue;
            }
            analysed++;
            StatementValidator.Result result = validator.validate(unit, candidates, "p" + unit.index() + ".");
            all.add(result);
            if (verify && verifier.isEnabled()) {
                check(unit, result, all);
            } else {
                all.events.addAll(result.events());
                all.relations.addAll(result.relations());
            }
        }
        return new DocumentStatements(all.mentions, all.events, all.relations, analysed, failed, skipped,
                all.rejections, all.verdicts);
    }

    private void check(ParagraphText unit, StatementValidator.Result result, Collector all) {
        Set<String> dropped = new HashSet<>();
        Map<String, Event> kept = new TreeMap<>();
        String text = unit.documentText();
        for (Event event : result.events()) {
            if (event.status() != ExtractionStatus.EXTRACTED) {
                all.events.add(event);
                kept.put(event.key(), event);
                continue;
            }
            Event checked = apply(event, verdict(event.sentence().of(text), claim(event), all));
            if (checked == null) {
                dropped.add(event.key());
            } else {
                all.events.add(checked);
                kept.put(checked.key(), checked);
            }
        }
        for (Relation relation : result.relations()) {
            if (relation.kind() == RelationKind.BEFORE) {
                if (dropped.contains(relation.subjectEvent().key()) || dropped.contains(relation.objectEvent().key())) {
                    all.reject("order-without-event");
                    continue;
                }
                relation = Relation.order(relation.key(), relation.sentence(), kept.get(relation.subjectEvent().key()),
                        kept.get(relation.objectEvent().key()), relation.literal(), relation.status());
            }
            if (relation.status() != ExtractionStatus.EXTRACTED) {
                all.relations.add(relation);
                continue;
            }
            StatementVerifier.Verdict verdict = verdict(relation.sentence().of(text), claim(relation), all);
            if (verdict != StatementVerifier.Verdict.NO) {
                all.relations.add(relation.withStatus(status(verdict, relation.status())));
            }
        }
    }

    private Event apply(Event event, StatementVerifier.Verdict verdict) {
        return verdict == StatementVerifier.Verdict.NO ? null : event.withStatus(status(verdict, event.status()));
    }

    private StatementVerifier.Verdict verdict(String sentence, String claim, Collector all) {
        StatementVerifier.Verdict verdict = verifier.verify(sentence, claim);
        all.verdicts.merge(verdict, 1, Integer::sum);
        if (verdict == StatementVerifier.Verdict.NO) {
            all.reject("check-denied");
        }
        return verdict;
    }

    private static ExtractionStatus status(StatementVerifier.Verdict verdict, ExtractionStatus current) {
        return switch (verdict) {
            case YES -> ExtractionStatus.MODEL_VALIDATED;
            case UNSURE -> ExtractionStatus.UNCERTAIN;
            default -> current;
        };
    }

    /** Die Aussage einer Handlung in Worten, wie die Prüfung sie sieht. */
    static String claim(Event event) {
        StringBuilder claim = new StringBuilder("Handlung: ").append(event.lemmaCandidate() != null
                ? event.lemmaCandidate() : event.predicateText()).append('.');
        for (Participant participant : event.participants()) {
            claim.append(' ').append(label(participant.role())).append(": ")
                    .append(participant.mention().surface().text()).append('.');
        }
        return claim.append(context(event.context())).toString();
    }

    /** Die Aussage einer Relation in Worten, wie die Prüfung sie sieht. */
    static String claim(Relation relation) {
        if (relation.kind() == RelationKind.BEFORE) {
            return "Die Handlung „" + relation.subjectEvent().predicateText() + "“ geschieht vor der Handlung „"
                    + relation.objectEvent().predicateText() + "“.";
        }
        return relation.subjectMention().surface().text() + " hat "
                + (relation.kind() == RelationKind.STATE ? "den Zustand" : "die Eigenschaft") + " „"
                + relation.literal().text() + "“." + context(relation.context());
    }

    private static String context(StatementContext context) {
        StringBuilder text = new StringBuilder();
        if (context.negation() != null) {
            text.append(" Verneint durch: ").append(context.negation().text()).append('.');
        }
        if (context.modality() != null) {
            text.append(" Modalität: ").append(context.modality().text()).append('.');
        }
        if (context.condition() != null) {
            text.append(" Nur unter der Bedingung: ").append(context.condition().text()).append('.');
        }
        return text.toString();
    }

    private static String label(ParticipantRole role) {
        return switch (role) {
            case ACTOR -> "Wer handelt";
            case AFFECTED_OBJECT -> "Worauf sich die Handlung richtet";
            case RECIPIENT -> "Wer etwas erhält";
            case INSTRUMENT -> "Womit";
            default -> "Wo";
        };
    }

    /** Sammelt die Ergebnisse aller Einheiten. */
    private static final class Collector {
        final List<Mention> mentions = new ArrayList<>();
        final List<Event> events = new ArrayList<>();
        final List<Relation> relations = new ArrayList<>();
        final Map<String, Integer> rejections = new TreeMap<>();
        final Map<StatementVerifier.Verdict, Integer> verdicts = new EnumMap<>(StatementVerifier.Verdict.class);

        void add(StatementValidator.Result result) {
            mentions.addAll(result.mentions());
            result.rejections().forEach(this::reject);
        }

        void reject(String reason) {
            reject(reason, 1);
        }

        void reject(String reason, int count) {
            rejections.merge(reason, count, Integer::sum);
        }
    }
}
