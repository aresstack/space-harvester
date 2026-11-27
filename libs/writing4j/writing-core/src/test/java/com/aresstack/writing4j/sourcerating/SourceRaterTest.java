package com.aresstack.writing4j.sourcerating;

import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.writing4j.engine.WritingSlots;
import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Component 16: one call per source group, a fixed schema, one repair attempt, FEHLER instead of a guess. */
public class SourceRaterTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T22:00:00Z"), ZoneOffset.UTC);

    /** A chat from a script: the first host marker found in the prompt picks the answer; thread-safe. */
    static final class Script implements MainModelChat {
        final Map<String, List<String>> byMarker = new LinkedHashMap<String, List<String>>();
        final AtomicInteger calls = new AtomicInteger();
        final List<List<ChatMessage>> requests = Collections.synchronizedList(new ArrayList<List<ChatMessage>>());
        final List<String> schemas = Collections.synchronizedList(new ArrayList<String>());
        volatile double temperature = -1;
        volatile int tokens;
        volatile boolean down;
        volatile CyclicBarrier barrier;

        /** Answers in order for prompts holding the marker; the last one repeats; {@code null} is a failed call. */
        Script answer(String marker, String... answers) {
            byMarker.put(marker, Arrays.asList(answers));
            return this;
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
            CyclicBarrier wait = barrier;
            if (wait != null) {
                try {
                    wait.await(5, TimeUnit.SECONDS);
                } catch (Exception notParallel) {
                    return MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE, "not parallel");
                }
            }
            if (down) {
                return MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "model down");
            }
            String prompt = messages.get(1).getContent();
            int asked = (messages.size() - 2) / 2;
            for (Map.Entry<String, List<String>> entry : byMarker.entrySet()) {
                if (prompt.contains(entry.getKey())) {
                    List<String> answers = entry.getValue();
                    String answer = answers.get(Math.min(asked, answers.size() - 1));
                    return answer == null ? MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "slow")
                            : MainModelChatResult.ok(answer);
                }
            }
            return MainModelChatResult.ok(rating("Unbekannt", "SONSTIGE", "OHNE", "UNKLAR", ""));
        }

        @Override
        public String modelName() {
            return "slot phase.research.sources";
        }
    }

    static String rating(String publisher, String kind, String jurisdiction, String selfInterest, String date) {
        JsonObject o = new JsonObject();
        o.addProperty("anbieter", publisher);
        o.addProperty("grund", "Grund zu " + publisher + ".");
        o.addProperty("quellenart", kind);
        o.addProperty("rechtsraum", jurisdiction);
        o.addProperty("eigeninteresse", selfInterest);
        o.addProperty("datum", date);
        return o.toString();
    }

    static SourceSample sample(String id, String address, String title, String passage) {
        return new SourceSample(id, address, title, "", passage);
    }

    private static SourceRater rater(Script chat, KnownDomains known, int parallel) {
        return new SourceRater(chat, known, "de", new SourceRater.Options(parallel, 200), null, CLOCK);
    }

    private static final String DGUV_ANSWER = rating("Deutsche Gesetzliche Unfallversicherung",
            "BEHOERDE_UNFALLVERSICHERUNG", "DE", "NEIN", "2024-03");

    @Test
    public void oneCallWithTheSchemaAndTheSuggestion() {
        Script chat = new Script().answer("dguv.de", DGUV_ANSWER);
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            longText.append("Wearables im Arbeitsschutz. ");
        }

        SourceRating rating = rater(chat, KnownDomains.shipped(), 1).rate(sample("source-1",
                "https://www.dguv.de/ifa/fachinfos/wearables/index.jsp", "Wearables  im\nArbeitsschutz",
                longText.toString()));

        assertEquals(1, chat.calls.get());
        assertEquals(0.0, chat.temperature, 0);
        assertEquals(400, chat.tokens);
        JsonObject schema = JsonParser.parseString(chat.schemas.get(0)).getAsJsonObject();
        assertEquals("[\"anbieter\",\"grund\",\"quellenart\",\"rechtsraum\",\"eigeninteresse\",\"datum\"]",
                schema.get("required").toString());
        assertFalse(schema.get("additionalProperties").getAsBoolean());
        assertEquals(9, schema.getAsJsonObject("properties").getAsJsonObject("quellenart").getAsJsonArray("enum")
                .size());
        List<ChatMessage> messages = chat.requests.get(0);
        assertEquals(SourceRater.SYSTEM_DE, messages.get(0).getContent());
        String prompt = messages.get(1).getContent();
        assertTrue(prompt.startsWith("Adresse: https://www.dguv.de/ifa/fachinfos/wearables/index.jsp\n"
                + "Domain: dguv.de\nTitel: Wearables im Arbeitsschutz\nAnfang des Textes:\n"));
        assertTrue("the passage is cut at 200 characters", prompt.contains("Arbeitsschutz. Wear …\n"));
        assertTrue(prompt.contains("Hinweis aus der Liste bekannter Anbieter: dguv.de = BEHOERDE_UNFALLVERSICHERUNG, "
                + "DE (Deutsche Gesetzliche Unfallversicherung). Übernimm das nur, wenn die Seite dazu passt."));
        assertTrue(prompt.endsWith("\"eigeninteresse\" und \"datum\"."));

        assertTrue(rating.isOk());
        assertEquals("source-1", rating.getSourceId());
        assertEquals("dguv.de", rating.getHost());
        assertEquals(SourceRating.Kind.BEHOERDE_UNFALLVERSICHERUNG, rating.getKind());
        assertEquals(SourceRating.Jurisdiction.DE, rating.getJurisdiction());
        assertEquals(SourceRating.SelfInterest.NEIN, rating.getSelfInterest());
        assertEquals("2024-03", rating.getDate());
        assertEquals("Deutsche Gesetzliche Unfallversicherung", rating.getPublisher());
        assertEquals("Grund zu Deutsche Gesetzliche Unfallversicherung.", rating.getReason());
        assertEquals("dguv.de", rating.getSuggestion().getDomain());
        assertEquals("slot phase.research.sources", rating.getModel());
        assertEquals("2026-11-21T22:00:00Z", rating.getTime());
        assertEquals(Arrays.asList(DGUV_ANSWER), rating.getAnswers());
        assertFalse(rating.needsAttribution());
        assertEquals(WritingSlots.PHASE_RESEARCH_SOURCES, SourceRater.ROLE.getSlot());
    }

    @Test
    public void withoutTheListThereIsNoSuggestion() {
        Script chat = new Script().answer("dguv.de", DGUV_ANSWER);

        SourceRating rating = rater(chat, KnownDomains.none(), 1).rate(sample("source-1", "https://dguv.de/x", "",
                ""));

        String prompt = chat.requests.get(0).get(1).getContent();
        assertFalse(prompt.contains("Hinweis aus der Liste"));
        assertTrue(prompt.contains("Titel: (keiner)\nAnfang des Textes:\n(kein Text)"));
        assertNull(rating.getSuggestion());
        assertTrue(rating.isOk());
    }

    @Test
    public void anInvalidAnswerGetsOneRepairAttempt() {
        String bad = "{\"anbieter\":\"Firma\",\"grund\":\"x\",\"quellenart\":\"BLOG\",\"rechtsraum\":\"DE\","
                + "\"eigeninteresse\":\"JA\",\"datum\":\"\"}";
        String good = rating("Wearable GmbH", "HERSTELLER_HANDEL", "OHNE", "JA", "");
        Script chat = new Script().answer("wearable-firma.com", bad, good);

        SourceRating rating = rater(chat, KnownDomains.none(), 1).rate(sample("source-2",
                "https://wearable-firma.com/blog/smartwatch", "Smartwatch für Monteure", "Unsere Uhr ..."));

        assertEquals(2, chat.calls.get());
        List<ChatMessage> second = chat.requests.get(1);
        assertEquals(4, second.size());
        assertEquals(bad, second.get(2).getContent());
        assertTrue(second.get(3).getContent().startsWith("Deine Antwort war ungültig: \"quellenart\" missing or not "
                + "one of the allowed values"));
        assertTrue(rating.isOk());
        assertEquals(SourceRating.Kind.HERSTELLER_HANDEL, rating.getKind());
        assertTrue(rating.needsAttribution());
        assertEquals(Arrays.asList(bad, good), rating.getAnswers());
    }

    @Test
    public void twoFailedCallsGiveFehlerNotAGuess() {
        Script chat = new Script();
        chat.down = true;

        SourceRating rating = rater(chat, KnownDomains.shipped(), 1).rate(sample("source-1",
                "https://www.dguv.de/x", "DGUV", "Text"));

        assertEquals(2, chat.calls.get());
        assertFalse(rating.isOk());
        assertEquals(SourceRating.Status.FEHLER, rating.getStatus());
        assertNull(rating.getKind());
        assertTrue(rating.getProblem(), rating.getProblem().startsWith("model call failed (TIMEOUT)"));
        assertEquals(Arrays.asList("", ""), rating.getAnswers());
        assertEquals("the suggestion stays recorded", "dguv.de", rating.getSuggestion().getDomain());
    }

    @Test
    public void wordsAreReadLenientlyAndAWrongDateIsDropped() {
        String answer = "{\"anbieter\":\"BAuA\",\"grund\":\"Bundesanstalt.\",\"quellenart\":\"behörde "
                + "unfallversicherung\",\"rechtsraum\":\"de\",\"eigeninteresse\":\"nein\",\"datum\":\"17.05.2023\"}";
        Script chat = new Script().answer("baua.de", answer);

        SourceRating rating = rater(chat, KnownDomains.none(), 1).rate(sample("source-5",
                "https://www.baua.de/DE/Angebote/2023/05/wearables.html", "Wearables", "Text"));

        assertTrue(rating.isOk());
        assertEquals(SourceRating.Kind.BEHOERDE_UNFALLVERSICHERUNG, rating.getKind());
        assertEquals("the model's date is no JJJJ-MM-TT; the address shows a month", "2023-05", rating.getDate());
    }

    /** Review of PR #266: without the schema a transport may drop publisher or reason; such an answer is no rating. */
    @Test
    public void publisherAndReasonMustBeThereAsText() {
        String valid = "\"quellenart\":\"HERSTELLER_HANDEL\",\"rechtsraum\":\"DE\",\"eigeninteresse\":\"NEIN\"";
        for (String answer : new String[]{"{" + valid + "}", "{\"anbieter\":\"A\"," + valid + "}",
                "{\"anbieter\":{},\"grund\":\"x\"," + valid + "}",
                "{\"anbieter\":\"A\",\"grund\":\" \"," + valid + "}"}) {
            assertEquals(answer, "\"anbieter\" and \"grund\" must be texts, \"grund\" not empty",
                    SourceRater.parse(answer).problem);
        }
        assertEquals("an empty publisher is allowed", "",
                SourceRater.parse("{\"anbieter\":\"\",\"grund\":\"x\"," + valid + "}").problem);
    }

    @Test
    public void datesFromAddresses() {
        assertEquals("2021-11-03", SourceRater.dateOf("https://x.de/news-2021-11-03.html"));
        assertEquals("2024-02-29", SourceRater.dateOf("https://x.de/2024/02/29/artikel"));
        assertEquals("2023-05", SourceRater.dateOf("https://x.de/2023/05/artikel"));
        assertEquals("a year alone is a document number as often as a date", "",
                SourceRater.dateOf("https://x.de/DIN-EN-ISO-9241-210-2019.pdf"));
        assertEquals("", SourceRater.dateOf(null));
    }

    @Test
    public void oneCallPerHostAndTheVerdictIsCopied() {
        Script chat = new Script().answer("dguv.de", DGUV_ANSWER)
                .answer("wearable-firma.com", rating("Wearable GmbH", "HERSTELLER_HANDEL", "OHNE", "JA", ""));
        List<SourceSample> sources = Arrays.asList(
                sample("source-1", "https://www.dguv.de/ifa/a.html", "DGUV A", "Text A"),
                sample("source-2", "https://wearable-firma.com/shop", "Shop", "Kaufen"),
                sample("source-3", "https://publikationen.dguv.de/2022/07/b.pdf", "DGUV B", "Text B"),
                sample("source-4", "https://dguv.de/c", "DGUV C", "Text C"));

        List<SourceRating> ratings = rater(chat, KnownDomains.shipped(), 2).rateAll(sources,
                Collections.<String, SourceRating>emptyMap());

        assertEquals("dguv.de and wearable-firma.com; publikationen.dguv.de is a host of its own", 3,
                chat.calls.get());
        assertEquals(4, ratings.size());
        SourceRating copy = ratings.get(3);
        assertEquals("source-4", copy.getSourceId());
        assertEquals("DGUV C", copy.getTitle());
        assertEquals("https://dguv.de/c", copy.getAddress());
        assertEquals("source-1", copy.getSampleId());
        assertEquals(SourceRating.Kind.BEHOERDE_UNFALLVERSICHERUNG, copy.getKind());
        assertEquals("the date belongs to the page read, not to the group", "", copy.getDate());
        assertEquals("2024-03", ratings.get(0).getDate());
        assertEquals("publikationen.dguv.de", ratings.get(2).getHost());
        assertEquals("source-3", ratings.get(2).getSampleId());
        assertEquals(SourceRating.Kind.HERSTELLER_HANDEL, ratings.get(1).getKind());
    }

    @Test
    public void aGroupIsReadFromItsFirstSourceWithText() {
        Script chat = new Script().answer("Text B", DGUV_ANSWER);
        List<SourceSample> sources = Arrays.asList(
                sample("source-1", "https://dguv.de/leer", "Ohne Text", "  "),
                sample("source-2", "https://dguv.de/b", "Mit Text", "Text B"),
                sample("source-3", "https://dguv.de/c", "Auch mit Text", "Text C"));

        List<SourceRating> ratings = rater(chat, KnownDomains.none(), 1).rateAll(sources,
                Collections.<String, SourceRating>emptyMap());

        assertEquals(1, chat.calls.get());
        assertTrue(chat.requests.get(0).get(1).getContent().contains("Titel: Mit Text\n"));
        assertEquals("source-2", ratings.get(0).getSampleId());
        assertEquals("source-1", ratings.get(0).getSourceId());
        assertEquals(SourceRating.Kind.BEHOERDE_UNFALLVERSICHERUNG, ratings.get(0).getKind());
        assertEquals("source-2", ratings.get(1).getSampleId());
        assertEquals("2024-03", ratings.get(1).getDate());
        assertEquals("source-2", ratings.get(2).getSampleId());

        Script empty = new Script();
        List<SourceRating> withoutText = rater(empty, KnownDomains.none(), 1).rateAll(Arrays.asList(
                sample("source-8", "https://a.example/1", "Eins", ""),
                sample("source-9", "https://a.example/2", "Zwei", "")), Collections.<String, SourceRating>emptyMap());
        assertTrue("no source has text: the first is read", empty.requests.get(0).get(1).getContent()
                .contains("Titel: Eins\n"));
        assertEquals("source-8", withoutText.get(1).getSampleId());
    }

    @Test
    public void aValidEarlierRatingIsReusedAFailedOneIsAskedAgain() {
        Script chat = new Script().answer("dguv.de", DGUV_ANSWER)
                .answer("wearable-firma.com", rating("Wearable GmbH", "HERSTELLER_HANDEL", "OHNE", "JA", ""));
        SourceRater rater = rater(chat, KnownDomains.none(), 1);
        SourceRating earlierOk = rater.rate(sample("source-1", "https://dguv.de/a", "A", "Text"));
        chat.down = true;
        SourceRating earlierFailed = rater.rate(sample("source-2", "https://wearable-firma.com/a", "B", "Text"));
        chat.down = false;
        chat.calls.set(0);
        Map<String, SourceRating> existing = new HashMap<String, SourceRating>();
        existing.put("dguv.de", earlierOk);
        existing.put("wearable-firma.com", earlierFailed);

        List<SourceRating> ratings = rater.rateAll(Arrays.asList(
                sample("source-1", "https://dguv.de/a", "A", "Text"),
                sample("source-7", "https://dguv.de/b", "B", "Text"),
                sample("source-2", "https://wearable-firma.com/a", "B", "Text")), existing);

        assertEquals("only the failed host is asked again", 1, chat.calls.get());
        assertTrue(ratings.get(2).isOk());
        assertEquals("source-7", ratings.get(1).getSourceId());
        assertEquals(earlierOk.getReason(), ratings.get(1).getReason());
    }

    @Test
    public void afterThreeFailedGroupsInARowTheRestIsNotAsked() {
        Script chat = new Script();
        chat.down = true;
        List<SourceSample> sources = new ArrayList<SourceSample>();
        for (int i = 1; i <= 5; i++) {
            sources.add(sample("source-" + i, "https://host" + i + ".example.org/", "T" + i, "Text"));
        }

        List<SourceRating> ratings = rater(chat, KnownDomains.none(), 1).rateAll(sources,
                Collections.<String, SourceRating>emptyMap());

        assertEquals("three groups, two calls each", 6, chat.calls.get());
        for (SourceRating rating : ratings) {
            assertFalse(rating.isOk());
        }
        assertTrue(ratings.get(2).getProblem().startsWith("model call failed"));
        assertEquals("not asked: the model failed 3 groups in a row; last: " + ratings.get(2).getProblem(),
                ratings.get(3).getProblem());
    }

    @Test
    public void groupsAreRatedInParallel() {
        Script chat = new Script();
        chat.barrier = new CyclicBarrier(4);
        List<SourceSample> sources = new ArrayList<SourceSample>();
        for (int i = 1; i <= 4; i++) {
            sources.add(sample("source-" + i, "https://host" + i + ".example.org/", "T" + i, "Text"));
        }

        List<SourceRating> ratings = rater(chat, KnownDomains.none(), 4).rateAll(sources,
                Collections.<String, SourceRating>emptyMap());

        assertEquals("four calls waited for each other", 4, chat.calls.get());
        for (int i = 0; i < 4; i++) {
            assertTrue(ratings.get(i).isOk());
            assertEquals("in the given order", "source-" + (i + 1), ratings.get(i).getSourceId());
        }
    }

    @Test
    public void englishPrompts() {
        Script chat = new Script().answer("dguv.de", DGUV_ANSWER);

        new SourceRater(chat, KnownDomains.shipped(), "en", null, null).rate(sample("source-1", "https://dguv.de/x",
                "T", "Text"));

        assertEquals(SourceRater.SYSTEM_EN, chat.requests.get(0).get(0).getContent());
        String prompt = chat.requests.get(0).get(1).getContent();
        assertTrue(prompt.startsWith("Address: https://dguv.de/x\nDomain: dguv.de\nTitle: T\n"));
        assertTrue(prompt.contains("Hint from the list of known publishers: dguv.de"));
    }


    @Test
    public void withAFenceAnAnswerWithoutTheRelationIsRepaired() {
        String plain = rating("Nord", "SONSTIGE", "OHNE", "UNKLAR", "");
        String full = plain.replace("}", ",\"gegenstand\":\"x\",\"zaun\":\"UNKLAR\",\"aussenTeile\":[]}");
        Script chat = new Script().answer("nord.example", plain, full);
        SourceRater rater = new SourceRater(chat, KnownDomains.none(), "de", new SourceRater.Options(1, 200), null,
                CLOCK, Fence.of("Auftrag"));

        SourceRating rating = rater.rate(sample("s1", "https://nord.example/a", "T", "P"));

        assertEquals(2, chat.calls.get());
        assertTrue(rating.isOk());
        assertEquals(SourceRating.Relation.UNKLAR, rating.getFit().getRelation());
    }
}
