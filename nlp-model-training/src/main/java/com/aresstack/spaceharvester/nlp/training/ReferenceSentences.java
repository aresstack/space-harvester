package com.aresstack.spaceharvester.nlp.training;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Die synthetischen deutschen Prüfsätze aus {@code model/german-reference-sentences.conllu} (Referenzsatz aus #98,
 * Passiv, trennbare Verben, Dativ gegen Zeitangabe, Koordination, Sätze ohne Akkusativobjekt). Je Satz nennt
 * {@code # expect} die geprüften Kanten. Das Ergebnis ist ein Bericht, keine Freigabegrenze: Die Sätze zeigen, wo das
 * Modell für die objektzentrierte Auswertung trägt und wo nicht.
 */
public final class ReferenceSentences {

    private ReferenceSentences() {
    }

    public record Expectation(int word, String form, String deprel, int head) {
    }

    public record Check(Expectation expected, String predictedDeprel, int predictedHead) {

        public boolean passed() {
            return expected.head() == predictedHead && expected.deprel().equals(predictedDeprel);
        }
    }

    public record SentenceResult(String sentenceId, String text, List<Check> checks) {

        public long passed() {
            return checks.stream().filter(Check::passed).count();
        }
    }

    public record Report(int checks, int passed, List<SentenceResult> sentences) {
    }

    public static List<Expectation> expectations(ConlluSentence sentence) {
        List<String[]> words = sentence.words();
        List<Expectation> expectations = new ArrayList<>();
        for (String comment : sentence.comments()) {
            if (!comment.startsWith("# expect = ")) {
                continue;
            }
            for (String item : comment.substring("# expect = ".length()).trim().split("\\s+")) {
                int first = item.indexOf(':');
                int last = item.lastIndexOf(':');
                if (first < 0 || first == last) {
                    throw new IllegalArgumentException(sentence.sentenceId() + ": ungültige Erwartung " + item);
                }
                int word = Integer.parseInt(item.substring(0, first));
                expectations.add(new Expectation(word, words.get(word - 1)[ConlluSentence.FORM],
                        item.substring(first + 1, last), Integer.parseInt(item.substring(last + 1))));
            }
        }
        return expectations;
    }

    /** Parst jeden Satz in Vertragsform mit {@code parser} und vergleicht die erwarteten Kanten. */
    public static Report check(List<ConlluSentence> sentences, Function<ConlluSentence, ConlluSentence> parser) {
        List<SentenceResult> results = new ArrayList<>();
        int checks = 0;
        int passed = 0;
        for (ConlluSentence sentence : sentences) {
            List<String[]> predicted = parser.apply(InputContract.forParsing(sentence)).words();
            List<Check> sentenceChecks = new ArrayList<>();
            for (Expectation expectation : expectations(sentence)) {
                String[] word = predicted.get(expectation.word() - 1);
                Check check = new Check(expectation, word[ConlluSentence.DEPREL],
                        Integer.parseInt(word[ConlluSentence.HEAD]));
                sentenceChecks.add(check);
                checks++;
                passed += check.passed() ? 1 : 0;
            }
            results.add(new SentenceResult(sentence.sentenceId(), text(sentence), List.copyOf(sentenceChecks)));
        }
        return new Report(checks, passed, List.copyOf(results));
    }

    private static String text(ConlluSentence sentence) {
        return sentence.comments().stream().filter(comment -> comment.startsWith("# text = "))
                .map(comment -> comment.substring("# text = ".length())).findFirst().orElse("");
    }
}
