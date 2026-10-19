package com.aresstack.spaceharvester.application.enrichment.statements;

import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Der Prozessor {@code statements}: die Satzanalyse des Enterprise AI Client (#108, {@link StatementAnalyzer}) als
 * Anreicherung, zusätzlich zum regelbasierten Weg ({@code entities}, {@code activities}, {@code relations}). Seine
 * Ergebnisse werden zu Fakten {@code MENTION}, {@code EVENT} und {@code RELATION} ({@code EnrichmentFacts}).
 *
 * <p>Standardmäßig aus ({@link StatementSettings#DEFAULT_ENABLED}). Er bearbeitet eine Seite nur, wenn die Analyse
 * eingeschaltet ist, ein Chat-Modell eingestellt ist und die Quelle in {@code external-processing-sources} steht
 * ({@link #appliesTo}, ADR 0011); {@link #process(PageKey, PageText)} prüft das noch einmal, bevor etwas an das Modell
 * geht. Er rechnet im Hintergrund der Anreicherungen ({@link #background()}), also nach einem Abgleich oder Abruf,
 * nie beim Lesen. Die {@link #version()} enthält Kette, Prompts, Modell, Satzmodell und Grenzen
 * ({@link StatementAnalyzer#version}): ein anderes Chat-Modell macht die Ergebnisse veraltet. Scheitert ein Absatz,
 * scheitert das ganze Ergebnis (wie {@code INCOMPLETE} im Client: kein halbes Ergebnis als aktuell).
 *
 * <p>Ergebnis: ein JSON-Objekt mit {@code mentions} (key, surface, lemmaCandidate, normalized, normalization, pronoun,
 * status), {@code events} (key, predicate, parts, lemmaCandidate, normalized, normalization, verbal, participants mit
 * role, mention, term, normalized, pronoun; negation, modality, condition, status), {@code relations} (key, kind,
 * subject, object, value, lemmaCandidate, normalized, normalization, negation, modality, condition, status), je mit
 * section, charStart und charEnd, und {@code diagnostics} (nur Zähler). Bereiche sind Stellen in der Textprojektion:
 * bei Erwähnungen die Wörter, bei Handlungen der Satz, bei Relationen die Eigenschaftswörter bzw. das Signalwort.
 */
public final class StatementProcessor implements EnrichmentProcessor {

    public static final String ID = "statements";
    /** Version des Ergebnisformats; Teil der {@link #version()}. */
    static final String PAYLOAD = "1";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SentenceSegmenter segmenter;
    private final Supplier<StatementSettings> settings;
    private final Supplier<StatementChat> chat;
    private final Predicate<String> mayProcessExternally;

    /**
     * @param segmenter            Satzgrenzen, produktiv {@code OpenNlpSentenceSegmenter.german(…)} (ADR 0020)
     * @param settings             Schalter und Grenzen, je Seite gelesen
     * @param chat                 das eingestellte Chat-Modell oder {@code null}, je Seite gelesen
     * @param mayProcessExternally ob Inhalte der Quelle an den KI-Dienst gehen dürfen; je Seite gefragt
     */
    public StatementProcessor(SentenceSegmenter segmenter, Supplier<StatementSettings> settings,
                              Supplier<StatementChat> chat, Predicate<String> mayProcessExternally) {
        this.segmenter = Objects.requireNonNull(segmenter, "segmenter");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.chat = Objects.requireNonNull(chat, "chat");
        this.mayProcessExternally = Objects.requireNonNull(mayProcessExternally, "mayProcessExternally");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        StatementChat model = chat.get();
        if (ChatStatementExtractor.model(model) == null) {
            return "off";
        }
        StatementSettings current = settings();
        return version(analyzer(model, current), current);
    }

    private static String version(StatementAnalyzer analyzer, StatementSettings settings) {
        return analyzer.version(settings) + ";payload=" + PAYLOAD;
    }

    /** Ob die Analyse jetzt laufen kann: eingeschaltet und ein Chat-Modell eingestellt. */
    public boolean available() {
        return settings().enabled() && ChatStatementExtractor.model(chat.get()) != null;
    }

    @Override
    public boolean appliesTo(PageKey key) {
        return available() && mayProcessExternally.test(key.sourceId());
    }

    @Override
    public boolean background() {
        return true;
    }

    /** Ohne Seite ist die Freigabe der Quelle unbekannt: dann geht nichts an das Modell. */
    @Override
    public EnrichmentOutput process(PageText text) {
        throw new IllegalStateException("statements braucht die Quelle der Seite");
    }

    @Override
    public EnrichmentOutput process(PageKey key, PageText text) {
        // Ein Modell und eine Einstellung für den ganzen Lauf, auch wenn die Konfiguration währenddessen wechselt.
        StatementSettings current = settings();
        StatementChat model = chat.get();
        if (!current.enabled() || ChatStatementExtractor.model(model) == null
                || !mayProcessExternally.test(key.sourceId())) {
            throw new IllegalStateException("statements ist aus, ohne Chat-Modell oder für diese Quelle nicht "
                    + "freigegeben");
        }
        StatementAnalyzer analyzer = analyzer(model, current);
        String version = version(analyzer, current);
        DocumentStatements statements = analyzer.analyze(text, current);
        if (statements.failedUnits() > 0) {
            throw new StatementExtractionException(statements.failedUnits() + " von "
                    + (statements.failedUnits() + statements.analysedUnits()) + " Absätzen ohne Antwort");
        }
        List<String> warnings = new ArrayList<>();
        if (statements.skippedUnits() > 0) {
            warnings.add("Nur die ersten " + current.maxParagraphs() + " von "
                    + (current.maxParagraphs() + statements.skippedUnits()) + " Absätzen analysiert");
        }
        return new EnrichmentOutput(payload(text, statements), warnings, version);
    }

    private StatementSettings settings() {
        StatementSettings current = settings.get();
        return current == null ? StatementSettings.DEFAULT : current;
    }

    private StatementAnalyzer analyzer(StatementChat model, StatementSettings current) {
        Supplier<StatementChat> fixed = () -> model;
        return new StatementAnalyzer(segmenter, new ChatStatementExtractor(fixed), PredicateCheck.none(),
                new ChatStatementVerifier(fixed, current::verify));
    }

    static String payload(PageText text, DocumentStatements statements) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode mentions = root.putArray("mentions");
        for (Mention mention : statements.mentions()) {
            ObjectNode entry = mentions.addObject();
            entry.put("key", mention.key());
            entry.put("surface", mention.surface().text());
            lemma(entry, mention.lemmaCandidate());
            entry.put("normalized", mention.normalized());
            entry.put("normalization", mention.normalization());
            entry.put("pronoun", mention.pronoun());
            entry.put("status", mention.status().name());
            place(entry, text, mention.surface().span());
        }
        ArrayNode events = root.putArray("events");
        for (Event event : statements.events()) {
            ObjectNode entry = events.addObject();
            entry.put("key", event.key());
            entry.put("predicate", event.predicateText());
            ArrayNode parts = entry.putArray("parts");
            for (GroundedText part : event.predicate()) {
                grounded(parts.addObject(), part);
            }
            lemma(entry, event.lemmaCandidate());
            entry.put("normalized", event.normalized());
            entry.put("normalization", event.normalization());
            if (event.verbal() == null) {
                entry.putNull("verbal");
            } else {
                entry.put("verbal", event.verbal());
            }
            ArrayNode participants = entry.putArray("participants");
            for (Participant participant : event.participants()) {
                ObjectNode copy = participants.addObject();
                copy.put("role", participant.role().name());
                copy.put("mention", participant.mention().key());
                copy.put("term", participant.mention().surface().text());
                copy.put("normalized", participant.mention().normalized());
                copy.put("pronoun", participant.mention().pronoun());
            }
            context(entry, event.context());
            entry.put("status", event.status().name());
            place(entry, text, event.sentence());
        }
        ArrayNode relations = root.putArray("relations");
        for (Relation relation : statements.relations()) {
            ObjectNode entry = relations.addObject();
            entry.put("key", relation.key());
            entry.put("kind", relation.kind().name());
            if (relation.subjectMention() != null) {
                reference(entry.putObject("subject"), relation.subjectMention().key(),
                        relation.subjectMention().surface().text(), relation.subjectMention().normalized());
                entry.putNull("object");
            } else {
                reference(entry.putObject("subject"), relation.subjectEvent().key(),
                        relation.subjectEvent().predicateText(), relation.subjectEvent().normalized());
                reference(entry.putObject("object"), relation.objectEvent().key(),
                        relation.objectEvent().predicateText(), relation.objectEvent().normalized());
            }
            entry.put("value", relation.literal().text());
            lemma(entry, relation.lemmaCandidate());
            entry.put("normalized", relation.normalized());
            entry.put("normalization", relation.normalization());
            context(entry, relation.context());
            entry.put("status", relation.status().name());
            place(entry, text, relation.literal().span());
        }
        ObjectNode diagnostics = root.putObject("diagnostics");
        diagnostics.put("analysedUnits", statements.analysedUnits());
        diagnostics.put("failedUnits", statements.failedUnits());
        diagnostics.put("skippedUnits", statements.skippedUnits());
        ObjectNode rejections = diagnostics.putObject("rejections");
        statements.rejections().forEach(rejections::put);
        ObjectNode verdicts = diagnostics.putObject("verdicts");
        statements.verdicts().forEach((verdict, count) -> verdicts.put(verdict.name(), count));
        return root.toString();
    }

    private static void lemma(ObjectNode entry, String lemma) {
        if (lemma == null) {
            entry.putNull("lemmaCandidate");
            return;
        }
        ObjectNode value = entry.putObject("lemmaCandidate");
        value.put("value", lemma);
        value.put("status", TermNormalizer.LEMMA_STATUS);
    }

    private static void reference(ObjectNode node, String key, String term, String normalized) {
        node.put("key", key);
        node.put("term", term);
        node.put("normalized", normalized);
    }

    private static void context(ObjectNode entry, StatementContext context) {
        optional(entry, "negation", context.negation());
        optional(entry, "modality", context.modality());
        optional(entry, "condition", context.condition());
    }

    private static void optional(ObjectNode entry, String field, GroundedText text) {
        if (text == null) {
            entry.putNull(field);
        } else {
            grounded(entry.putObject(field), text);
        }
    }

    private static void grounded(ObjectNode node, GroundedText text) {
        node.put("text", text.text());
        node.put("charStart", text.span().start());
        node.put("charEnd", text.span().end());
    }

    private static void place(ObjectNode entry, PageText text, TextSpan span) {
        PageText.Section section = text.sectionAt(span.start());
        if (section == null) {
            entry.putNull("section");
        } else {
            entry.put("section", section.heading());
        }
        entry.put("charStart", span.start());
        entry.put("charEnd", span.end());
    }
}
