package com.aresstack.spaceharvester.nlp.training;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;

import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;

/**
 * Vorhergesagte Wortarten für die zweite Evaluation: das deutsche UD-Modell von Apache OpenNLP aus Maven Central,
 * dasselbe, das die Vorverarbeitung in #98 nutzt. Es wird auf Gold-Tokens angewendet; Tokenisierungsfehler sind damit
 * nicht gemessen, nur Wortartfehler.
 */
public final class OpenNlpPosTagger {

    /** Im Jar {@code org.apache.opennlp:opennlp-models-pos-de:1.3.0}. */
    public static final String MODEL_RESOURCE = "opennlp-de-ud-gsd-pos-1.3-2.5.4.bin";

    private final POSTaggerME tagger;

    private OpenNlpPosTagger(POSTaggerME tagger) {
        this.tagger = tagger;
    }

    public static OpenNlpPosTagger load() {
        try (InputStream in = OpenNlpPosTagger.class.getClassLoader().getResourceAsStream(MODEL_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(MODEL_RESOURCE + " fehlt auf dem Klassenpfad (opennlp-models-pos-de)");
            }
            return new OpenNlpPosTagger(new POSTaggerME(new POSModel(in)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<String> tag(List<String> forms) {
        return Arrays.asList(tagger.tag(forms.toArray(String[]::new)));
    }

    /** Ersetzt UPOS jedes Wortes durch die Vorhersage. */
    public ConlluSentence retag(ConlluSentence sentence) {
        List<String> forms = sentence.words().stream().map(word -> word[ConlluSentence.FORM]).toList();
        return InputContract.withUpos(sentence, tag(forms));
    }
}
