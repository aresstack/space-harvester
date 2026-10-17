package com.aresstack.spaceharvester.embedding.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingUnavailableException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link EmbeddingProvider} mit einem Satzmodell im Prozess (#89, ADR 0004), standardmäßig
 * {@code intfloat/multilingual-e5-small}: Tokenizer aus {@code tokenizer.json}, Modell über ONNX Runtime,
 * Mean-Pooling über die Attention-Maske und L2-Normierung, wie in der Modellkarte. Dokumenttexte bekommen das
 * Passagen-, Anfragen das Query-Präfix.
 *
 * <p>Geladen wird beim ersten Aufruf, nur nach geprüfter SHA-256 beider Dateien. Scheitert das Laden (Datei fehlt,
 * falsche Prüfsumme, unpassende Ausgabe), wirft jeder Aufruf {@link EmbeddingUnavailableException}, bis
 * {@link OnnxEmbeddingSettings#retryAfter()} vergangen ist; der Abgleich der Seiten läuft davon unberührt weiter.
 * Gleichzeitige Aufrufe begrenzt {@link OnnxEmbeddingSettings#maxConcurrent()}. Texte erscheinen nie in Logs oder
 * Fehlermeldungen.
 */
public class OnnxEmbeddingProvider implements EmbeddingProvider, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OnnxEmbeddingProvider.class);
    private static final String OUTPUT = "last_hidden_state";

    private final OnnxEmbeddingSettings settings;
    private final Clock clock;
    private final Semaphore slots;
    private final Object lock = new Object();

    private Model model;
    private Instant failedAt;
    private String failure;
    private boolean closed;

    public OnnxEmbeddingProvider(OnnxEmbeddingSettings settings) {
        this(settings, Clock.systemUTC());
    }

    OnnxEmbeddingProvider(OnnxEmbeddingSettings settings, Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.slots = new Semaphore(settings.maxConcurrent(), true);
    }

    @Override
    public String modelId() {
        return settings.modelId();
    }

    @Override
    public int dimensions() {
        return settings.dimensions();
    }

    /** Bettet Dokumenttexte ein, jeweils mit dem Passagen-Präfix. */
    @Override
    public List<float[]> embed(List<String> texts) {
        return run(texts, settings.passagePrefix());
    }

    /** Bettet Suchanfragen ein, jeweils mit dem Query-Präfix. */
    public List<float[]> embedQueries(List<String> texts) {
        return run(texts, settings.queryPrefix());
    }

    /** Token-IDs eines Textes wie sie das Modell sieht, samt Präfix; für den Vergleich mit der Referenz. */
    long[] passageTokenIds(String text) {
        return model().tokenizer.encode(settings.passagePrefix() + text, true, false).getIds();
    }

    private List<float[]> run(List<String> texts, String prefix) {
        Objects.requireNonNull(texts, "texts");
        if (texts.isEmpty()) {
            return List.of();
        }
        Model loaded = model();
        try {
            if (!slots.tryAcquire(settings.acquireTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new EmbeddingUnavailableException("Das lokale Embedding-Modell ist ausgelastet");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingUnavailableException("Beim Warten auf das lokale Embedding-Modell unterbrochen");
        }
        try {
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (int from = 0; from < texts.size(); from += settings.batchSize()) {
                List<String> batch = texts.subList(from, Math.min(texts.size(), from + settings.batchSize()));
                vectors.addAll(loaded.embed(batch.stream().map(text -> prefix + Objects.requireNonNull(text, "text"))
                        .toList()));
            }
            return vectors;
        } catch (OrtException e) {
            // Die Meldung von ONNX Runtime nennt Formen und Knoten, keine Texte.
            throw new EmbeddingUnavailableException("Das lokale Embedding-Modell ist gescheitert: " + e.getMessage());
        } finally {
            slots.release();
        }
    }

    private Model model() {
        synchronized (lock) {
            if (closed) {
                throw new EmbeddingUnavailableException("Das lokale Embedding-Modell ist geschlossen");
            }
            if (model != null) {
                return model;
            }
            if (failedAt != null && clock.instant().isBefore(failedAt.plus(settings.retryAfter()))) {
                throw new EmbeddingUnavailableException(failure);
            }
            try {
                model = Model.load(settings);
                failedAt = null;
                LOG.info("Lokales Embedding-Modell {} geladen ({} Dimensionen)", settings.modelId(),
                        settings.dimensions());
                return model;
            } catch (ModelException | IOException | OrtException | RuntimeException e) {
                failedAt = clock.instant();
                failure = "Das lokale Embedding-Modell ist nicht verfügbar: " + describe(e);
                LOG.warn("{}; neuer Versuch frühestens nach {}", failure, settings.retryAfter());
                throw new EmbeddingUnavailableException(failure);
            }
        }
    }

    private static String describe(Exception e) {
        return e instanceof ModelException ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            if (model != null) {
                model.close();
                model = null;
            }
        }
    }

    /** Mittelwert der Token-Vektoren über die Attention-Maske, dann auf Länge 1 normiert. */
    static float[] meanPoolNormalized(FloatBuffer hidden, int offset, long[] mask, int dimensions) {
        double[] sum = new double[dimensions];
        int count = 0;
        for (int t = 0; t < mask.length; t++) {
            if (mask[t] == 0) {
                continue;
            }
            count++;
            int base = offset + t * dimensions;
            for (int d = 0; d < dimensions; d++) {
                sum[d] += hidden.get(base + d);
            }
        }
        float[] vector = new float[dimensions];
        if (count == 0) {
            return vector;
        }
        double norm = 0;
        for (int d = 0; d < dimensions; d++) {
            sum[d] /= count;
            norm += sum[d] * sum[d];
        }
        norm = Math.sqrt(norm);
        for (int d = 0; d < dimensions; d++) {
            vector[d] = (float) (norm == 0 ? 0 : sum[d] / norm);
        }
        return vector;
    }

    /** Geladenes Modell samt Tokenizer; nur unter dem Semaphor benutzt. */
    private static final class Model implements AutoCloseable {

        private final OnnxEmbeddingSettings settings;
        private final OrtEnvironment environment;
        private final OrtSession session;
        private final HuggingFaceTokenizer tokenizer;
        private final boolean typeIds;

        private Model(OnnxEmbeddingSettings settings, OrtEnvironment environment, OrtSession session,
                      HuggingFaceTokenizer tokenizer, boolean typeIds) {
            this.settings = settings;
            this.environment = environment;
            this.session = session;
            this.tokenizer = tokenizer;
            this.typeIds = typeIds;
        }

        static Model load(OnnxEmbeddingSettings settings) throws IOException, OrtException, ModelException {
            verify(settings.modelFile(), settings.modelSha256(), "Modelldatei");
            verify(settings.tokenizerFile(), settings.tokenizerSha256(), "Tokenizer");
            // DJL: keine Downloads und keine Telemetrie; die native Bibliothek liegt im Jar.
            System.setProperty("ai.djl.offline", "true");
            System.setProperty("OPT_OUT_TRACKING", "true");
            HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(settings.tokenizerFile())
                    .optAddSpecialTokens(true)
                    .optTruncation(true)
                    .optMaxLength(settings.maxTokens())
                    .optPadding(false)
                    .build();
            OrtEnvironment environment = OrtEnvironment.getEnvironment();
            OrtSession session = null;
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setIntraOpNumThreads(settings.threads());
                options.setInterOpNumThreads(1);
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                session = environment.createSession(settings.modelFile().toString(), options);
                Map<String, NodeInfo> inputs = session.getInputInfo();
                if (!inputs.containsKey("input_ids") || !inputs.containsKey("attention_mask")) {
                    throw new ModelException("Das Modell erwartet nicht input_ids und attention_mask");
                }
                for (String name : inputs.keySet()) {
                    if (!List.of("input_ids", "attention_mask", "token_type_ids").contains(name)) {
                        throw new ModelException("Das Modell erwartet die unbekannte Eingabe " + name);
                    }
                }
                Model model = new Model(settings, environment, session, tokenizer,
                        inputs.containsKey("token_type_ids"));
                model.checkOutput();
                return model;
            } catch (OrtException | ModelException | RuntimeException e) {
                if (session != null) {
                    session.close();
                }
                tokenizer.close();
                throw e;
            }
        }

        private void checkOutput() throws ModelException, OrtException {
            NodeInfo output = session.getOutputInfo().get(OUTPUT);
            if (output == null) {
                throw new ModelException("Das Modell hat keine Ausgabe " + OUTPUT);
            }
            if (!(output.getInfo() instanceof TensorInfo tensor) || tensor.getShape().length != 3) {
                throw new ModelException("Die Ausgabe " + OUTPUT + " ist kein Tensor [Stapel, Token, Dimension]");
            }
            long dims = tensor.getShape()[2];
            if (dims > 0 && dims != settings.dimensions()) {
                throw new ModelException("Das Modell liefert " + dims + " statt " + settings.dimensions()
                        + " Dimensionen");
            }
        }

        List<float[]> embed(List<String> texts) throws OrtException {
            Encoding[] encodings = new Encoding[texts.size()];
            int length = 1;
            for (int i = 0; i < encodings.length; i++) {
                encodings[i] = tokenizer.encode(texts.get(i), true, false);
                length = Math.max(length, encodings[i].getIds().length);
            }
            int batch = encodings.length;
            long[] ids = new long[batch * length];
            long[] mask = new long[batch * length];
            long[][] masks = new long[batch][length];
            for (int i = 0; i < batch; i++) {
                long[] tokenIds = encodings[i].getIds();
                for (int t = 0; t < length; t++) {
                    boolean real = t < tokenIds.length;
                    ids[i * length + t] = real ? tokenIds[t] : settings.padTokenId();
                    mask[i * length + t] = real ? 1 : 0;
                    masks[i][t] = real ? 1 : 0;
                }
            }
            long[] shape = {batch, length};
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            try {
                inputs.put("input_ids", OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape));
                inputs.put("attention_mask", OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape));
                if (typeIds) {
                    inputs.put("token_type_ids", OnnxTensor.createTensor(environment,
                            LongBuffer.wrap(new long[batch * length]), shape));
                }
                try (OrtSession.Result result = session.run(inputs)) {
                    OnnxValue value = result.get(OUTPUT).orElseThrow(() ->
                            new EmbeddingUnavailableException("Das Modell lieferte keine Ausgabe " + OUTPUT));
                    OnnxTensor hidden = (OnnxTensor) value;
                    long[] outShape = hidden.getInfo().getShape();
                    if (outShape.length != 3 || outShape[0] != batch || outShape[1] != length
                            || outShape[2] != settings.dimensions()) {
                        throw new EmbeddingUnavailableException("Das Modell lieferte eine unerwartete Ausgabeform");
                    }
                    FloatBuffer buffer = hidden.getFloatBuffer();
                    List<float[]> vectors = new ArrayList<>(batch);
                    for (int i = 0; i < batch; i++) {
                        vectors.add(meanPoolNormalized(buffer, i * length * settings.dimensions(), masks[i],
                                settings.dimensions()));
                    }
                    return vectors;
                }
            } finally {
                inputs.values().forEach(OnnxTensor::close);
            }
        }

        private static void verify(Path file, String expected, String label) throws IOException, ModelException {
            if (!Files.isRegularFile(file)) {
                throw new ModelException(label + " fehlt: " + file.getFileName());
            }
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    digest.update(buffer, 0, n);
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equals(expected)) {
                throw new ModelException(label + " " + file.getFileName() + " hat die SHA-256 " + actual
                        + " statt " + expected);
            }
        }

        @Override
        public void close() {
            try {
                session.close();
            } catch (OrtException e) {
                LOG.debug("Embedding-Modell nicht sauber geschlossen", e);
            }
            tokenizer.close();
        }
    }

    /** Das Modell passt nicht zu den Einstellungen. */
    private static final class ModelException extends Exception {

        ModelException(String message) {
            super(message);
        }
    }
}
