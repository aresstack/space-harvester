package com.aresstack.spaceharvester.nlp.training;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Einfache Untergrenze für die Evaluation: Jedes Wort hängt am linken Nachbarn, das erste ist die Wurzel; das Label
 * ist das im Train-Teil häufigste für die Wortart. Ein trainiertes Modell muss deutlich darüber liegen.
 */
public final class AdjacencyBaseline {

    private final Map<String, String> labelByUpos;

    private AdjacencyBaseline(Map<String, String> labelByUpos) {
        this.labelByUpos = labelByUpos;
    }

    public static AdjacencyBaseline learn(List<ConlluSentence> train) {
        Map<String, Map<String, Integer>> counts = new HashMap<>();
        for (ConlluSentence sentence : train) {
            for (String[] word : sentence.words()) {
                if (!word[ConlluSentence.HEAD].equals("0")) {
                    counts.computeIfAbsent(word[ConlluSentence.UPOS], k -> new HashMap<>())
                            .merge(word[ConlluSentence.DEPREL], 1, Integer::sum);
                }
            }
        }
        Map<String, String> labels = new HashMap<>();
        counts.forEach((upos, byLabel) -> labels.put(upos, byLabel.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .orElseThrow().getKey()));
        return new AdjacencyBaseline(labels);
    }

    public ConlluSentence parse(ConlluSentence input) {
        List<String[]> lines = new ArrayList<>();
        int id = 0;
        for (String[] word : input.words()) {
            String[] columns = word.clone();
            columns[ConlluSentence.HEAD] = Integer.toString(id);
            columns[ConlluSentence.DEPREL] = id == 0 ? "root"
                    : labelByUpos.getOrDefault(word[ConlluSentence.UPOS], "dep");
            lines.add(columns);
            id++;
        }
        return new ConlluSentence(input.comments(), lines);
    }

    public List<ConlluSentence> parseAll(List<ConlluSentence> inputs) {
        return inputs.stream().map(this::parse).toList();
    }
}
