package com.aresstack.spaceharvester.application.enrichment.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.passages.OpenNlpSentenceSegmenter;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code StatementAnalyzerTest} des Enterprise AI Client (#108), mit dem Chat-Port des Core. */
class StatementAnalyzerTest {

    /** Das Beispiel aus Issue #108. Wörter: 1 Die 2 zuständige 3 Sachbearbeitung 4 prüft 5 den 6 eingereichten 7 Beleg
     * 8 und 9 löst 10 anschließend 11 die 12 Zahlung 13 aus. */
    static final String SENTENCE = "Die zuständige Sachbearbeitung prüft den eingereichten Beleg und löst "
            + "anschließend die Zahlung aus.";

    static final String MENTIONS = "\"mentions\": ["
            + "{\"id\": \"m1\", \"sentence\": 1, \"from\": 1, \"to\": 3, \"text\": \"Die zuständige Sachbearbeitung\", "
            + "\"lemma\": \"Sachbearbeitung\", \"pronoun\": false},"
            + "{\"id\": \"m2\", \"sentence\": 1, \"from\": 5, \"to\": 7, \"text\": \"den eingereichten Beleg\", "
            + "\"lemma\": \"Beleg\", \"pronoun\": false},"
            + "{\"id\": \"m3\", \"sentence\": 1, \"from\": 11, \"to\": 12, \"text\": \"die Zahlung\", "
            + "\"lemma\": \"Zahlung\", \"pronoun\": false}]";

    static final String ANSWER = "{" + MENTIONS + ", \"events\": ["
            + "{\"id\": \"e1\", \"sentence\": 1, \"predicate\": {\"from\": 4, \"to\": 4, \"text\": \"prüft\"}, "
            + "\"particle\": null, \"lemma\": \"prüfen\", \"participants\": [{\"role\": \"ACTOR\", \"mention\": \"m1\"}, "
            + "{\"role\": \"AFFECTED_OBJECT\", \"mention\": \"m2\"}], \"negation\": null, \"modality\": null, "
            + "\"condition\": null},"
            + "{\"id\": \"e2\", \"sentence\": 1, \"predicate\": {\"from\": 9, \"to\": 9, \"text\": \"löst\"}, "
            + "\"particle\": {\"from\": 13, \"to\": 13, \"text\": \"aus\"}, \"lemma\": \"auslösen\", "
            + "\"participants\": [{\"role\": \"ACTOR\", \"mention\": \"m1\"}, {\"role\": \"AFFECTED_OBJECT\", "
            + "\"mention\": \"m3\"}], \"negation\": null, \"modality\": null, \"condition\": null}],"
            + "\"relations\": ["
            + "{\"id\": \"r1\", \"sentence\": 1, \"kind\": \"ATTRIBUTE\", \"subject\": \"m1\", "
            + "\"value\": {\"from\": 2, \"to\": 2, \"text\": \"zuständige\"}, \"lemma\": \"zuständig\"},"
            + "{\"id\": \"r2\", \"sentence\": 1, \"kind\": \"STATE\", \"subject\": \"m2\", "
            + "\"value\": {\"from\": 6, \"to\": 6, \"text\": \"eingereichten\"}, \"lemma\": \"eingereicht\"}],"
            + "\"order\": [{\"before\": \"e1\", \"after\": \"e2\", \"sentence\": 1, "
            + "\"cue\": {\"from\": 10, \"to\": 10, \"text\": \"anschließend\"}}]}";

    /** Satzgrenzen wie im Betrieb: OpenNLP, sonst der Splitter des Client. */
    static final SentenceSegmenter SENTENCES = OpenNlpSentenceSegmenter.german(
            SentenceSegmenter.splitter(KnowledgeTextSegmentation.defaults()));

    private final FakeStatementChat chat = new FakeStatementChat();

    @Test
    void exampleSentenceYieldsTwoEventsWithTheSameActor() {
        chat.enqueueAnswer("Hier ist das Ergebnis:\n```json\n" + ANSWER + "\n```");
        DocumentStatements statements = analyzer(false).analyze(SENTENCE, settings(false));

        assertThat(statements.mentions()).hasSize(3);
        assertThat(statements.events()).hasSize(2);
        Event check = statements.events().get(0);
        Event release = statements.events().get(1);
        assertThat(check.normalized()).isEqualTo("prüfen");
        assertThat(release.normalized()).isEqualTo("auslösen");
        assertThat(release.predicateText()).isEqualTo("löst … aus");
        assertThat(only(check.participants(ParticipantRole.ACTOR)).normalized()).isEqualTo("sachbearbeitung");
        assertThat(only(check.participants(ParticipantRole.AFFECTED_OBJECT)).normalized()).isEqualTo("beleg");
        // Das gemeinsame Subjekt ist in beiden Handlungen dasselbe Vorkommen; der Beleg ist nie Akteur der Zahlung.
        assertThat(only(check.participants(ParticipantRole.ACTOR)))
                .isSameAs(only(release.participants(ParticipantRole.ACTOR)));
        assertThat(only(release.participants(ParticipantRole.AFFECTED_OBJECT)).normalized()).isEqualTo("zahlung");
        assertThat(release.participants()).noneMatch(p -> p.mention().normalized().equals("beleg"));
        assertThat(only(check.participants(ParticipantRole.ACTOR)).surface().text())
                .isEqualTo("Die zuständige Sachbearbeitung");
        assertThat(check.status()).isEqualTo(ExtractionStatus.EXTRACTED);

        assertThat(statements.relations()).hasSize(3);
        Relation order = statements.relations().get(2);
        assertThat(order.kind()).isEqualTo(RelationKind.BEFORE);
        assertThat(order.subjectEvent()).isSameAs(check);
        assertThat(order.objectEvent()).isSameAs(release);
        assertThat(order.literal().text()).isEqualTo("anschließend");
        assertThat(statements.relations().get(1).normalized()).isEqualTo("eingereicht");
        assertThat(statements.rejections()).isEmpty();
        assertThat(chat.requests()).hasSize(1);
    }

    @Test
    void wordsNotInTheSentenceAreDiscardedWithEverythingBuiltOnThem() {
        chat.enqueueAnswer(ANSWER.replace("\"text\": \"die Zahlung\"", "\"text\": \"die Rechnung\""));
        DocumentStatements statements = analyzer(false).analyze(SENTENCE, settings(false));

        assertThat(statements.mentions()).hasSize(2);
        assertThat(statements.events()).extracting(Event::normalized).containsExactly("prüfen");
        assertThat(statements.rejections()).containsEntry("words-not-in-sentence", 1)
                .containsEntry("unknown-mention", 1)
                // Die Folge braucht die verworfene Handlung.
                .containsEntry("unknown-event", 1);
    }

    @Test
    void wrongWordNumbersAreRepairedByTheUniqueText() {
        chat.enqueueAnswer(ANSWER.replace("\"from\": 11, \"to\": 12", "\"from\": 10, \"to\": 11"));
        DocumentStatements statements = analyzer(false).analyze(SENTENCE, settings(false));

        assertThat(statements.mentions()).hasSize(3);
        assertThat(statements.mentions().get(2).surface().text()).isEqualTo("die Zahlung");
    }

    @Test
    void deniedEventIsDroppedTogetherWithItsOrder() {
        chat.enqueueAnswer(ANSWER);
        chat.enqueueAnswer("JA");     // e1
        chat.enqueueAnswer("NEIN");   // e2
        chat.enqueueAnswer("JA");     // r1
        chat.enqueueAnswer("UNSICHER, der Satz ist mehrdeutig"); // r2
        DocumentStatements statements = analyzer(true).analyze(SENTENCE, settings(true));

        assertThat(statements.events()).singleElement().extracting(Event::status)
                .isEqualTo(ExtractionStatus.MODEL_VALIDATED);
        assertThat(statements.relations()).extracting(Relation::status)
                .containsExactly(ExtractionStatus.MODEL_VALIDATED, ExtractionStatus.UNCERTAIN);
        assertThat(statements.rejections()).containsEntry("check-denied", 1).containsEntry("order-without-event", 1);
        assertThat(chat.requests()).hasSize(5);
        String claim = chat.requests().get(2).get(1).content();
        assertThat(claim).contains("Handlung: auslösen.").contains("Wer handelt: Die zuständige");
        assertThat(chat.requests().get(2).getFirst().content()).isEqualTo(ChatStatementVerifier.INSTRUCTION);
    }

    @Test
    void pronounsStayUnresolvedAndAreNotChecked() {
        String text = SENTENCE + " Sie informiert danach den Antragsteller.";
        // Zweiter Satz: 1 Sie 2 informiert 3 danach 4 den 5 Antragsteller
        chat.enqueueAnswer("{\"mentions\": [{\"id\": \"m1\", \"sentence\": 2, \"from\": 1, \"to\": 1, \"text\": "
                + "\"Sie\", \"pronoun\": true}, {\"id\": \"m2\", \"sentence\": 2, \"from\": 4, \"to\": 5, \"text\": "
                + "\"den Antragsteller\", \"lemma\": \"Antragsteller\"}], \"events\": [{\"id\": \"e1\", "
                + "\"sentence\": 2, \"predicate\": {\"from\": 2, \"to\": 2, \"text\": \"informiert\"}, \"lemma\": "
                + "\"informieren\", \"participants\": [{\"role\": \"ACTOR\", \"mention\": \"m1\"}, {\"role\": "
                + "\"RECIPIENT\", \"mention\": \"m2\"}]}]}");
        DocumentStatements statements = analyzer(true).analyze(text, settings(true));

        assertThat(statements.mentions().getFirst().status()).isEqualTo(ExtractionStatus.UNRESOLVED_REFERENCE);
        assertThat(statements.mentions().getFirst().lemmaCandidate()).isNull();
        assertThat(statements.events().getFirst().status()).isEqualTo(ExtractionStatus.UNRESOLVED_REFERENCE);
        assertThat(chat.requests()).hasSize(1);
    }

    @Test
    void participantFromAnotherSentenceRejectsTheEvent() {
        String text = SENTENCE + " Danach wird informiert.";
        chat.enqueueAnswer("{" + MENTIONS + ", \"events\": [{\"id\": \"e1\", \"sentence\": 2, \"predicate\": "
                + "{\"from\": 3, \"to\": 3, \"text\": \"informiert\"}, \"lemma\": \"informieren\", \"participants\": "
                + "[{\"role\": \"ACTOR\", \"mention\": \"m1\"}]}]}");
        DocumentStatements statements = analyzer(false).analyze(text, settings(false));

        assertThat(statements.events()).isEmpty();
        assertThat(statements.rejections()).containsEntry("other-sentence", 1);
    }

    @Test
    void negationMustBeGroundedAndIsKept() {
        String text = "Die Sachbearbeitung löst die Zahlung nicht aus.";
        // 1 Die 2 Sachbearbeitung 3 löst 4 die 5 Zahlung 6 nicht 7 aus
        String answer = "{\"mentions\": [{\"id\": \"m1\", \"sentence\": 1, \"from\": 1, \"to\": 2, \"text\": "
                + "\"Die Sachbearbeitung\", \"lemma\": \"Sachbearbeitung\"}, {\"id\": \"m2\", \"sentence\": 1, "
                + "\"from\": 4, \"to\": 5, \"text\": \"die Zahlung\", \"lemma\": \"Zahlung\"}], \"events\": [{\"id\": "
                + "\"e1\", \"sentence\": 1, \"predicate\": {\"from\": 3, \"to\": 3, \"text\": \"löst\"}, \"particle\": "
                + "{\"from\": 7, \"to\": 7, \"text\": \"aus\"}, \"lemma\": \"auslösen\", \"participants\": [{\"role\": "
                + "\"ACTOR\", \"mention\": \"m1\"}, {\"role\": \"AFFECTED_OBJECT\", \"mention\": \"m2\"}], "
                + "\"negation\": {\"from\": 6, \"to\": 6, \"text\": \"NEGATION\"}}]}";
        chat.enqueueAnswer(answer.replace("NEGATION", "nicht"));
        Event event = analyzer(false).analyze(text, settings(false)).events().getFirst();
        assertThat(event.context().negation().text()).isEqualTo("nicht");

        chat.enqueueAnswer(answer.replace("NEGATION", "niemals"));
        assertThat(analyzer(false).analyze(text, settings(false)).events()).isEmpty();
    }

    @Test
    void equalTermsStayTwoMentions() {
        String text = "Die Stelle prüft die Zahlung und bucht die Zahlung.";
        // 1 Die 2 Stelle 3 prüft 4 die 5 Zahlung 6 und 7 bucht 8 die 9 Zahlung
        chat.enqueueAnswer("{\"mentions\": [{\"id\": \"m1\", \"sentence\": 1, \"from\": 4, \"to\": 5, \"text\": "
                + "\"die Zahlung\", \"lemma\": \"Zahlung\"}, {\"id\": \"m2\", \"sentence\": 1, \"from\": 8, \"to\": 9, "
                + "\"text\": \"die Zahlung\", \"lemma\": \"Zahlung\"}]}");
        DocumentStatements statements = analyzer(false).analyze(text, settings(false));

        assertThat(statements.mentions()).hasSize(2);
        assertThat(statements.mentions().get(0).surface().span())
                .isNotEqualTo(statements.mentions().get(1).surface().span());
    }

    @Test
    void unreadableAnswerIsRepairedOnce() {
        chat.enqueueAnswer("Ich habe den Satz analysiert.");
        chat.enqueueAnswer(ANSWER);
        DocumentStatements statements = analyzer(false).analyze(SENTENCE, settings(false));

        assertThat(statements.events()).hasSize(2);
        assertThat(chat.requests()).hasSize(2);
        assertThat(chat.requests().get(1)).hasSize(4);
        assertThat(chat.requests().get(1).get(3).content()).isEqualTo(ChatStatementExtractor.REPAIR);
    }

    @Test
    void unreadableAfterRepairCountsAsFailedUnit() {
        chat.enqueueAnswer("kein JSON");
        chat.enqueueAnswer("{\"weder\": 1}");
        DocumentStatements statements = analyzer(false).analyze(SENTENCE, settings(false));

        assertThat(statements.failedUnits()).isEqualTo(1);
        assertThat(statements.analysedUnits()).isZero();
    }

    @Test
    void parseCountsMalformedEntries() {
        StatementCandidates candidates = ChatStatementExtractor.parse("{\"mentions\": [{\"id\": \"m1\"}, 7], "
                + "\"events\": []}");
        assertThat(candidates.malformed()).isEqualTo(2);
        assertThatThrownBy(() -> ChatStatementExtractor.parse("[]"))
                .isInstanceOf(StatementExtractionException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("[]"));
    }

    @Test
    void verdictParsing() {
        assertThat(ChatStatementVerifier.parse(" Ja.")).isEqualTo(StatementVerifier.Verdict.YES);
        assertThat(ChatStatementVerifier.parse("**NEIN**")).isEqualTo(StatementVerifier.Verdict.NO);
        assertThat(ChatStatementVerifier.parse("Vielleicht")).isEqualTo(StatementVerifier.Verdict.UNSURE);
        assertThat(ChatStatementVerifier.parse(null)).isEqualTo(StatementVerifier.Verdict.UNSURE);
    }

    @Test
    void codeAndTablesAreNoParagraphs() {
        String text = "# Überschrift\n\n| a | b |\n|---|---|\n\n```\nDie Klasse ruft die Methode auf.\n```\n\n"
                + SENTENCE + "\n\nNUR GROSSBUCHSTABEN IN DIESER ZEILE HIER";
        List<ParagraphText> units = ParagraphText.split(text, SENTENCES, 1500);
        assertThat(units).hasSize(1);
        assertThat(units.getFirst().text()).isEqualTo(SENTENCE);
        assertThat(units.getFirst().span().start()).isEqualTo(text.indexOf(SENTENCE));
    }

    @Test
    void versionChangesWithModelAndLimits() {
        StatementAnalyzer analyzer = analyzer(true);
        String version = analyzer.version(settings(true));
        assertThat(version).contains("chat-json/1/test-model").contains("chat-yes-no/1/test-model")
                .contains("sentences=" + SENTENCES.id()).contains("pos=none");
        assertThat(analyzer.version(new StatementSettings(true, true, 10, 1500))).isNotEqualTo(version);
        assertThat(analyzer.version(settings(false))).isNotEqualTo(version);
        FakeStatementChat other = new FakeStatementChat("other-model");
        StatementAnalyzer otherModel = new StatementAnalyzer(SENTENCES, new ChatStatementExtractor(() -> other),
                PredicateCheck.none(), new ChatStatementVerifier(() -> other, () -> true));
        assertThat(otherModel.version(settings(true))).isNotEqualTo(version).contains("other-model");
    }

    @Test
    void withoutModelTheExtractorIsUnavailableAndSendsNothing() {
        ChatStatementExtractor extractor = new ChatStatementExtractor(() -> null);
        assertThat(extractor.isAvailable()).isFalse();
        assertThatThrownBy(() -> extractor.extract(ParagraphText.of(SENTENCE, SENTENCES)))
                .isInstanceOf(StatementExtractionException.class);
        assertThat(new ChatStatementVerifier(() -> null, () -> true).verify("Satz", "Aussage"))
                .isEqualTo(StatementVerifier.Verdict.UNAVAILABLE);
    }

    private StatementAnalyzer analyzer(boolean verify) {
        ChatStatementExtractor extractor = new ChatStatementExtractor(() -> chat);
        ChatStatementVerifier verifier = new ChatStatementVerifier(() -> chat, () -> verify);
        return new StatementAnalyzer(SENTENCES, extractor, PredicateCheck.none(), verifier);
    }

    static StatementSettings settings(boolean verify) {
        return new StatementSettings(true, verify, StatementSettings.DEFAULT_MAX_PARAGRAPHS,
                StatementSettings.DEFAULT_MAX_PARAGRAPH_CHARS);
    }

    private static Mention only(List<Mention> mentions) {
        assertThat(mentions).hasSize(1);
        return mentions.getFirst();
    }
}
