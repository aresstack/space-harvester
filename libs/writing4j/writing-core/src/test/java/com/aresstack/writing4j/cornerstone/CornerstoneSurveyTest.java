package com.aresstack.writing4j.cornerstone;

import com.aresstack.knowledge4j.basket.OrderingHint;
import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.aresstack.knowledge4j.scope.Fence;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The sieve with votes, the field cards and the reader's two optional steps, with scripted models on an invented
 * basket (rooftop beehives).
 */
public class CornerstoneSurveyTest {

    private static final Fence FENCE = new Fence("Ein Ratgeber über Dachbienen in der Stadt.", "",
            Collections.<String>emptyList(), Collections.singletonList("Honigvermarktung"), "", "");
    private static final List<String> TERMS = Arrays.asList("Beute", "Rähmchen", "Varroamilbe", "Schwarmtrieb",
            "Steuerrecht", "Landwirtschaft");

    @Test
    public void agreementIsSafeDisagreementIsTheUncertainMiddle() {
        // Model A and B agree on Beute and Rähmchen (in), Landwirtschaft (out); they disagree on Varroamilbe.
        Scripted a = new Scripted("A", roles("eckpfeiler", "eckpfeiler", "eckpfeiler", "umfeld", "umfeld",
                "allgemeinwort"));
        Scripted b = new Scripted("B", roles("eckpfeiler", "eckpfeiler", "allgemeinwort", "umfeld", "umfeld",
                "allgemeinwort"));
        CornerstoneSurvey.Survey survey = survey(a, b).survey(passages(), FENCE, Collections.<String>emptyList(),
                null);

        assertEquals(TERMS.size(), survey.getCandidates().getCandidates().size());
        assertEquals("two models, two sets of places, with and without the fence: 8 votes per term",
                8, survey.getVotes().get(key("Beute")).getRoles().size());
        assertEquals(Arrays.asList("Beute", "Rähmchen"), terms(survey.safeIn()));
        assertEquals(Collections.singletonList("Varroamilbe"), terms(survey.uncertain()));
        assertTrue(terms(survey.safeOut()).containsAll(Arrays.asList("Schwarmtrieb", "Steuerrecht",
                "Landwirtschaft")));
        assertEquals("fields from the terms with a cornerstone or neighbour vote", 2, survey.getFields().size());

        CornerstoneSurvey.Chosen alone = CornerstoneSurvey.chosen(survey, null, null);
        assertEquals("going on without sorting takes the safe terms", Arrays.asList("Beute", "Rähmchen"),
                terms(alone.getCornerstones()));
        Map<String, String> sorted = new LinkedHashMap<String, String>();
        sorted.put(key("Varroamilbe"), key("Beute"));
        CornerstoneSurvey.Chosen chosen = CornerstoneSurvey.chosen(survey, sorted,
                Collections.singletonList(new OrderingHint("Rähmchen", "Beute")));
        assertEquals(Arrays.asList("Beute", "Rähmchen", "Varroamilbe"), terms(chosen.getCornerstones()));
        assertEquals(Arrays.asList(new OrderingHint("Rähmchen", "Beute"), new OrderingHint("Varroamilbe", "Beute")),
                chosen.getHints());
    }

    @Test
    public void theTagsTheReaderColouredAreTheCornerstonesWhateverTheSieveSaid() {
        Scripted a = new Scripted("A", roles("eckpfeiler", "eckpfeiler", "eckpfeiler", "umfeld", "umfeld",
                "allgemeinwort"));
        CornerstoneSurvey.Survey survey = survey(a).survey(passages(), FENCE, Collections.<String>emptyList(), null);
        Map<String, String> parents = new LinkedHashMap<String, String>();
        parents.put(key("Steuerrecht"), key("Beute"));
        CornerstoneSurvey.Chosen chosen = CornerstoneSurvey.selected(survey,
                new LinkedHashSet<String>(Arrays.asList(key("Rähmchen"), key("Steuerrecht"))), parents, null);
        assertEquals(Arrays.asList("Rähmchen", "Steuerrecht"), terms(chosen.getCornerstones()));
        assertEquals(Collections.singletonList(new OrderingHint("Steuerrecht", "Beute")), chosen.getHints());
        assertTrue("each tag has examples", !survey.snippets(key("Rähmchen")).isEmpty()
                && survey.snippets(key("Rähmchen")).get(0).getSentence().contains("Rähmchen"));
        assertTrue("nothing deselected, nothing excluded", chosen.getExcludedFields().isEmpty());
    }

    @Test
    public void aWordChosenForOneFieldBringsOnlyThePassagesOfThatField() {
        Scripted a = new Scripted("A", roles("eckpfeiler", "eckpfeiler", "eckpfeiler", "umfeld", "umfeld",
                "allgemeinwort"));
        List<TermCandidates.Text> passages = new ArrayList<TermCandidates.Text>();
        for (int i = 0; i < 4; i++) {
            passages.add(new TermCandidates.Text("b" + i, "q" + (i % 2), "Über die Dachbienen: Die Beute und die "
                    + "Rähmchen und die Varroamilbe. Dazu die Verordnung."));
            passages.add(new TermCandidates.Text("r" + i, "q" + (i % 2), "Über die Dachbienen: Das Steuerrecht "
                    + "und die Landwirtschaft. Dazu die Verordnung und das Steuerrecht."));
        }
        CornerstoneSurvey.Survey survey = survey(a).survey(passages, FENCE, Collections.<String>emptyList(), null);
        String regulation = key("Verordnung");
        assertEquals("the word stands in both fields", 2, survey.snippetsByField(regulation).size());
        assertEquals("Bienenhaltung", survey.fieldOf("b0"));
        Map<String, java.util.Set<String>> selected = new LinkedHashMap<String, java.util.Set<String>>();
        selected.put(regulation, Collections.singleton("Bienenhaltung"));
        CornerstoneSurvey.Chosen chosen = CornerstoneSurvey.selected(survey, selected,
                Collections.singleton("Recht und Handel"), null, null);
        assertEquals(Arrays.asList("b0", "b1", "b2", "b3"), chosen.passagesOf(survey.candidate(regulation)));
        assertEquals(Collections.singletonList("Recht und Handel"), chosen.getExcludedFields());
    }

    @Test
    public void aStrongModelLeavesNoMiddleAndAsksNothing() {
        Scripted a = new Scripted("A", roles("eckpfeiler", "eckpfeiler", "eckpfeiler", "umfeld", "allgemeinwort",
                "fremd"));
        CornerstoneSurvey.Survey survey = survey(a).survey(passages(), FENCE, Collections.<String>emptyList(), null);
        assertTrue(survey.uncertain().isEmpty());
        assertEquals(Arrays.asList("Beute", "Rähmchen", "Varroamilbe"), terms(survey.safeIn()));
    }

    @Test
    public void onlyTheTermsOfFieldsLeftLyingAreJudgedAgainAndAWantedNeighbourhoodCounts() {
        Scripted a = new Scripted("A", roles("eckpfeiler", "eckpfeiler", "eckpfeiler", "umfeld", "umfeld",
                "allgemeinwort"));
        CornerstoneSurvey surveys = survey(a);
        CornerstoneSurvey.Survey first = surveys.survey(passages(), FENCE, Collections.<String>emptyList(), null);
        int before = a.calls.get();
        a.withFenceAnswers.put("Steuerrecht", "allgemeinwort");

        CornerstoneSurvey.Survey second = surveys.withFields(first,
                new LinkedHashSet<String>(Collections.singletonList("Bienenhaltung")), null);

        assertEquals("the field left lying is excluded, the wanted one included",
                Arrays.asList("Bienenhaltung"), second.getFence().getIncluded());
        assertTrue(second.getFence().getExcluded().contains("Recht und Handel"));
        assertEquals("two runs with the fence for each of the terms of the field left lying (Steuerrecht)",
                2, a.calls.get() - before);
        assertTrue(a.lastPrompt.contains("Ausgeschlossen:\n- Honigvermarktung\n- Recht und Handel"));
        assertEquals("the neighbour vote counts in the wanted field", Arrays.asList("Beute", "Rähmchen",
                "Varroamilbe", "Schwarmtrieb"), terms(second.safeIn()));
        assertTrue(terms(second.safeOut()).contains("Steuerrecht"));
        assertTrue("skipping the cards changes nothing", surveys.withFields(first, null, null) == first);
    }

    @Test
    public void theRoleNamedLastCounts() {
        assertEquals(CornerstoneSieve.Role.UMFELD, CornerstoneSieve.roleOf("Kein eckpfeiler, sondern umfeld."));
        assertEquals(null, CornerstoneSieve.roleOf("weiß nicht"));
    }

    private static CornerstoneSurvey survey(MainModelChat... models) {
        return new CornerstoneSurvey(new TermCandidates(GeneralLanguage.of(Collections.<String, Double>emptyMap()),
                new TermCandidates.Options(2, 2, 50, 1.5)), new CornerstoneSieve("de",
                CornerstoneSieve.Options.defaults().withParallel(3), null),
                new FieldCards(new Fields(), "de", null, null), Arrays.asList(models), null);
    }

    /** Every term in passages of two sources, the subject named in each. */
    private static List<TermCandidates.Text> passages() {
        List<TermCandidates.Text> out = new ArrayList<TermCandidates.Text>();
        for (int i = 0; i < 4; i++) {
            StringBuilder text = new StringBuilder("Über die Dachbienen:");
            for (String term : TERMS) {
                text.append(" Hier geht es um ").append(term).append(" und mehr.");
            }
            out.add(new TermCandidates.Text("p" + i, "q" + (i % 2), text.toString()));
        }
        return out;
    }

    private static String key(String term) {
        return TermCandidates.stem(term);
    }

    private static List<String> terms(List<TermCandidates.Candidate> list) {
        List<String> out = new ArrayList<String>();
        for (TermCandidates.Candidate candidate : list) {
            out.add(candidate.getTerm());
        }
        Collections.sort(out, (x, y) -> TERMS.indexOf(x) - TERMS.indexOf(y));
        return out;
    }

    private static Map<String, String> roles(String... roles) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (int i = 0; i < TERMS.size(); i++) {
            out.put(TERMS.get(i), roles[i]);
        }
        return out;
    }

    /** A sieve model answering by the term; with the fence it may answer otherwise. */
    static final class Scripted implements MainModelChat {
        private static final Pattern TERM = Pattern.compile("\nBegriff: ([^\n]*)\n");
        final String name;
        final Map<String, String> answers;
        final Map<String, String> withFenceAnswers = new LinkedHashMap<String, String>();
        final AtomicInteger calls = new AtomicInteger();
        volatile String lastPrompt = "";

        Scripted(String name, Map<String, String> answers) {
            this.name = name;
            this.answers = answers;
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            calls.incrementAndGet();
            String prompt = messages.get(0).getContent();
            lastPrompt = prompt;
            Matcher term = TERM.matcher(prompt);
            String t = term.find() ? term.group(1).trim() : "";
            String role = prompt.contains("Ausgeschlossen:") && withFenceAnswers.containsKey(t)
                    ? withFenceAnswers.get(t) : answers.get(t);
            return MainModelChatResult.ok(role == null ? "fremd" : role);
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String modelName() {
            return name;
        }
    }

    /** Two fields: the keeping of bees, and law and trade. */
    static final class Fields implements MainModelChat {
        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            String prompt = messages.get(messages.size() - 1).getContent();
            List<Integer> bees = new ArrayList<Integer>();
            List<Integer> law = new ArrayList<Integer>();
            Matcher line = Pattern.compile("(?m)^(\\d+)\\. (.*)$").matcher(prompt);
            while (line.find()) {
                (line.group(2).equals("Steuerrecht") ? law : bees).add(Integer.parseInt(line.group(1)));
            }
            return MainModelChatResult.ok("{\"felder\":[{\"name\":\"Bienenhaltung\",\"begriffe\":" + bees
                    + "},{\"name\":\"Recht und Handel\",\"begriffe\":" + law + "}]}");
        }

        @Override
        public String modelName() {
            return "fields";
        }
    }
}
