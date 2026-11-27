package com.aresstack.writing4j.sentencecheck;

import com.aresstack.knowledge4j.model.ThinkingMode;
import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.aresstack.writing4j.sentencecheck.SentenceCheck.Item;
import com.aresstack.writing4j.sentencecheck.SentenceCheck.Options;
import com.aresstack.writing4j.sentencecheck.SentenceCheck.Result;
import com.aresstack.writing4j.sentencecheck.SentenceVerdict.Attribution;
import com.aresstack.writing4j.sentencecheck.SentenceVerdict.Jurisdiction;
import com.aresstack.writing4j.sentencecheck.SentenceVerdict.Kind;
import com.aresstack.writing4j.sentencecheck.SentenceVerdict.Verdict;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static com.aresstack.writing4j.sentencecheck.SentenceVerdictTest.PASSAGE;
import static com.aresstack.writing4j.sentencecheck.SentenceVerdictTest.QUOTE;
import static com.aresstack.writing4j.sentencecheck.SentenceVerdictTest.SENTENCE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Component 11: one call per sentence and cited passage, the quote checked, one repair, FEHLER never NICHT_GEDECKT;
 * sources with an interest of their own or of another legal space are asked about attribution and marking.
 */
public class SentenceCheckTest {

    static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(70_000L), ZoneOffset.UTC);
    static final String BIKE = "Radfahrer dürfen in Fahrradstraßen nebeneinander fahren [4].";

    @Rule
    public final TemporaryFolder scratch = new TemporaryFolder();

    static String answer(String kind, String quote, String reason, String verdict) {
        JsonObject json = new JsonObject();
        json.addProperty("belegart", kind);
        json.addProperty("zitat", quote);
        json.addProperty("grund", reason);
        json.addProperty("urteil", verdict);
        return json.toString();
    }

    static SentenceCheck check(MainModelChat chat) {
        return new SentenceCheck(chat, "de", Options.defaults(), CLOCK);
    }

    static Item item(String id, String sentence) {
        return new Item(id, sentence, "p1", "s1", PASSAGE);
    }

    @Test
    public void aPairBecomesAVerdictWithTheQuoteCheckedAndGoesToTheStore() throws IOException {
        Scripted chat = new Scripted(messages -> MainModelChatResult.ok(messages.get(1).getContent().contains(
                "Radfahrer") ? answer("TEXT", "", "Die Passage spricht nicht von Radfahrern.", "NICHT_GEDECKT")
                : answer("TEXT", QUOTE, "Die Passage nennt den Rückgang um 25 Prozent.", "GEDECKT")));
        SentenceVerdicts store = new SentenceVerdicts();

        Result result = check(chat).check(Arrays.asList(item("S1", SENTENCE), item("S2", BIKE)), store);

        assertEquals(2, result.getAsked());
        assertEquals(1, result.count(Verdict.GEDECKT));
        assertEquals(1, result.count(Verdict.NICHT_GEDECKT));
        assertEquals(1, result.toRewrite());
        SentenceVerdict first = result.getVerdicts().get(0);
        assertEquals("S1", first.getSentenceId());
        assertEquals(Verdict.GEDECKT, first.getVerdict());
        assertEquals("test-model", first.getModel());
        assertEquals(SentenceCheck.VERSION, first.getVersion());
        assertEquals(first, store.verdict(first.getSentenceKey(), first.getTextKey()));
        assertEquals(2, store.size());

        // the two pairs go out at the same time: the request of S1 is found by its text, not by its place
        List<ChatMessage> request = requestOf(chat, "Satz aus dem Buch:\n„In Tempo-30-Zonen sank");
        String prompt = request.get(1).getContent();
        assertTrue(prompt, prompt.startsWith("Satz aus dem Buch:\n„In Tempo-30-Zonen sank die Zahl der Unfälle mit "
                + "Verletzten im ersten Jahr um 25 Prozent.“\n\nZitierte Passage:\n„In den Tempo-30-Zonen"));
        assertFalse("no citation marker", prompt.contains("[12]"));
        assertTrue(prompt, prompt.contains("- urteil: GEDECKT, wenn die Passage alles sagt, was der Satz behauptet"));
        assertTrue(prompt, prompt.endsWith("Antworte nur mit JSON: {\"belegart\": ..., \"zitat\": ..., \"grund\": ..., "
                + "\"urteil\": ...}"));
        assertFalse(prompt.contains("zuschreibung"));
        assertEquals(SentenceCheck.SYSTEM_DE, request.get(0).getContent());
        JsonObject schema = JsonParser.parseString(chat.schemas.get(0)).getAsJsonObject();
        assertEquals("[\"belegart\",\"zitat\",\"grund\",\"urteil\"]", schema.getAsJsonArray("required").toString());
        assertEquals("[\"GEDECKT\",\"TEILWEISE\",\"NICHT_GEDECKT\"]", schema.getAsJsonObject("properties")
                .getAsJsonObject("urteil").getAsJsonArray("enum").toString());
        assertEquals(0.0, chat.temperature, 0.0);
        assertEquals(768, chat.tokens);
    }

    /** The one request whose user message starts so; the requests of parallel pairs come in any order. */
    private static List<ChatMessage> requestOf(Scripted chat, String start) {
        List<ChatMessage> found = null;
        synchronized (chat.requests) {
            for (List<ChatMessage> request : chat.requests) {
                if (request.get(1).getContent().startsWith(start)) {
                    assertTrue("one request starts so: " + start, found == null);
                    found = request;
                }
            }
        }
        assertTrue("a request starts so: " + start, found != null);
        return found;
    }

    /** After the page-noise check (#274): a pair whose passage is a page leftover is not asked. */
    @Test
    public void aPairOnAPageLeftoverIsNotAsked() throws IOException {
        Scripted chat = new Scripted(messages -> MainModelChatResult.ok(
                answer("TEXT", QUOTE, "Die Passage nennt den Rückgang um 25 Prozent.", "GEDECKT")));
        Item item = item("S1", SENTENCE);
        String key = SentenceVerdict.textKey(item.getPassageId(), item.getPassageText());

        Result result = check(chat).useNoise(k -> k.equals(key)).check(Arrays.asList(item), new SentenceVerdicts());

        assertEquals(0, result.getAsked());
        assertEquals(1, result.getLeftovers());
        assertTrue(result.getVerdicts().isEmpty());
        assertEquals(1, check(chat).useNoise(null).check(Arrays.asList(item), new SentenceVerdicts()).getAsked());
    }

    @Test
    public void theRoleIsTheDocumentCheckSlotWithoutThinking() {
        assertEquals("phase.draft.check", SentenceCheck.ROLE.getSlot().getName());
        assertEquals(ThinkingMode.OFF, SentenceCheck.ROLE.getThinking());
        assertTrue(SentenceCheck.ROLE.getOutput().isSchemaPerRequest());
        assertEquals(8192, SentenceCheck.ROLE.getContextTokens());
    }

    @Test
    public void aBadAnswerIsRepairedOnceAndAFailedPairIsFehlerNeverNichtGedeckt() throws IOException {
        AtomicInteger n = new AtomicInteger();
        Scripted repaired = new Scripted(messages -> MainModelChatResult.ok(n.incrementAndGet() == 1
                ? answer("TEXT", "", "Kurz.", "VIELLEICHT")
                : answer("TEXT", QUOTE, "Die Passage nennt den Rückgang.", "GEDECKT")));
        SentenceCheck.Judgement judgement = check(repaired).judge(item("S1", SENTENCE));
        assertEquals(Verdict.GEDECKT, judgement.getVerdict().getVerdict());
        assertEquals(2, judgement.getAnswers().size());
        assertEquals("Deine Antwort war ungültig: \"urteil\" must be GEDECKT, TEILWEISE or NICHT_GEDECKT\nAntworte "
                + "nur mit dem JSON-Objekt.", repaired.requests.get(1).get(3).getContent());

        Scripted broken = new Scripted(messages -> MainModelChatResult.ok("kein JSON"));
        SentenceVerdict failed = check(broken).judge(item("S1", SENTENCE)).getVerdict();
        assertEquals(SentenceVerdict.Status.FEHLER, failed.getStatus());
        assertEquals("invalid answer: the answer is no JSON", failed.getProblem());
        assertEquals(2, broken.calls.get());
        assertFalse("a FEHLER sends nothing back", failed.needsRewrite());

        Scripted down = new Scripted(messages -> MainModelChatResult.failure(
                MainModelChatResult.Status.PROVIDER_FAILURE, "connection refused"));
        assertEquals("model call failed (PROVIDER_FAILURE): connection refused",
                check(down).judge(item("S1", SENTENCE)).getVerdict().getProblem());
        assertEquals("no model", check(null).judge(item("S1", SENTENCE)).getVerdict().getProblem());
    }

    @Test
    public void aSourceWithAnInterestOfItsOwnOrOfAnotherLegalSpaceIsAskedAboutIt() throws IOException {
        JsonObject full = JsonParser.parseString(answer("TEXT", QUOTE, "Die Passage nennt den Rückgang.", "GEDECKT"))
                .getAsJsonObject();
        full.addProperty("zuschreibung", "als Tatsache");
        full.addProperty("rechtsraum", "nicht gekennzeichnet");
        Scripted chat = new Scripted(messages -> MainModelChatResult.ok(full.toString()));
        Item shop = new Item("S1", SENTENCE, "p1", "s1", PASSAGE, "hersteller_handel", "at");

        SentenceVerdict verdict = check(chat).judge(shop).getVerdict();

        assertEquals(Attribution.ALS_TATSACHE, verdict.getAttribution());
        assertEquals(Jurisdiction.NICHT_GEKENNZEICHNET, verdict.getJurisdiction());
        assertTrue("covered, but stated as a fact", verdict.needsRewrite());
        String prompt = chat.requests.get(0).get(1).getContent();
        assertTrue(prompt, prompt.contains("\nDie Quelle der Passage ist ein Hersteller oder Händler.\n- zuschreibung: "
                + "ZUGESCHRIEBEN, wenn der Satz die Aussage dieser Quelle zuschreibt"));
        assertTrue(prompt, prompt.contains("\nDie Quelle der Passage gilt für Österreich, das Buch für Deutschland.\n"
                + "- rechtsraum: KEINE_RECHTSAUSSAGE"));
        assertTrue(prompt, prompt.endsWith("\"urteil\": ..., \"zuschreibung\": ..., \"rechtsraum\": ...}"));
        assertEquals("[\"belegart\",\"zitat\",\"grund\",\"urteil\",\"zuschreibung\",\"rechtsraum\"]",
                JsonParser.parseString(chat.schemas.get(0)).getAsJsonObject().getAsJsonArray("required").toString());

        SentenceCheck germanBook = check(chat);
        assertTrue(germanBook.asksAttribution(new Item("S1", SENTENCE, "p1", "s1", PASSAGE, "VERBAND", "")));
        assertFalse(germanBook.asksAttribution(new Item("S1", SENTENCE, "p1", "s1", PASSAGE, "BEHOERDE_"
                + "UNFALLVERSICHERUNG", "DE")));
        for (String space : new String[]{"DE", "EU", "OHNE", ""}) {
            assertFalse(space, germanBook.asksJurisdiction(new Item("S1", SENTENCE, "p1", "s1", PASSAGE, "", space)));
        }
        assertTrue(germanBook.asksJurisdiction(new Item("S1", SENTENCE, "p1", "s1", PASSAGE, "", "ANDERE")));
        SentenceCheck austrianBook = new SentenceCheck(chat, "de", Options.defaults().withLegalSpace("AT"), CLOCK);
        assertTrue(austrianBook.asksJurisdiction(new Item("S1", SENTENCE, "p1", "s1", PASSAGE, "", "DE")));
        assertFalse(austrianBook.asksJurisdiction(new Item("S1", SENTENCE, "p1", "s1", PASSAGE, "", "AT")));

        Scripted missing = new Scripted(messages -> MainModelChatResult.ok(answer("TEXT", QUOTE, "Die Passage nennt "
                + "den Rückgang.", "GEDECKT")));
        SentenceVerdict without = check(missing).judge(shop).getVerdict();
        assertEquals("the questions asked must be answered", SentenceVerdict.Status.FEHLER, without.getStatus());
        assertEquals("invalid answer: \"zuschreibung\" must be ZUGESCHRIEBEN or ALS_TATSACHE", without.getProblem());
    }

    @Test
    public void aVerdictThatHoldsIsReusedUnlessItFailedIsOldOrANewQuestionIsDue() throws IOException {
        Scripted chat = new Scripted(messages -> MainModelChatResult.ok(answer("TEXT", QUOTE, "Die Passage nennt "
                + "den Rückgang.", "GEDECKT")));
        SentenceVerdicts store = new SentenceVerdicts();
        check(chat).check(Collections.singletonList(item("S1", SENTENCE)), store);
        assertEquals(1, chat.calls.get());

        Result again = check(chat).check(Arrays.asList(item("S1", SENTENCE), item("S1b", "In Tempo-30-Zonen sank "
                + "die Zahl der Unfälle mit Verletzten im ersten Jahr um 25 Prozent [3].")), store);
        assertEquals("the same sentence with another marker is the same pair", 1, again.getReused());
        assertEquals(1, again.getPairs());
        assertEquals(0, again.getAsked());
        assertEquals(1, chat.calls.get());

        Result known = check(chat).check(Collections.singletonList(new Item("S1", SENTENCE, "p1", "s1", PASSAGE,
                "VERBAND", "DE")), store);
        assertEquals("the source is known now: asked again", 1, known.getAsked());
        assertTrue(chat.requests.get(1).get(1).getContent().contains("ist ein Verband"));

        SentenceVerdicts old = new SentenceVerdicts();
        old.record(SentenceVerdict.checked("S1", SENTENCE, "p1", "s1", PASSAGE, Verdict.GEDECKT, Kind.TEXT, QUOTE,
                "Grund.", null, null, "m", SentenceCheck.VERSION - 1, 1L, 0));
        old.record(SentenceVerdict.failed("S2", BIKE, "p1", "s1", PASSAGE, "timeout", "m", SentenceCheck.VERSION, 1L,
                0));
        Result retried = check(chat).check(Arrays.asList(item("S1", SENTENCE), item("S2", BIKE)), old);
        assertEquals("an old version and a FEHLER are checked again", 2, retried.getAsked());
    }

    @Test
    public void eachPairOnceInParallelAndTheVerdictsInTheOrderOfThePairs() throws IOException {
        Scripted chat = new Scripted(messages -> {
            try {
                Thread.sleep(messages.get(1).getContent().contains("Radfahrer") ? 50 : 0);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return MainModelChatResult.ok(answer("TEXT", "", "Die Passage trägt den Satz nicht.", "NICHT_GEDECKT"));
        });
        List<Item> items = new ArrayList<Item>();
        items.add(item("S2", BIKE));
        items.add(item("S1", SENTENCE));
        items.add(item("S2-dup", BIKE));
        Result result = new SentenceCheck(chat, "de", Options.defaults().withParallel(2), CLOCK).check(items,
                new SentenceVerdicts());
        assertEquals(3, result.getItems());
        assertEquals(2, result.getPairs());
        assertEquals(2, chat.calls.get());
        assertEquals("S2", result.getVerdicts().get(0).getSentenceId());
        assertEquals("S1", result.getVerdicts().get(1).getSentenceId());
    }

    @Test
    public void bundledTheSentencesOfOnePassageGoInOneCall() throws IOException {
        Scripted chat = new Scripted(messages -> {
            if (messages.get(1).getContent().contains("Parkplätze")) {
                return MainModelChatResult.ok(answer("TEXT", "", "Die Passage spricht von Parkplätzen.",
                        "NICHT_GEDECKT"));
            }
            JsonObject s1 = JsonParser.parseString(answer("TEXT", QUOTE, "Die Passage nennt den Rückgang um 25 "
                    + "Prozent.", "GEDECKT")).getAsJsonObject();
            s1.addProperty("nr", "S1");
            JsonObject s2 = JsonParser.parseString(answer("TEXT", "", "Die Passage spricht nicht von Radfahrern.",
                    "NICHT_GEDECKT")).getAsJsonObject();
            s2.addProperty("nr", "S2");
            return MainModelChatResult.ok("{\"urteile\": [" + s1 + ", " + s2 + "]}");
        });
        Options bundled = Options.defaults().withBundles(true, 8192, SentenceCheck.DEFAULT_CHARS_PER_TOKEN);
        SentenceVerdicts store = new SentenceVerdicts();

        Result result = new SentenceCheck(chat, "de", bundled, CLOCK).check(Arrays.asList(item("S1", SENTENCE),
                item("S2", BIKE), new Item("S3", BIKE, "p9", "s9", "Ein ganz anderer Text über Parkplätze.")), store);

        assertEquals(3, result.getAsked());
        assertEquals(2, chat.calls.get());
        String bundle = requestOf(chat, "Zitierte Passage:\n„In den Tempo-30-Zonen").get(1).getContent();
        assertTrue(bundle, bundle.contains("[S1] „In Tempo-30-Zonen sank die Zahl der Unfälle"));
        assertTrue(bundle, bundle.contains("[S2] „Radfahrer dürfen"));
        assertFalse("no citation marker", bundle.contains("[12]"));
        assertTrue(bundle, bundle.contains("- urteil: GEDECKT, wenn die Passage alles sagt"));
        assertTrue(bundle, bundle.endsWith("\"nr\" (die Kennung des Satzes, etwa \"S1\"), \"belegart\", \"zitat\", "
                + "\"grund\", \"urteil\"."));
        assertEquals(Verdict.GEDECKT, result.getVerdicts().get(0).getVerdict());
        assertEquals(Verdict.NICHT_GEDECKT, result.getVerdicts().get(1).getVerdict());
        assertEquals("S3", result.getVerdicts().get(2).getSentenceId());
        assertEquals(3, store.size());
    }

    @Test
    public void bundledAMissedSentenceGetsOneRepairThenFehler() throws IOException {
        AtomicInteger n = new AtomicInteger();
        Scripted chat = new Scripted(messages -> {
            if (n.getAndIncrement() == 0) {
                JsonObject s1 = JsonParser.parseString(answer("TEXT", QUOTE, "Die Passage nennt den Rückgang.",
                        "GEDECKT")).getAsJsonObject();
                s1.addProperty("nr", "1");
                return MainModelChatResult.ok("{\"urteile\": [" + s1 + "]}");
            }
            assertTrue(messages.get(1).getContent().contains("[S1] „Radfahrer"));
            return MainModelChatResult.ok("{\"urteile\": []}");
        });
        Options bundled = Options.defaults().withBundles(true, 8192, SentenceCheck.DEFAULT_CHARS_PER_TOKEN);

        Result result = new SentenceCheck(chat, "de", bundled, CLOCK).check(Arrays.asList(item("S1", SENTENCE),
                item("S2", BIKE)), new SentenceVerdicts());

        assertEquals(2, chat.calls.get());
        assertEquals(Verdict.GEDECKT, result.getVerdicts().get(0).getVerdict());
        assertEquals(SentenceVerdict.Status.FEHLER, result.getVerdicts().get(1).getStatus());
    }

    @Test
    public void theBundleSizeFollowsTheContext() {
        List<Item> items = new ArrayList<Item>();
        for (int i = 0; i < 20; i++) {
            items.add(item("S" + i, SENTENCE + " Fall " + i + "."));
        }
        SentenceCheck small = new SentenceCheck(null, "de", Options.defaults().withBundles(true, 4096, 3.0), CLOCK);
        SentenceCheck large = new SentenceCheck(null, "de", Options.defaults().withBundles(true, 65536, 3.0), CLOCK);

        assertTrue(small.bundles(items).size() > large.bundles(items).size());
        assertEquals(1, large.bundles(items).size());
        int total = 0;
        for (List<Item> bundle : small.bundles(items)) {
            assertFalse(bundle.isEmpty());
            total += bundle.size();
        }
        assertEquals(20, total);
    }

    @Test
    public void aVerdictThatCannotBeWrittenStopsTheRun() throws Exception {
        File project = scratch.newFolder("projekt");
        SentenceVerdicts store = new SentenceVerdicts(project);
        store.open();
        assertTrue(store.logFile().mkdirs());
        Scripted chat = new Scripted(messages -> MainModelChatResult.ok(answer("TEXT", QUOTE, "Die Passage nennt "
                + "den Rückgang.", "GEDECKT")));
        List<Item> items = new ArrayList<Item>();
        for (int i = 0; i < 6; i++) {
            items.add(new Item("S" + i, SENTENCE + " Fall " + i + ".", "p1", "s1", PASSAGE));
        }
        try {
            new SentenceCheck(chat, "de", Options.defaults().withParallel(1), CLOCK).check(items, store);
            fail("a verdict that cannot be written was taken");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().startsWith("a verdict of the sentence check could "
                    + "not be written"));
        }
        assertEquals("no further pair was asked", 1, chat.calls.get());
    }

    @Test
    public void theAnswerIsReadLenientlyAndTheEnglishPromptAsksTheSame() {
        SentenceCheck.Answer answer = SentenceCheck.parse("```json\n{\"belegart\": \"Linkliste\", \"zitat\": \"\", "
                + "\"grund\": \"Nur eine Liste von Links.\", \"urteil\": \"nicht gedeckt\"}\n```", false, false);
        assertEquals("", answer.problem);
        assertEquals(Verdict.NICHT_GEDECKT, answer.verdict);
        assertEquals(Kind.LINKLISTE, answer.kind);
        assertEquals(Kind.SONSTIGES, SentenceCheck.parse(answer("Fließtext", "", "Ein Grund dafür.", "Teilweise"),
                false, false).kind);
        assertEquals("\"belegart\" must name the kind of the passage", SentenceCheck.parse(answer("", "",
                "Ein Grund dafür.", "GEDECKT"), false, false).problem);
        assertEquals("\"grund\" must give the reason in one sentence", SentenceCheck.parse(answer("TEXT", "", "",
                "GEDECKT"), false, false).problem);

        String english = new SentenceCheck(null, "en", Options.defaults(), CLOCK).prompt(new Item("S1", SENTENCE,
                "p1", "s1", PASSAGE, "VERBAND", "CH"));
        assertTrue(english, english.startsWith("Sentence of the book:\n\"In Tempo-30-Zonen sank"));
        assertTrue(english, english.contains(" For the coverage, a statement of who says something (such as "
                + "\"according to …\", \"as stated by …\" or \", as … says\") is no part of the claim: such a "
                + "statement need not stand in the passage. Check the coverage of the claim without it.\n"));
        assertTrue(english, english.contains("- grund: one sentence: what the sentence claims (without who says "
                + "it) and"));
        assertTrue(english, english.contains("\nThe source of the passage is an association.\n"));
        assertTrue(english, english.contains("\nThe source of the passage holds for Switzerland, the book for "
                + "Germany.\n"));
        assertTrue(english, english.endsWith("Answer only with JSON: {\"belegart\": ..., \"zitat\": ..., \"grund\": "
                + "..., \"urteil\": ..., \"zuschreibung\": ..., \"rechtsraum\": ...}"));
        String cut = new SentenceCheck(null, "de", Options.defaults().withPassageChars(200), CLOCK).prompt(new Item(
                "S1", SENTENCE, "p1", "s1", PASSAGE + " " + PASSAGE));
        assertTrue(cut, cut.contains(" …“"));
        assertTrue(cut, cut.contains(" Eine Angabe, wer etwas sagt (etwa „laut …“, „nach Angaben …“, „…, wie … "
                + "angibt“ oder „so der …“), gehört für die Deckung nicht zur Aussage: Diese Angabe muss nicht in der "
                + "Passage stehen. Prüfe die Deckung der Aussage ohne sie.\n"));
        assertTrue(cut, cut.contains("- grund: ein Satz: was der Satz behauptet (ohne die Angabe, wer es sagt) und "));
    }

    @Test
    public void theNamesOfTheSourceReachTheNumberRuleButNotThePrompt() throws IOException {
        String sentence = "Nach Angaben von Rad24 sank die Zahl der Unfälle mit Verletzten im ersten Jahr um 25 "
                + "Prozent [2].";
        Scripted chat = new Scripted(messages -> MainModelChatResult.ok(answer("TEXT", QUOTE, "Die Passage nennt "
                + "den Rückgang.", "GEDECKT")));
        Item plain = item("S1", sentence);
        assertEquals(Collections.emptyList(), plain.getSourceNames());
        assertEquals("the 24 of the name is not in the quote", Verdict.TEILWEISE,
                check(chat).judge(plain).getVerdict().getVerdict());

        Item named = plain.withSourceNames(Arrays.asList("Rad24", " ", null, "Rad24", "rad24.example"));
        assertEquals(Arrays.asList("Rad24", "rad24.example"), named.getSourceNames());
        assertEquals(Collections.emptyList(), plain.getSourceNames());
        assertEquals(Verdict.GEDECKT, check(chat).judge(named).getVerdict().getVerdict());
        assertEquals("the names are no part of the prompt", chat.requests.get(0).get(1).getContent(),
                chat.requests.get(1).get(1).getContent());
        assertEquals(named.sentenceKey(), plain.sentenceKey());
        assertEquals(named.textKey(), plain.textKey());
    }

    /**
     * The prompts at {@link SentenceCheck#VERSION}, as a hash: a prompt that changed without a new version would let
     * the verdicts of the old prompt be reused.
     */
    @Test
    public void aChangedPromptNeedsANewVersion() throws Exception {
        StringBuilder all = new StringBuilder(SentenceCheck.SYSTEM_DE).append('\n').append(SentenceCheck.SYSTEM_EN);
        for (String language : new String[]{"de", "en"}) {
            SentenceCheck check = new SentenceCheck(null, language, Options.defaults(), CLOCK);
            for (String[] source : new String[][]{{"", ""}, {"HERSTELLER_HANDEL", "AT"}, {"VERBAND", "ANDERE"}}) {
                all.append('\n').append(check.prompt(new Item("S1", SENTENCE, "p1", "s1", PASSAGE, source[0],
                        source[1])));
            }
        }
        for (boolean attribution : new boolean[]{false, true}) {
            for (boolean jurisdiction : new boolean[]{false, true}) {
                all.append('\n').append(SentenceCheck.schema(attribution, jurisdiction));
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(all.toString().getBytes(StandardCharsets.UTF_8))) {
            hex.append(String.format("%02x", b & 0xff));
        }
        assertEquals("prompt changed: raise SentenceCheck.VERSION, then update this hash", "4:" + PROMPT_HASH,
                SentenceCheck.VERSION + ":" + hex);
    }

    /** The hash of the prompts of version 4. */
    static final String PROMPT_HASH = "9043157d77ef2adfc6c6ce3826df7b7c5cd57a56b12df0f3c392f0b847a01ad9";

    // ------------------------------------------------------------------------------------------------- helpers

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
