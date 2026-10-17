package com.aresstack.spaceharvester.nlp.training;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Evaluiert ein trainiertes Modell: Dev und Test je mit Gold-Wortarten und mit von OpenNLP vorhergesagten Wortarten,
 * die Nachbar-Baseline auf Test, die synthetischen Prüfsätze und die Qualitätsgrenzen aus dem Rezept. Train dient nur
 * der Baseline und dem Labelset; Dev war Grundlage der Konfigurationswahl, Test fließt in keine Entscheidung ein.
 */
public final class ModelEvaluator {

    private final Consumer<String> log;

    public ModelEvaluator(Consumer<String> log) {
        this.log = log;
    }

    public record Splits(List<ConlluSentence> train, List<ConlluSentence> dev, List<ConlluSentence> test,
                         List<ConlluSentence> reference) {
    }

    public record TaggerAccuracy(String model, double dev, double test) {
    }

    public record QualityGate(boolean passed, Map<String, Double> minimum, List<String> failures) {
    }

    public record Report(String conventions, Map<String, DependencyEvaluation.Metrics> sets,
                         TaggerAccuracy predictedUpos, ReferenceSentences.Report referenceGoldUpos,
                         ReferenceSentences.Report referencePredictedUpos, double testSentencesPerSecond,
                         QualityGate qualityGate) {

        public Map<String, Double> headline() {
            return ModelEvaluator.headline(sets);
        }
    }

    /** Kennzahlen für Manifest und Qualitätsgrenzen, z. B. {@code test.gold.las}. */
    static Map<String, Double> headline(Map<String, DependencyEvaluation.Metrics> sets) {
        Map<String, Double> values = new LinkedHashMap<>();
        sets.forEach((name, metrics) -> {
            values.put(name + ".uas", metrics.uas());
            values.put(name + ".las", metrics.las());
        });
        return values;
    }

    static final String CONVENTIONS = "UAS/LAS in Prozent über alle syntaktischen Wörter (Interpunktion zählt mit, "
            + "Mehrwort-Zeilen und leere Knoten entfallen, wie CoNLL 2018); *WithoutPunct ohne UPOS PUNCT; LAS mit "
            + "vollem Label einschließlich Subtyp. Parsereingabe nur FORM und UPOS (InputContract). gold = UPOS aus der "
            + "Treebank, predicted = UPOS von OpenNLP auf Gold-Tokens, baseline = rechter Nachbar mit häufigstem Label "
            + "je Wortart.";

    public Report evaluate(MaltModel model, OpenNlpPosTagger tagger, Splits splits, Map<String, Double> minimum) {
        Map<String, DependencyEvaluation.Metrics> sets = new LinkedHashMap<>();
        List<ConlluSentence> devGold = gold(splits.dev());
        List<ConlluSentence> testGold = gold(splits.test());
        List<ConlluSentence> devInput = input(devGold);
        List<ConlluSentence> testInput = input(testGold);

        log.accept("Dev mit Gold-Wortarten");
        sets.put("dev.gold", DependencyEvaluation.evaluate(devGold, model.parseAll(devInput)));
        log.accept("Test mit Gold-Wortarten");
        long start = System.nanoTime();
        sets.put("test.gold", DependencyEvaluation.evaluate(testGold, model.parseAll(testInput)));
        double seconds = (System.nanoTime() - start) / 1e9;

        log.accept("Wortarten mit OpenNLP vorhersagen");
        List<ConlluSentence> devTagged = devInput.stream().map(tagger::retag).toList();
        List<ConlluSentence> testTagged = testInput.stream().map(tagger::retag).toList();
        TaggerAccuracy accuracy = new TaggerAccuracy(OpenNlpPosTagger.MODEL_RESOURCE,
                uposAccuracy(devGold, devTagged), uposAccuracy(testGold, testTagged));
        log.accept("Dev und Test mit vorhergesagten Wortarten");
        sets.put("dev.predicted", DependencyEvaluation.evaluate(devGold, model.parseAll(devTagged)));
        sets.put("test.predicted", DependencyEvaluation.evaluate(testGold, model.parseAll(testTagged)));

        AdjacencyBaseline baseline = AdjacencyBaseline.learn(gold(splits.train()));
        sets.put("test.baseline", DependencyEvaluation.evaluate(testGold, baseline.parseAll(testInput)));

        ReferenceSentences.Report referenceGold = ReferenceSentences.check(splits.reference(), model::parse);
        ReferenceSentences.Report referencePredicted = ReferenceSentences.check(splits.reference(),
                sentence -> model.parse(tagger.retag(sentence)));

        return new Report(CONVENTIONS, sets, accuracy, referenceGold, referencePredicted,
                Math.round(testGold.size() / seconds * 10) / 10.0, gate(headline(sets), minimum));
    }

    static QualityGate gate(Map<String, Double> values, Map<String, Double> minimum) {
        List<String> failures = new ArrayList<>();
        minimum.forEach((key, floor) -> {
            Double value = values.get(key);
            if (value == null) {
                failures.add(key + ": keine solche Kennzahl");
            } else if (value < floor) {
                failures.add(key + " = " + value + " < " + floor);
            }
        });
        return new QualityGate(failures.isEmpty(), new LinkedHashMap<>(minimum), List.copyOf(failures));
    }

    private static List<ConlluSentence> gold(List<ConlluSentence> sentences) {
        return sentences.stream().map(InputContract::forTraining).toList();
    }

    private static List<ConlluSentence> input(List<ConlluSentence> gold) {
        return gold.stream().map(InputContract::forParsing).toList();
    }

    static double uposAccuracy(List<ConlluSentence> gold, List<ConlluSentence> tagged) {
        int words = 0;
        int correct = 0;
        for (int s = 0; s < gold.size(); s++) {
            List<String[]> g = gold.get(s).words();
            List<String[]> t = tagged.get(s).words();
            for (int i = 0; i < g.size(); i++) {
                words++;
                correct += g.get(i)[ConlluSentence.UPOS].equals(t.get(i)[ConlluSentence.UPOS]) ? 1 : 0;
            }
        }
        return DependencyEvaluation.percent(correct, words);
    }
}
