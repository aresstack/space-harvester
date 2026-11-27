package com.aresstack.writing4j.evidence;

import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.writing4j.evidence.EvidenceBundleJudge.Candidate;
import com.aresstack.writing4j.evidence.EvidenceBundleJudge.Options;
import com.aresstack.writing4j.evidence.EvidenceBundleJudge.Result;
import com.aresstack.writing4j.evidence.EvidenceJudge.Passage;
import com.aresstack.writing4j.evidence.EvidenceJudge.Question;
import com.aresstack.writing4j.evidence.EvidenceJudgeTest.Scripted;
import com.aresstack.writing4j.memory.EvidenceVerdict;
import com.aresstack.writing4j.memory.EvidenceVerdict.Kind;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult.Status;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The evidence check per question with a scripted model: one call per bundle, the bundle size from the context,
 * applicability, the repair of missed passages, FEHLER and reuse. The texts are made up for this test (city traffic),
 * not taken from any source.
 */
public class EvidenceBundleJudgeTest {

    private static final Fence FENCE = new Fence(
            "Ein Sachbuch für Stadtplaner über Verkehrssicherheit in Innenstädten.", "",
            Collections.singletonList("Tempo 30"), Collections.singletonList("Autobahnen"), "Deutschland", "");
    private static final Question QUESTION = new Question("F01", "Wie wirkt Tempo 30 auf schwere Unfälle?");
    private static final String URL = "https://www.stadt.example/verkehr/bericht";
    private static final String IN = "Nach Einführung von Tempo 30 in der Innenstadt sank die Zahl der schweren "
            + "Unfälle im ersten Jahr um 30 Prozent.";
    private static final String OUT = "Auf der Autobahn sank die Zahl der schweren Unfälle nach dem Tempolimit um "
            + "12 Prozent.";
    private static final String VAGUE = "In einer Vergleichsstadt sank die Zahl der schweren Unfälle nach Tempo 30 "
            + "um 20 Prozent.";

    @Test
    public void oneCallJudgesTheWholeBundleWithHeadingsProfilesAndTheFence() {
        Scripted chat = new Scripted(m -> MainModelChatResult.ok(answers(
                entry("P1", "sank die Zahl der schweren Unfälle im ersten Jahr um 30 Prozent",
                        "Schwere Unfälle sanken um 30 Prozent.", "JA", "BELEGT"),
                entry("P2", "sank die Zahl der schweren Unfälle nach dem Tempolimit um 12 Prozent",
                        "Auf der Autobahn sanken sie um 12 Prozent.", "NEIN", "BELEGT"),
                entry("P3", "sank die Zahl der schweren Unfälle nach Tempo 30 um 20 Prozent",
                        "In einer Vergleichsstadt um 20 Prozent.", "UNKLAR", "BELEGT"))));
        Result result = judge(chat, Options.defaults()).judge(QUESTION,
                Arrays.asList(candidate("p1", IN, "Bericht > Innenstadt"), candidate("p2", OUT, "Bericht > Autobahn"),
                        candidate("p3", VAGUE, "")), Collections.<EvidenceVerdict>emptyList(), 1);

        assertEquals(1, chat.calls.get());
        assertEquals(1, result.getCalls().size());
        assertEquals(3, result.getCalls().get(0).getPassages());
        String prompt = chat.requests.get(0).get(1).getContent();
        assertTrue(prompt.contains("Weidezaun des Buchs:"));
        assertTrue(prompt.contains("- Autobahnen"));
        assertTrue(prompt.contains("Räumlicher Rahmen: Deutschland"));
        assertTrue(prompt.contains("[P1] Quelle: stadt.example"));
        assertTrue(prompt.contains("Steckbrief: Stadtverwaltung, Bericht 2025"));
        assertTrue(prompt.contains("Überschrift: Bericht > Autobahn"));
        assertEquals(EvidenceBundleJudge.schema(), chat.schemas.get(0));
        assertEquals(3 * Options.DEFAULT_ANSWER_TOKENS_PER_PASSAGE, chat.tokens);

        List<EvidenceVerdict> verdicts = result.getVerdicts();
        assertEquals(Arrays.asList(Kind.BELEGT, Kind.KEIN, Kind.HINWEIS), kinds(verdicts));
        assertTrue(verdicts.get(1).getReason().startsWith("nicht anwendbar: "));
        assertTrue(verdicts.get(2).getReason().startsWith("Anwendbarkeit unklar: "));
    }

    @Test
    public void theProgramStillChecksTheQuote() {
        Scripted chat = new Scripted(m -> MainModelChatResult.ok(answers(
                entry("P1", "die Unfälle halbierten sich", "Sie halbierten sich.", "JA", "BELEGT"))));
        Result result = judge(chat, Options.defaults()).judge(QUESTION,
                Collections.singletonList(candidate("p1", IN, "")), Collections.<EvidenceVerdict>emptyList(), 1);

        assertEquals(Kind.HINWEIS, result.getVerdicts().get(0).getKind());
    }

    @Test
    public void theBundleSizeFollowsTheContextNotAFixedCount() {
        StringBuilder longText = new StringBuilder();
        while (longText.length() < 3000) {
            longText.append(IN).append(' ');
        }
        List<Candidate> many = new ArrayList<Candidate>();
        for (int i = 1; i <= 12; i++) {
            many.add(candidate("p" + i, i + ". " + longText, ""));
        }
        EvidenceBundleJudge small = judge(new Scripted(m -> null), Options.defaults().withContextTokens(4096));
        EvidenceBundleJudge large = judge(new Scripted(m -> null), Options.defaults().withContextTokens(32768));

        int smallBundles = small.bundles(QUESTION, many).size();
        int largeBundles = large.bundles(QUESTION, many).size();
        assertTrue(smallBundles > largeBundles);
        assertEquals(1, largeBundles);
        int total = 0;
        for (List<Candidate> bundle : small.bundles(QUESTION, many)) {
            assertTrue(!bundle.isEmpty());
            total += bundle.size();
        }
        assertEquals(12, total);
    }

    @Test
    public void missedPassagesGetOneRepairThenFehler() {
        AtomicInteger n = new AtomicInteger();
        Scripted chat = new Scripted(m -> {
            String prompt = m.get(1).getContent();
            if (n.getAndIncrement() == 0) {
                return MainModelChatResult.ok(answers(
                        entry("P1", "sank die Zahl der schweren Unfälle im ersten Jahr um 30 Prozent",
                                "Schwere Unfälle sanken um 30 Prozent.", "JA", "BELEGT")));
            }
            assertTrue(prompt.contains("[P1]"));
            assertTrue(!prompt.contains("[P2]"));
            return MainModelChatResult.ok("kein JSON");
        });
        Result result = judge(chat, Options.defaults()).judge(QUESTION,
                Arrays.asList(candidate("p1", IN, ""), candidate("p2", VAGUE, "")),
                Collections.<EvidenceVerdict>emptyList(), 1);

        assertEquals(2, chat.calls.get());
        assertEquals(Arrays.asList(Kind.BELEGT, Kind.FEHLER), kinds(result.getVerdicts()));
        assertEquals("p2", result.getVerdicts().get(1).getPassageId());
        assertEquals(2, result.getCalls().get(0).getAttempts());
    }

    @Test
    public void aFailedCallIsRepairedOnce() {
        AtomicInteger n = new AtomicInteger();
        Scripted chat = new Scripted(m -> n.getAndIncrement() == 0
                ? MainModelChatResult.failure(Status.TIMEOUT, "Read timed out")
                : MainModelChatResult.ok(answers(entry("1", "", "nichts", "JA", "KEIN"))));
        Result result = judge(chat, Options.defaults()).judge(QUESTION,
                Collections.singletonList(candidate("p1", IN, "")), Collections.<EvidenceVerdict>emptyList(), 1);

        assertEquals(2, chat.calls.get());
        assertEquals(Kind.KEIN, result.getVerdicts().get(0).getKind());
    }

    @Test
    public void earlierVerdictsAndRepeatedTextsAreNotAskedAgain() {
        Scripted chat = new Scripted(m -> {
            Matcher labels = Pattern.compile("\\[(P\\d+)]").matcher(m.get(1).getContent());
            List<JsonObject> out = new ArrayList<JsonObject>();
            while (labels.find()) {
                out.add(entry(labels.group(1), "", "nichts", "JA", "KEIN"));
            }
            return MainModelChatResult.ok(answers(out.toArray(new JsonObject[0])));
        });
        EvidenceBundleJudge judge = judge(chat, Options.defaults());
        Result first = judge.judge(QUESTION, Arrays.asList(candidate("p1", IN, ""), candidate("p2", IN, ""),
                candidate("p3", VAGUE, "")), Collections.<EvidenceVerdict>emptyList(), 1);
        assertEquals(1, chat.calls.get());
        assertEquals(3, first.getVerdicts().size());
        assertEquals(1, first.getReused());
        assertTrue(chat.requests.get(0).get(1).getContent().contains("[P2]"));
        assertTrue(!chat.requests.get(0).get(1).getContent().contains("[P3]"));

        Result second = judge.judge(QUESTION, Arrays.asList(candidate("p1", IN, ""), candidate("p4", OUT, "")),
                first.getVerdicts(), 2);
        assertEquals(2, chat.calls.get());
        assertEquals(1, second.getReused());
        assertTrue(!chat.requests.get(1).get(1).getContent().contains("Innenstadt sank"));
    }

    @Test
    public void questionsRunInParallelAndKeepTheirOrder() {
        Scripted chat = new Scripted(m -> MainModelChatResult.ok(answers(entry("P1", "", "nichts", "JA", "KEIN"))));
        Map<Question, List<Candidate>> work = new LinkedHashMap<Question, List<Candidate>>();
        for (int i = 1; i <= 5; i++) {
            work.put(new Question("F0" + i, "Frage " + i), Collections.singletonList(candidate("p" + i, IN + i, "")));
        }
        List<Result> results = judge(chat, Options.defaults().withParallel(3)).judgeAll(work,
                Collections.<EvidenceVerdict>emptyList(), 1);

        assertEquals(5, results.size());
        for (int i = 0; i < 5; i++) {
            assertEquals("F0" + (i + 1), results.get(i).getVerdicts().get(0).getQuestionId());
        }
        assertEquals(5, chat.calls.get());
    }

    @Test
    public void theSchemaAsksForApplicability() {
        JsonObject entry = JsonParser.parseString(EvidenceBundleJudge.schema()).getAsJsonObject()
                .getAsJsonObject("properties").getAsJsonObject("urteile").getAsJsonObject("items");
        assertEquals("[\"nr\",\"zitat\",\"aussage\",\"anwendbar\",\"urteil\"]", entry.get("required").toString());
        assertEquals("[\"JA\",\"NEIN\",\"UNKLAR\"]",
                entry.getAsJsonObject("properties").getAsJsonObject("anwendbar").get("enum").toString());
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static EvidenceBundleJudge judge(Scripted chat, Options options) {
        return new EvidenceBundleJudge(chat, FENCE, "de", options, null);
    }

    private static Candidate candidate(String id, String text, String heading) {
        return new Candidate(new Passage(id, "s-" + id, URL, "stadt.example", text), heading,
                "Stadtverwaltung, Bericht 2025");
    }

    private static JsonObject entry(String nr, String quote, String statement, String applies, String verdict) {
        JsonObject json = new JsonObject();
        json.addProperty("nr", nr);
        json.addProperty("zitat", quote);
        json.addProperty("aussage", statement);
        json.addProperty("anwendbar", applies);
        json.addProperty("urteil", verdict);
        return json;
    }

    private static String answers(JsonObject... entries) {
        JsonArray list = new JsonArray();
        for (JsonObject entry : entries) {
            list.add(entry);
        }
        JsonObject top = new JsonObject();
        top.add("urteile", list);
        return top.toString();
    }

    private static List<Kind> kinds(List<EvidenceVerdict> verdicts) {
        List<Kind> out = new ArrayList<Kind>();
        for (EvidenceVerdict verdict : verdicts) {
            out.add(verdict.getKind());
        }
        return out;
    }
}
