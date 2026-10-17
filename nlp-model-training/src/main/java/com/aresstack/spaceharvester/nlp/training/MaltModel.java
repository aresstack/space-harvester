package com.aresstack.spaceharvester.nlp.training;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.maltparser.concurrent.ConcurrentMaltParserModel;
import org.maltparser.concurrent.ConcurrentMaltParserService;
import org.maltparser.core.exception.MaltChainedException;

/**
 * Ein geladenes Modell über die thread-sichere MaltParser-API, so wie ein Verbraucher es lädt: Die Evaluation misst
 * damit genau den Weg, den das Laufzeitmodul aus #98 nimmt. Der Konfigurationsname ergibt sich aus dem Dateinamen
 * ({@code maltparser-de-gsd.mco}); die Datei darf deshalb nicht umbenannt werden.
 */
public final class MaltModel {

    private final ConcurrentMaltParserModel model;

    private MaltModel(ConcurrentMaltParserModel model) {
        this.model = model;
    }

    public static MaltModel load(Path mco) {
        try {
            return new MaltModel(ConcurrentMaltParserService.initializeParserModel(mco.toUri().toURL()));
        } catch (MaltChainedException | java.net.MalformedURLException e) {
            throw new IllegalStateException("Modell " + mco + " lässt sich nicht laden: " + e.getMessage(), e);
        }
    }

    /**
     * Parst einen Satz in Vertragsform ({@link InputContract#forParsing}) und gibt ihn mit vorhergesagten HEAD und
     * DEPREL zurück; die übrigen Spalten bleiben, wie sie waren.
     */
    public ConlluSentence parse(ConlluSentence input) {
        List<String[]> words = input.words();
        String[] tokens = new String[words.size()];
        for (int i = 0; i < tokens.length; i++) {
            String[] columns = words.get(i).clone();
            columns[ConlluSentence.HEAD] = "_";
            columns[ConlluSentence.DEPREL] = "_";
            tokens[i] = String.join("\t", columns);
        }
        String[] parsed;
        try {
            parsed = model.parseTokens(tokens);
        } catch (MaltChainedException e) {
            throw new IllegalStateException("Parsen fehlgeschlagen (" + input.sentenceId() + "): " + e.getMessage(), e);
        }
        if (parsed.length != tokens.length) {
            throw new IllegalStateException(tokens.length + " Wörter, aber " + parsed.length + " geparste Zeilen");
        }
        List<String[]> lines = new ArrayList<>(words.size());
        for (int i = 0; i < parsed.length; i++) {
            String[] out = parsed[i].split("\t", -1);
            String[] columns = words.get(i).clone();
            columns[ConlluSentence.HEAD] = out[ConlluSentence.HEAD];
            columns[ConlluSentence.DEPREL] = out[ConlluSentence.DEPREL];
            lines.add(columns);
        }
        return new ConlluSentence(input.comments(), lines);
    }

    public List<ConlluSentence> parseAll(List<ConlluSentence> inputs) {
        return inputs.stream().map(this::parse).toList();
    }
}
