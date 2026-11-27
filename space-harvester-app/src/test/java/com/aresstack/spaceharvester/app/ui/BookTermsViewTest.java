package com.aresstack.spaceharvester.app.ui;

import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.spaceharvester.app.research.BookNotes;
import com.aresstack.writing4j.cornerstone.CornerstoneSieve;
import com.aresstack.writing4j.cornerstone.CornerstoneSurvey;
import com.aresstack.writing4j.cornerstone.FieldCards;
import com.aresstack.writing4j.cornerstone.GeneralLanguage;
import com.aresstack.writing4j.cornerstone.TermCandidates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * „Begriffe wählen“ ohne Fenster: Vorwahl des Siebs, gespeicherte Wahl des Lesers, Wahl je Feld, abgewähltes Feld. Ein
 * erfundener Korb (Bienen auf dem Dach) mit geskripteten Modellen.
 */
class BookTermsViewTest {

    private static final Fence FENCE = new Fence("Ein Ratgeber über Dachbienen in der Stadt.", "", List.of(),
            List.of("Honigvermarktung"), "", "");

    private static CornerstoneSurvey.Survey survey() {
        List<TermCandidates.Text> passages = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            passages.add(new TermCandidates.Text("b" + i, "q" + (i % 2), "Über die Dachbienen: Die Beute und die "
                    + "Rähmchen und die Varroamilbe. Dazu die Verordnung.", "Imkerei > Beute", "Bienenbuch"));
            passages.add(new TermCandidates.Text("r" + i, "q" + (i % 2), "Über die Dachbienen: Das Steuerrecht "
                    + "und die Landwirtschaft. Dazu die Verordnung und das Steuerrecht.", "Recht", "Rechtsbuch"));
        }
        return new CornerstoneSurvey(new TermCandidates(GeneralLanguage.of(Map.of()),
                new TermCandidates.Options(2, 2, 50, 1.5)), new CornerstoneSieve("de",
                CornerstoneSieve.Options.defaults(), null), new FieldCards(new Fields(), "de", null, null),
                List.of(new Sieve()), null).survey(passages, FENCE, List.of(), null);
    }

    private static String key(String term) {
        return TermCandidates.stem(term);
    }

    @Test
    void theSafeTermsArePreselectedAndTheUncertainMiddleAsksTheReader() {
        CornerstoneSurvey.Survey survey = survey();
        assertThat(survey.uncertain()).extracting(TermCandidates.Candidate::getTerm).containsExactly("Verordnung");
        assertThat(BookTermsView.needsReader(survey)).isTrue();
        assertThat(BookTermsView.numbers(survey)).contains("1 unsicher");

        BookTermsView view = new BookTermsView(survey, null);
        assertThat(view.selected().keySet()).containsExactlyInAnyOrder(key("Beute"), key("Rähmchen"),
                key("Varroamilbe"));
        assertThat(view.body()).isNotNull();
        view.show(key("Verordnung"));
        CornerstoneSurvey.Chosen chosen = view.chosen(List.of());
        assertThat(chosen.getCornerstones()).extracting(TermCandidates.Candidate::getTerm)
                .containsExactlyInAnyOrder("Beute", "Rähmchen", "Varroamilbe");
        assertThat(chosen.getExcludedFields()).isEmpty();
    }

    @Test
    void theStoredChoiceOfTheReaderBeatsTheSieveAndKeepsTheFieldOfAWord(@TempDir Path data) throws Exception {
        CornerstoneSurvey.Survey survey = survey();
        BookNotes notes = BookNotes.of(FENCE, 0).withChoice(Map.of(key("Verordnung"), List.of("Bienenhaltung")),
                List.of(key("Beute")), List.of("Recht und Handel"));
        notes.save(data, "ordner-1");
        BookNotes loaded = BookNotes.load(data, "ordner-1").orElseThrow();
        assertThat(loaded).isEqualTo(notes);

        BookTermsView view = new BookTermsView(survey, loaded);
        assertThat(view.selected()).doesNotContainKey(key("Beute")).containsKey(key("Rähmchen"));
        CornerstoneSurvey.Chosen chosen = view.chosen(List.of());
        TermCandidates.Candidate regulation = survey.candidate(key("Verordnung"));
        assertThat(chosen.passagesOf(regulation)).containsExactly("b0", "b1", "b2", "b3");
        assertThat(chosen.getExcludedFields()).containsExactly("Recht und Handel");
        assertThat(view.remember(BookNotes.of(FENCE, 0)).chosen()).containsEntry(key("Verordnung"),
                List.of("Bienenhaltung"));
        assertThat(view.remember(BookNotes.of(FENCE, 0)).unchosen()).contains(key("Beute"));
    }

    /** Sieb: die Bienenwörter Eckpfeiler, „Verordnung“ nur ohne Zaun, der Rest Umfeld oder Allgemeinwort. */
    static final class Sieve implements MainModelChat {
        private static final Pattern TERM = Pattern.compile("\nBegriff: ([^\n]*)\n");

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            String prompt = messages.get(0).getContent();
            Matcher term = TERM.matcher(prompt);
            String t = term.find() ? term.group(1).trim() : "";
            String role = switch (t) {
                case "Beute", "Rähmchen", "Varroamilbe" -> "eckpfeiler";
                case "Verordnung" -> prompt.contains("Ausgeschlossen:") ? "allgemeinwort" : "eckpfeiler";
                case "Steuerrecht" -> "umfeld";
                default -> "allgemeinwort";
            };
            return MainModelChatResult.ok(role);
        }

        @Override
        public String modelName() {
            return "sieb";
        }
    }

    /** Zwei Felder: Bienenhaltung und Recht und Handel. */
    static final class Fields implements MainModelChat {
        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            return completeJson(messages, temperature, maxOutputTokens, null);
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            String prompt = messages.get(messages.size() - 1).getContent();
            List<Integer> bees = new ArrayList<>();
            List<Integer> law = new ArrayList<>();
            Matcher line = Pattern.compile("(?m)^(\\d+)\\. (.*)$").matcher(prompt);
            while (line.find()) {
                (line.group(2).equals("Steuerrecht") ? law : bees).add(Integer.parseInt(line.group(1)));
            }
            return MainModelChatResult.ok("{\"felder\":[{\"name\":\"Bienenhaltung\",\"begriffe\":" + bees
                    + "},{\"name\":\"Recht und Handel\",\"begriffe\":" + law + "}]}");
        }

        @Override
        public String modelName() {
            return "felder";
        }
    }
}
