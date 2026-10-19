package com.aresstack.spaceharvester.application.passages;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;

/**
 * Deutsche Satzgrenzen aus dem OpenNLP-Satzmodell (UD German GSD, {@code opennlp-models-sentdetect-de} 1.3.0), wie
 * {@code IndexSentenceSegmenter} im Enterprise AI Client und askai arch {@code OpenNlpSentenceSegmenter}. OpenNLP dient
 * nur den Satzgrenzen. Das Modell (rund 100 KB) lädt einmal; fehlt es im Klassenpfad oder hat es eine andere SHA-256,
 * gilt der regelbasierte Splitter mit seiner eigenen {@link #id()}.
 */
public final class OpenNlpSentenceSegmenter implements SentenceSegmenter {

    static final String MODEL = "opennlp-de-ud-gsd-sentence-1.3-2.5.4.bin";
    /** SHA-256 der Modelldatei wie in der {@code model.properties} des Maven-Central-Artefakts (auch :nlp-parser). */
    static final String MODEL_SHA256 = "b5553223d30a0422e80a28e2ae766a92dd7181e229f0d6c73087951e84142c43";

    private static final System.Logger LOG = System.getLogger(OpenNlpSentenceSegmenter.class.getName());

    private final SentenceDetectorME detector;
    private final SentenceSegmenter fallback;

    private OpenNlpSentenceSegmenter(SentenceModel model, SentenceSegmenter fallback) {
        this.detector = new SentenceDetectorME(model);
        this.fallback = fallback;
    }

    /**
     * Deutsch; ohne ladbares Modell {@code fallback}.
     *
     * @param fallback regelbasierte Satzgrenzen, {@link SentenceSegmenter#splitter}
     */
    public static SentenceSegmenter german(SentenceSegmenter fallback) {
        try {
            return new OpenNlpSentenceSegmenter(new SentenceModel(model()), fallback);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "OpenNLP-Satzmodell nicht ladbar ({0}), Satzgrenzen nach Regeln",
                    e.getClass().getName());
            return fallback;
        }
    }

    @Override
    public List<String> sentences(String paragraph) {
        String[] found;
        synchronized (this) { // OpenNLP ist nicht threadsicher
            found = detector.sentDetect(paragraph);
        }
        List<String> sentences = new ArrayList<>(found.length);
        for (String sentence : found) {
            String trimmed = sentence.strip();
            if (!trimmed.isEmpty()) {
                sentences.add(trimmed);
            }
        }
        return sentences.isEmpty() ? fallback.sentences(paragraph) : sentences;
    }

    @Override
    public String id() {
        return "opennlp-de";
    }

    private static InputStream model() throws IOException {
        byte[] bytes;
        try (InputStream stream = OpenNlpSentenceSegmenter.class.getClassLoader().getResourceAsStream(MODEL)) {
            if (stream == null) {
                throw new IOException("Satzmodell fehlt");
            }
            bytes = stream.readAllBytes();
        }
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!actual.equals(MODEL_SHA256)) {
                throw new IOException("Satzmodell hat eine andere SHA-256");
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return new ByteArrayInputStream(bytes);
    }
}
