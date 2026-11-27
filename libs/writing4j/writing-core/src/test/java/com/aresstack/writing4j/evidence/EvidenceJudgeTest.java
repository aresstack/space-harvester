package com.aresstack.writing4j.evidence;

import com.aresstack.knowledge4j.model.ThinkingMode;
import com.aresstack.writing4j.evidence.EvidenceJudge.Options;
import com.aresstack.writing4j.evidence.EvidenceJudge.Outcome;
import com.aresstack.writing4j.evidence.EvidenceJudge.Pair;
import com.aresstack.writing4j.evidence.EvidenceJudge.Passage;
import com.aresstack.writing4j.evidence.EvidenceJudge.Question;
import com.aresstack.writing4j.memory.EvidenceVerdict;
import com.aresstack.writing4j.memory.EvidenceVerdict.Kind;
import com.aresstack.writing4j.memory.PassageText;
import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult.Status;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Component 6 with a scripted model: the request, the check after the answer (it only lowers), repair, FEHLER,
 * reuse by text, the breaker and the parallel batch. The texts are made up for this test (road traffic), not taken
 * from any source.
 */
public class EvidenceJudgeTest {

    private static final String MISSION = "Ein Sachbuch für Stadtplaner über Verkehrssicherheit in Innenstädten.";
    private static final String QUESTION = "Wie wirkt Tempo 30 auf die Zahl schwerer Unfälle?";
    private static final String URL = "https://www.stadt.example/verkehr/bericht-2025";
    private static final String TEXT = "Nach Einführung von Tempo 30 in der Innenstadt sank die Zahl der schweren "
            + "Unfälle im ersten Jahr um 30 Prozent. Die Stadt zählte 1.500 Verkehrsunfälle weniger als im Vorjahr. "
            + "Anwohner berichteten zudem von weniger Lärm.";
    private static final String QUOTE = "sank die Zahl der schweren Unfälle im ersten Jahr um 30 Prozent";
    private static final String STATEMENT = "Nach Tempo 30 sanken die schweren Unfälle im ersten Jahr um 30 Prozent.";

    @Test
    public void theRoleHasItsOwnSlotAndAFixedSchema() {
        assertEquals("phase.research.evidence", EvidenceJudge.ROLE.getSlot().getName());
        assertEquals(ThinkingMode.OFF, EvidenceJudge.ROLE.getThinking());
        JsonObject schema = JsonParser.parseString(EvidenceJudge.schema()).getAsJsonObject();
        assertEquals("[\"zitat\",\"aussage\",\"urteil\"]", schema.get("required").toString());
        assertEquals("[zitat, aussage, urteil]", schema.getAsJsonObject("properties").keySet().toString());
        assertEquals("[\"BELEGT\",\"HINWEIS\",\"KEIN\"]",
                schema.getAsJsonObject("properties").getAsJsonObject("urteil").get("enum").toString());
        assertFalse(schema.get("additionalProperties").getAsBoolean());
        assertEquals(schema, JsonParser.parseString(EvidenceJudge.ROLE.getOutput().getSchemaJson()));
    }

    @Test
    public void aBelegtWithAVerbatimQuoteHoldsAndThePromptCarriesTheParts() {
        Scripted chat = new Scripted(m -> answer(QUOTE, STATEMENT, "BELEGT"));
        Outcome outcome = judge(chat, "de", null).judge(pair("F01", QUESTION, "p1", TEXT), 2);

        EvidenceVerdict verdict = outcome.getVerdict();
        assertEquals(Kind.BELEGT, verdict.getKind());
        assertEquals(Kind.BELEGT, verdict.getJudgedKind());
        assertFalse(verdict.isDowngraded());
        assertEquals("F01", verdict.getQuestionId());
        assertEquals("p1", verdict.getPassageId());
        assertEquals("s-p1", verdict.getSourceId());
        assertEquals(URL, verdict.getSourceUrl());
        assertEquals(QUOTE, verdict.getQuote());
        assertEquals(STATEMENT, verdict.getReason());
        assertEquals("test-model", verdict.getModel());
        assertEquals(2, verdict.getRound());
        assertEquals(PassageText.key(TEXT), verdict.getTextKey());
        assertFalse(outcome.isReused());
        assertEquals(1, outcome.getAnswers().size());

        assertEquals(1, chat.calls.get());
        assertEquals(0.0, chat.temperature, 0.0);
        assertEquals(400, chat.tokens);
        assertEquals(EvidenceJudge.schema(), chat.schemas.get(0));
        List<ChatMessage> request = chat.requests.get(0);
        assertEquals(2, request.size());
        assertEquals(EvidenceJudge.SYSTEM_DE, request.get(0).getContent());
        String prompt = request.get(1).getContent();
        assertTrue(prompt.contains(MISSION));
        assertTrue(prompt.contains(QUESTION));
        assertTrue(prompt.contains("(Quelle: stadt.example)"));
        assertTrue(prompt.contains("\"\"\"\n" + TEXT + "\n\"\"\""));
        assertTrue(prompt.contains("Nennt die Aussage eine Zahl, muss diese Zahl im Zitat stehen."));
        assertTrue(prompt.contains("Übernimm die Wörter genau so, wie sie in der Passage stehen."));
        assertTrue(prompt.indexOf("1. Zitat") < prompt.indexOf("2. Aussage"));
        assertTrue(prompt.indexOf("2. Aussage") < prompt.indexOf("3. Urteil"));
        assertFalse(prompt.contains(" …"));
    }

    @Test
    public void theCheckOnlyLowers() {
        // the quote does not stand in the passage
        EvidenceVerdict invented = verdictFor("Die Zahl der Unfälle halbierte sich nach Tempo 30", STATEMENT,
                "BELEGT");
        assertEquals(Kind.BELEGT, invented.getJudgedKind());
        assertEquals(Kind.HINWEIS, invented.getKind());
        assertTrue(invented.isDowngraded());
        // the quote stands, but the statement names a number the quote does not hold
        EvidenceVerdict number = verdictFor("sank die Zahl der schweren Unfälle im ersten Jahr", STATEMENT, "BELEGT");
        assertEquals(Kind.HINWEIS, number.getKind());
        assertTrue(number.isDowngraded());
        // no quote
        assertEquals(Kind.HINWEIS, verdictFor("", "Schwere Unfälle wurden seltener.", "BELEGT").getKind());
        // HINWEIS and KEIN stay as said
        EvidenceVerdict hint = verdictFor("das steht so nirgends in der Passage", "Es geht um Lärm.", "HINWEIS");
        assertEquals(Kind.HINWEIS, hint.getKind());
        assertFalse(hint.isDowngraded());
        assertEquals(Kind.KEIN, verdictFor("", "nichts", "KEIN").getKind());
    }

    @Test
    public void theWordsOfThePlanningComparisonAreUnderstood() {
        assertEquals(Kind.BELEGT, EvidenceJudge.kindOf("BELEGT"));
        assertEquals(Kind.BELEGT, EvidenceJudge.kindOf(" belegt."));
        assertEquals(Kind.BELEGT, EvidenceJudge.kindOf("Ja"));
        assertEquals(Kind.HINWEIS, EvidenceJudge.kindOf("Hinweis"));
        assertEquals(Kind.HINWEIS, EvidenceJudge.kindOf("teilweise"));
        assertEquals(Kind.KEIN, EvidenceJudge.kindOf("kein"));
        assertEquals(Kind.KEIN, EvidenceJudge.kindOf("\"nein\""));
        assertNull(EvidenceJudge.kindOf("vielleicht"));
        assertNull(EvidenceJudge.kindOf(""));
        assertTrue(EvidenceJudge.parse("{\"zitat\":\"a\",\"aussage\":\"b\",\"traegt\":\"ja\"}").problem
                .contains("urteil"));
        assertTrue(EvidenceJudge.parse("[\"BELEGT\"]").problem.contains("no JSON object"));
        assertTrue(EvidenceJudge.parse("BELEGT, weil").problem.contains("no JSON"));
        EvidenceJudge.Parsed parsed = EvidenceJudge.parse(
                "{\"zitat\":\" a \\n b \",\"aussage\":\"c\",\"urteil\":\"ja\"}");
        assertEquals("", parsed.problem);
        assertEquals(Kind.BELEGT, parsed.kind);
        assertEquals("a b", parsed.quote);
    }

    @Test
    public void anUnreadableAnswerGetsOneRepairAttempt() {
        AtomicInteger n = new AtomicInteger();
        Scripted chat = new Scripted(m -> n.getAndIncrement() == 0 ? MainModelChatResult.ok("Die Passage belegt es.")
                : answer(QUOTE, STATEMENT, "BELEGT"));
        Outcome outcome = judge(chat, "de", null).judge(pair("F01", QUESTION, "p1", TEXT), 1);

        assertEquals(Kind.BELEGT, outcome.getVerdict().getKind());
        assertEquals(Arrays.asList("Die Passage belegt es.", answer(QUOTE, STATEMENT, "BELEGT").getText()),
                outcome.getAnswers());
        List<ChatMessage> second = chat.requests.get(1);
        assertEquals(4, second.size());
        assertEquals("Die Passage belegt es.", second.get(2).getContent());
        assertTrue(second.get(3).getContent().startsWith("Deine Antwort war ungültig: the answer is no JSON"));
        assertEquals(2, chat.requests.get(0).size());
    }

    @Test
    public void aFailedPairIsFehlerWithItsReasonNeverKein() {
        Scripted down = new Scripted(m -> MainModelChatResult.failure(Status.TIMEOUT, "Read timed out"));
        Outcome timedOut = judge(down, "de", null).judge(pair("F01", QUESTION, "p1", TEXT), 1);
        assertEquals(Kind.FEHLER, timedOut.getVerdict().getKind());
        assertTrue(timedOut.getVerdict().getReason(), timedOut.getVerdict().getReason()
                .contains("model call failed (TIMEOUT): Read timed out"));
        assertEquals(Arrays.asList("", ""), timedOut.getAnswers());
        assertEquals(2, down.calls.get());

        Scripted babble = new Scripted(m -> MainModelChatResult.ok(
                "{\"zitat\":\"x\",\"aussage\":\"y\",\"urteil\":\"VIELLEICHT\"}"));
        EvidenceVerdict unreadable = judge(babble, "de", null).judge(pair("F01", QUESTION, "p1", TEXT), 1)
                .getVerdict();
        assertEquals(Kind.FEHLER, unreadable.getKind());
        assertTrue(unreadable.getReason().startsWith("invalid answer: \"urteil\""));

        Scripted crash = new Scripted(m -> {
            throw new IllegalStateException("connection refused");
        });
        EvidenceVerdict crashed = judge(crash, "de", null).judge(pair("F01", QUESTION, "p1", TEXT), 1).getVerdict();
        assertEquals(Kind.FEHLER, crashed.getKind());
        assertTrue(crashed.getReason().contains("PROVIDER_FAILURE"));
        assertTrue(crashed.getReason().contains("connection refused"));
    }

    @Test
    public void theSameTextIsJudgedOncePerQuestion() {
        Scripted chat = new Scripted(m -> answer(QUOTE, STATEMENT, "BELEGT"));
        Pair first = pair("F01", QUESTION, "p1", TEXT);
        Pair copy = pair("F01", QUESTION, "p9", "  " + TEXT.toUpperCase().replace(".", " ."));
        Pair otherQuestion = pair("F02", "Was kostet die Umstellung der Schilder?", "p1", TEXT);
        List<Outcome> out = judge(chat, "de", null).judgeAll(Arrays.asList(first, copy, otherQuestion),
                Collections.<EvidenceVerdict>emptyList(), 1);

        assertEquals(2, chat.calls.get());
        assertFalse(out.get(0).isReused());
        assertTrue(out.get(1).isReused());
        assertSame(copy, out.get(1).getPair());
        assertSame(out.get(0).getVerdict(), out.get(1).getVerdict());
        assertTrue(out.get(1).getAnswers().isEmpty());
        assertEquals(0L, out.get(1).getMillis());
        assertFalse(out.get(2).isReused());
        assertEquals("F02", out.get(2).getVerdict().getQuestionId());
    }

    @Test
    public void aPageLeftoverIsLeftOutOfABatch() {
        Scripted chat = new Scripted(m -> answer(QUOTE, STATEMENT, "BELEGT"));
        String menu = "Startseite | Verkehr | Baustellen | Kontakt | Impressum";
        final String menuKey = PassageText.key(menu);
        Pair content = pair("F01", QUESTION, "p1", TEXT);
        Pair leftover = pair("F01", QUESTION, "p2", menu);
        List<Outcome> out = judge(chat, "de", null).useNoise(key -> key.equals(menuKey)).judgeAll(
                Arrays.asList(leftover, content), Collections.<EvidenceVerdict>emptyList(), 1);

        assertEquals(1, chat.calls.get());
        assertEquals(1, out.size());
        assertSame(content, out.get(0).getPair());
        assertEquals(Kind.BELEGT, out.get(0).getVerdict().getKind());
    }

    @Test
    public void earlierVerdictsAreReusedButAFehlerIsAskedAgain() {
        EvidenceVerdict belegt = EvidenceVerdict.judged("F01", "p1", "s-p1", URL, TEXT, Kind.BELEGT, QUOTE,
                STATEMENT, "old-model", 1, 10L);
        EvidenceVerdict failed = EvidenceVerdict.failed("F02", "p1", "s-p1", URL, TEXT, "timeout", "old-model", 1,
                10L);
        Scripted chat = new Scripted(m -> answer("", "nichts", "KEIN"));
        List<Outcome> out = judge(chat, "de", null).judgeAll(Arrays.asList(pair("F01", QUESTION, "p5", TEXT),
                pair("F02", "Was kostet die Umstellung der Schilder?", "p5", TEXT)), Arrays.asList(belegt, failed), 2);

        assertEquals(1, chat.calls.get());
        assertTrue(out.get(0).isReused());
        assertSame(belegt, out.get(0).getVerdict());
        assertFalse(out.get(1).isReused());
        assertEquals(Kind.KEIN, out.get(1).getVerdict().getKind());
        assertEquals(2, out.get(1).getVerdict().getRound());
        assertEquals("p5", out.get(1).getVerdict().getPassageId());
    }

    @Test
    public void afterFiveFailedPairsInARowTheRestIsNotAsked() {
        Scripted down = new Scripted(m -> MainModelChatResult.failure(Status.PROVIDER_FAILURE, "model not found"));
        List<Pair> pairs = new ArrayList<Pair>();
        for (int i = 0; i < 8; i++) {
            pairs.add(pair("F01", QUESTION, "p" + i, section(i)));
        }
        List<Outcome> out = judge(down, "de", new Options(1, Options.DEFAULT_PASSAGE_CHARS)).judgeAll(pairs,
                Collections.<EvidenceVerdict>emptyList(), 1);

        assertEquals(10, down.calls.get());
        for (int i = 0; i < 8; i++) {
            assertEquals(Kind.FEHLER, out.get(i).getVerdict().getKind());
            assertSame(pairs.get(i), out.get(i).getPair());
        }
        for (int i = 5; i < 8; i++) {
            assertEquals("not asked: the model failed 5 pairs in a row; last: model call failed "
                    + "(PROVIDER_FAILURE): model not found", out.get(i).getVerdict().getReason());
            assertTrue(out.get(i).getAnswers().isEmpty());
        }
    }

    @Test
    public void aVerdictInBetweenStartsTheCountAgain() {
        final Pattern which = Pattern.compile("Abschnitt (\\d+) endet");
        Scripted chat = new Scripted(m -> {
            Matcher found = which.matcher(m.get(1).getContent());
            assertTrue(found.find());
            return Integer.parseInt(found.group(1)) == 4 ? answer("", "nichts", "KEIN")
                    : MainModelChatResult.failure(Status.TIMEOUT, "Read timed out");
        });
        List<Pair> pairs = new ArrayList<Pair>();
        for (int i = 0; i < 12; i++) {
            pairs.add(pair("F01", QUESTION, "p" + i, section(i)));
        }
        List<Outcome> out = judge(chat, "de", new Options(1, Options.DEFAULT_PASSAGE_CHARS)).judgeAll(pairs,
                Collections.<EvidenceVerdict>emptyList(), 1);

        assertEquals(4 * 2 + 1 + 5 * 2, chat.calls.get());
        assertEquals(Kind.KEIN, out.get(4).getVerdict().getKind());
        assertFalse(out.get(9).getVerdict().getReason().startsWith("not asked"));
        assertTrue(out.get(10).getVerdict().getReason().startsWith("not asked"));
        assertTrue(out.get(11).getVerdict().getReason().startsWith("not asked"));
    }

    @Test
    public void aCopyOfAFailedTextFailsWithoutACall() {
        Scripted down = new Scripted(m -> MainModelChatResult.failure(Status.TIMEOUT, "Read timed out"));
        List<Outcome> out = judge(down, "de", null).judgeAll(Arrays.asList(pair("F01", QUESTION, "p1", TEXT),
                pair("F01", QUESTION, "p2", TEXT)), Collections.<EvidenceVerdict>emptyList(), 1);

        assertEquals(2, down.calls.get());
        assertEquals(Kind.FEHLER, out.get(1).getVerdict().getKind());
        assertFalse(out.get(1).isReused());
        assertEquals("p2", out.get(1).getVerdict().getPassageId());
        assertTrue(out.get(1).getVerdict().getReason().startsWith("same text as p1, which failed: model call failed"));
    }

    @Test
    public void manyPairsRunInParallelAndKeepTheirOrder() {
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger most = new AtomicInteger();
        final Pattern which = Pattern.compile("Frage Nummer (\\d+)\\?");
        Scripted chat = new Scripted(m -> {
            int now = active.incrementAndGet();
            most.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(30);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
            Matcher found = which.matcher(m.get(1).getContent());
            assertTrue(found.find());
            int number = Integer.parseInt(found.group(1));
            return number % 3 == 0 ? answer(QUOTE, STATEMENT, "BELEGT")
                    : answer("", "nichts", number % 3 == 1 ? "HINWEIS" : "KEIN");
        });
        List<Pair> pairs = new ArrayList<Pair>();
        for (int i = 0; i < 12; i++) {
            pairs.add(pair(String.format("F%02d", i), "Frage Nummer " + i + "?", "p1", TEXT));
        }
        List<Outcome> out = judge(chat, "de", new Options(4, Options.DEFAULT_PASSAGE_CHARS)).judgeAll(pairs,
                Collections.<EvidenceVerdict>emptyList(), 1);

        assertEquals(12, chat.calls.get());
        assertTrue("calls at the same time: " + most.get(), most.get() > 1 && most.get() <= 4);
        for (int i = 0; i < 12; i++) {
            assertSame(pairs.get(i), out.get(i).getPair());
            Kind expected = i % 3 == 0 ? Kind.BELEGT : i % 3 == 1 ? Kind.HINWEIS : Kind.KEIN;
            assertEquals(expected, out.get(i).getVerdict().getKind());
        }
    }

    @Test
    public void aLongPassageIsCutAndMarked() {
        StringBuilder text = new StringBuilder(TEXT);
        for (int i = 0; i < 100; i++) {
            text.append(" Weitere Messwerte folgen im Anhang.");
        }
        text.append(" ENDE-DER-PASSAGE");
        Scripted chat = new Scripted(m -> answer(QUOTE, STATEMENT, "BELEGT"));
        Outcome outcome = judge(chat, "de", new Options(1, 1000)).judge(pair("F01", QUESTION, "p1",
                text.toString()), 1);

        String prompt = chat.requests.get(0).get(1).getContent();
        assertTrue(prompt.contains(text.substring(0, 1000).trim() + " …\n\"\"\""));
        assertFalse(prompt.contains("ENDE-DER-PASSAGE"));
        assertEquals(Kind.BELEGT, outcome.getVerdict().getKind());
        assertEquals(PassageText.key(text.substring(0, 1000)), outcome.getVerdict().getTextKey());
        assertEquals(EvidenceJudge.keyOf(pair("F01", QUESTION, "p1", text.toString()), 1000),
                "F01\n" + outcome.getVerdict().getTextKey());
    }

    @Test
    public void anEnglishBookGetsEnglishPrompts() {
        AtomicInteger n = new AtomicInteger();
        Scripted chat = new Scripted(m -> n.getAndIncrement() == 0 ? MainModelChatResult.ok("yes")
                : answer("", "nothing", "KEIN"));
        judge(chat, "en", null).judge(pair("F01", "How does a 30 km/h limit affect severe crashes?", "p1", TEXT), 1);

        assertEquals(EvidenceJudge.SYSTEM_EN, chat.requests.get(0).get(0).getContent());
        String prompt = chat.requests.get(0).get(1).getContent();
        assertTrue(prompt.startsWith("Book mission:\n"));
        assertTrue(prompt.contains("If the statement names a number, that number must stand in the quote."));
        assertTrue(chat.requests.get(1).get(3).getContent().startsWith("Your answer was invalid: "));
    }

    @Test
    public void anEmptyMissionIsNamedAndALongOneIsCut() {
        Scripted chat = new Scripted(m -> answer("", "nichts", "KEIN"));
        judge(chat, "de", null, "").judge(pair("F01", QUESTION, "p1", TEXT), 1);
        assertTrue(chat.requests.get(0).get(1).getContent().startsWith("Buchauftrag:\n(keiner angegeben)\n\n"));

        StringBuilder mission = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            mission.append("Kapitel ").append(i).append(" behandelt Kreuzungen. ");
        }
        mission.append("LETZTER-SATZ");
        Scripted second = new Scripted(m -> answer("", "nichts", "KEIN"));
        judge(second, "de", null, mission.toString()).judge(pair("F01", QUESTION, "p1", TEXT), 1);
        String prompt = second.requests.get(0).get(1).getContent();
        assertFalse(prompt.contains("LETZTER-SATZ"));
        assertTrue(prompt.substring(0, prompt.indexOf("\n\nFrage, die")).endsWith(" …"));
    }

    @Test
    public void hostsPassagesAndOptions() {
        assertEquals("stadt.example", EvidenceJudge.hostOf("https://www.stadt.example/verkehr?x=1"));
        assertEquals("sub.example.org", EvidenceJudge.hostOf("http://user@Sub.Example.org:8080/a#b"));
        assertEquals("example.org", EvidenceJudge.hostOf("example.org/pfad"));
        assertEquals("", EvidenceJudge.hostOf(""));
        assertEquals("eigene.example", new Passage("p1", "s1", URL, "eigene.example", TEXT).getDomain());
        assertEquals("stadt.example", new Passage("p1", "s1", URL, " ", TEXT).getDomain());
        assertEquals(4, Options.defaults().getParallel());
        assertEquals(4000, Options.defaults().getPassageChars());
        expectRejected(() -> new Options(0, 4000));
        expectRejected(() -> new Options(1, Options.MIN_PASSAGE_CHARS - 1));
        expectRejected(() -> new Passage(" ", "s1", URL, "", TEXT));
        expectRejected(() -> new Question("", QUESTION));
        expectRejected(() -> new Pair(null, new Passage("p1", "s1", URL, "", TEXT)));
        expectRejected(() -> new EvidenceJudge(null, MISSION, "de", null, null));
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static EvidenceVerdict verdictFor(String quote, String statement, String urteil) {
        Scripted chat = new Scripted(m -> answer(quote, statement, urteil));
        return judge(chat, "de", null).judge(pair("F01", QUESTION, "p1", TEXT), 1).getVerdict();
    }

    private static EvidenceJudge judge(MainModelChat chat, String language, Options options) {
        return judge(chat, language, options, MISSION);
    }

    private static EvidenceJudge judge(MainModelChat chat, String language, Options options, String mission) {
        return new EvidenceJudge(chat, mission, language, options, null);
    }

    private static Pair pair(String questionId, String question, String passageId, String text) {
        return new Pair(new Question(questionId, question), new Passage(passageId, "s-" + passageId, URL, "", text));
    }

    private static String section(int i) {
        return TEXT + " Abschnitt " + i + " endet hier.";
    }

    private static MainModelChatResult answer(String quote, String statement, String urteil) {
        JsonObject json = new JsonObject();
        json.addProperty("zitat", quote);
        json.addProperty("aussage", statement);
        json.addProperty("urteil", urteil);
        return MainModelChatResult.ok(json.toString());
    }

    private static void expectRejected(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // fine
        }
    }

    /** A model from a script; records every request. */
    static final class Scripted implements MainModelChat {
        final Function<List<ChatMessage>, MainModelChatResult> answers;
        final AtomicInteger calls = new AtomicInteger();
        final List<List<ChatMessage>> requests = Collections.synchronizedList(new ArrayList<List<ChatMessage>>());
        final List<String> schemas = Collections.synchronizedList(new ArrayList<String>());
        volatile double temperature = -1;
        volatile int tokens = -1;

        Scripted(Function<List<ChatMessage>, MainModelChatResult> answers) {
            this.answers = answers;
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            return completeJson(messages, temperature, maxOutputTokens, null);
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            calls.incrementAndGet();
            requests.add(new ArrayList<ChatMessage>(messages));
            schemas.add(schemaJson);
            this.temperature = temperature;
            this.tokens = maxOutputTokens;
            return answers.apply(messages);
        }

        @Override
        public String modelName() {
            return "test-model";
        }
    }
}
