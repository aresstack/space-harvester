package com.aresstack.spaceharvester.nlp.opennlp;

import com.aresstack.spaceharvester.nlp.model.ModelUnavailableException;
import com.aresstack.spaceharvester.nlp.model.Sha256;
import com.aresstack.spaceharvester.nlp.syntax.Component;
import com.aresstack.spaceharvester.nlp.syntax.GermanPreprocessor;
import com.aresstack.spaceharvester.nlp.syntax.MultiwordToken;
import com.aresstack.spaceharvester.nlp.syntax.Sentence;
import com.aresstack.spaceharvester.nlp.syntax.Token;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import opennlp.tools.lemmatizer.LemmatizerME;
import opennlp.tools.lemmatizer.LemmatizerModel;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTagFormat;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.Span;

/**
 * Sätze, Tokens, UPOS und Grundformen mit den deutschen OpenNLP-Modellen 1.3.0 (UD German GSD) vom Klassenpfad.
 * Die Modelle werden einmal geladen und sind threadsicher; die OpenNLP-Werkzeuge ({@code …ME}) sind es nicht und
 * entstehen je Aufruf, das kostet nur Objekte, keine Modellinitialisierung.
 */
public final class OpenNlpGermanPreprocessor implements GermanPreprocessor {

    static final String TOOL = "opennlp-tools 2.5.12";
    static final String SENTENCE_MODEL = "opennlp-de-ud-gsd-sentence-1.3-2.5.4.bin";
    static final String TOKEN_MODEL = "opennlp-de-ud-gsd-tokens-1.3-2.5.4.bin";
    static final String POS_MODEL = "opennlp-de-ud-gsd-pos-1.3-2.5.4.bin";
    static final String LEMMA_MODEL = "opennlp-de-ud-gsd-lemmas-1.3-2.5.4.bin";
    /** SHA-256 je Modelldatei, wie in der {@code model.properties} der Maven-Central-Artefakte 1.3.0. */
    static final Map<String, String> SHA256 = Map.of(
            SENTENCE_MODEL, "b5553223d30a0422e80a28e2ae766a92dd7181e229f0d6c73087951e84142c43",
            TOKEN_MODEL, "32d0a7ff84fdd50f9e454340bed782d7269845bfa656e0c988cbb823dd628d6e",
            POS_MODEL, "073fefbfaff2ed403bf474fccf793752d01c22e69f487cd68b43832d3123742f",
            LEMMA_MODEL, "8850986f02dfbe293046bfce93bc0c3f3fa010e89177250abca23d860033d4f6");

    private final SentenceModel sentences;
    private final TokenizerModel tokens;
    private final POSModel tags;
    private final LemmatizerModel lemmas;

    private OpenNlpGermanPreprocessor(SentenceModel sentences, TokenizerModel tokens, POSModel tags,
                                      LemmatizerModel lemmas) {
        this.sentences = sentences;
        this.tokens = tokens;
        this.tags = tags;
        this.lemmas = lemmas;
    }

    /**
     * Lädt die vier Modelle vom Klassenpfad (Artefakte {@code org.apache.opennlp:opennlp-models-*-de}) und prüft
     * ihre SHA-256.
     */
    public static OpenNlpGermanPreprocessor fromClasspath() {
        try {
            return new OpenNlpGermanPreprocessor(new SentenceModel(resource(SENTENCE_MODEL)),
                    new TokenizerModel(resource(TOKEN_MODEL)), new POSModel(resource(POS_MODEL)),
                    new LemmatizerModel(resource(LEMMA_MODEL)));
        } catch (IOException e) {
            throw new UncheckedIOException("OpenNLP-Modell nicht lesbar", e);
        }
    }

    private static InputStream resource(String name) throws IOException {
        byte[] bytes;
        try (InputStream stream = OpenNlpGermanPreprocessor.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) {
                throw new ModelUnavailableException("OpenNLP-Modell " + name + " fehlt im Klassenpfad "
                        + "(org.apache.opennlp:opennlp-models-*-de:1.3.0)");
            }
            bytes = stream.readAllBytes();
        }
        String actual = Sha256.of(bytes);
        if (!actual.equals(SHA256.get(name))) {
            throw new ModelUnavailableException("OpenNLP-Modell " + name + " hat SHA-256 " + actual + ", erwartet "
                    + SHA256.get(name));
        }
        return new ByteArrayInputStream(bytes);
    }

    /**
     * Sätze mit Wörtern, Wortarten und Grundformen. Ein Zeilenumbruch trennt in der Textprojektion Blöcke
     * (Überschrift, Absatz, Listenpunkt) und ist deshalb eine harte Satzgrenze: Die Satzerkennung läuft je Block,
     * sonst verschmilzt eine Überschrift mit dem Folgesatz. Wortarten geschlossener Klassen, die nie richtig sein
     * können, korrigiert {@link GermanTagCorrections} vor dem Parser.
     */
    @Override
    public List<Sentence> analyze(String text) {
        SentenceDetectorME detector = new SentenceDetectorME(sentences);
        TokenizerME tokenizer = new TokenizerME(tokens);
        POSTaggerME tagger = new POSTaggerME(tags, POSTagFormat.UD);
        LemmatizerME lemmatizer = new LemmatizerME(lemmas);
        List<Sentence> result = new ArrayList<>();
        int blockStart = 0;
        while (blockStart <= text.length()) {
            int blockEnd = text.indexOf('\n', blockStart);
            if (blockEnd < 0) {
                blockEnd = text.length();
            }
            for (Span local : detector.sentPosDetect(text.substring(blockStart, blockEnd))) {
                result.add(sentence(text, blockStart + local.getStart(), blockStart + local.getEnd(), result.size(),
                        tokenizer, tagger, lemmatizer));
            }
            blockStart = blockEnd + 1;
        }
        return result;
    }

    private static Sentence sentence(String text, int sentenceStart, int sentenceEnd, int index, TokenizerME tokenizer,
                                     POSTaggerME tagger, LemmatizerME lemmatizer) {
        String sentenceText = text.substring(sentenceStart, sentenceEnd);
        List<Token> words = new ArrayList<>();
        List<MultiwordToken> multiwords = new ArrayList<>();
        for (Span span : tokenizer.tokenizePos(sentenceText)) {
            int start = sentenceStart + span.getStart();
            int end = sentenceStart + span.getEnd();
            String form = text.substring(start, end);
            List<String> parts = GermanContractions.split(form);
            if (parts == null) {
                words.add(Token.word(words.size() + 1, form, start, end));
            } else {
                int first = words.size() + 1;
                for (String part : parts) {
                    words.add(Token.word(words.size() + 1, part, start, end));
                }
                multiwords.add(new MultiwordToken(first, words.size(), form, start, end));
            }
        }
        String[] forms = words.stream().map(Token::form).toArray(String[]::new);
        String[] upos = forms.length == 0 ? new String[0] : tagger.tag(forms);
        GermanTagCorrections.correctTags(forms, upos);
        String[] lemma = forms.length == 0 ? new String[0] : lemmatizer.lemmatize(forms, upos);
        List<Token> tagged = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            tagged.add(words.get(i).withTags(GermanTagCorrections.lemma(forms[i], upos[i], lemma(lemma[i])), upos[i],
                    null, Map.of()));
        }
        return new Sentence(index, sentenceStart, sentenceEnd, sentenceText, tagged, multiwords);
    }

    /** OpenNLP meldet fehlende Grundformen als {@code O} oder {@code _}. */
    private static String lemma(String value) {
        return value == null || value.isEmpty() || "O".equals(value) || "_".equals(value) ? null : value;
    }

    @Override
    public List<Component> components() {
        return List.of(
                new Component("sentences", TOOL, SENTENCE_MODEL, SHA256.get(SENTENCE_MODEL)),
                new Component("tokens", TOOL, TOKEN_MODEL, SHA256.get(TOKEN_MODEL)),
                new Component("upos", TOOL, POS_MODEL, SHA256.get(POS_MODEL)),
                new Component("lemma", TOOL, LEMMA_MODEL, SHA256.get(LEMMA_MODEL)));
    }
}
