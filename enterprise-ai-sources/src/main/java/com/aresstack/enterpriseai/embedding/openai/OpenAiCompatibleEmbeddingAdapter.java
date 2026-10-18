package com.aresstack.enterpriseai.embedding.openai;

import com.aresstack.enterpriseai.astu.embedding.EmbeddingModelIdentity;
import com.aresstack.enterpriseai.astu.embedding.EmbeddingVector;
import com.aresstack.enterpriseai.embedding.api.EmbeddingBatch;
import com.aresstack.enterpriseai.embedding.api.EmbeddingException;
import com.aresstack.enterpriseai.embedding.api.EmbeddingFailureKind;
import com.aresstack.enterpriseai.embedding.api.EmbeddingInputs;
import com.aresstack.enterpriseai.embedding.api.EmbeddingPort;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * {@link EmbeddingPort} für den {@code POST /embeddings}-Endpunkt der internen, OpenAI-/GPT-kompatiblen
 * Enterprise-API. Der einzige produktive Embedding-Adapter (kein Multi-Provider-Design).
 *
 * <p>Abbildung eines Port-Batches (siehe {@link EmbeddingInputMode}): im Default ein Request je Text, im
 * UNVERIFIED-Array-Modus ein Request je Teil-Batch von höchstens {@code maxBatchSize} Texten. In beiden Fällen
 * gilt die Port-Garantie: ein Vektor je Text in Eingabereihenfolge, alle mit {@link #modelIdentity()}.
 *
 * <p>Streng statt still: Transportfehler, HTTP-Fehler, falsche Anzahl, uneinheitliche Dimension oder nicht-endliche
 * Werte führen zu {@link EmbeddingException}; es gibt keine Zero-Vector-Fallbacks, keine Teilergebnisse und keinen
 * Wechsel auf ein anderes Modell.
 *
 * <p>Modellerkennung der Dimension: Die konfigurierte Dimension ist eine Erwartung, die Wahrheit ist die Antwort des
 * Dienstes. Liefert er Vektoren einer anderen (einheitlichen) Länge, übernimmt der Adapter diese Dimension, seine
 * {@link #modelIdentity()} wechselt auf die erkannte Welt und eine Logzeile nennt konfigurierte und erkannte
 * Dimension. Index und Suche lesen die Welt je Aufruf vom Port; damit liegen Passagen und Frage in einem neuen
 * Namensraum derselben Dimension, der sich über die normale Aufbereitung neu füllt. Vorher scheiterte jeder Aufruf
 * („vector has dimension 2000, expected 768“): keine Passage im Index, die semantische Suche „ausgefallen“.
 *
 * <p>Überlast: Antwortet der Dienst mit HTTP 429 oder 503, wird derselbe Request nach einer Pause wiederholt
 * (500 ms, dann 1500 ms; höchstens drei Versuche), bevor der Fehler gilt. So scheitert eine Suchanfrage nicht
 * daran, dass gleichzeitig die eigene Vorladung Passagen einbettet. Nennt der Dienst {@code Retry-After}, wird
 * mindestens so lange gewartet (höchstens {@value #MAX_RETRY_AFTER_MILLIS} ms). Alle anderen Fehler gelten sofort.
 *
 * <p>Eine Spur ({@link RequestLane}): Von diesem Adapter ist höchstens ein Request gleichzeitig unterwegs, und das
 * Embedding einer Suchanfrage ({@link #embedQueries}) kommt vor wartenden Aufbereitungs-Requests ({@link #embed}).
 * Gateways, die je Schlüssel nur eine Anfrage gleichzeitig annehmen, weisen so nichts mehr wegen der eigenen
 * Vorladung ab, und die Frage wartet höchstens auf den einen laufenden Request.
 *
 * <p>Herkunft: Transport-Naht, strikte Validierung und „kein Fallback“ aus askai-java8
 * ({@code HttpEmbeddingPortAdapter}); OpenAI-Wireformat ({@code /embeddings}, {@code data[].embedding}, Bearer)
 * aus MainframeMate ({@code MultiProviderEmbeddingClient#embedOpenAIBatch}).
 */
public final class OpenAiCompatibleEmbeddingAdapter implements EmbeddingPort {

    private final OpenAiCompatibleEmbeddingConfiguration configuration;
    private final BearerTokenSource tokenSource;
    private final EmbeddingHttpTransport transport;
    private volatile EmbeddingModelIdentity identity;
    private final Sleeper sleeper;

    private static final Logger LOG = Logger.getLogger(OpenAiCompatibleEmbeddingAdapter.class.getName());
    /** Obergrenze der Antwortgröße rechnet mindestens mit so vielen Werten je Vektor (Modellerkennung). */
    static final int MAX_DETECTABLE_DIMENSION = 8192;

    /** Wartezeiten vor dem zweiten und dritten Versuch bei HTTP 429/503. */
    private static final long[] RETRY_DELAYS_MILLIS = {500L, 1500L};
    /** Längste Pause, die ein {@code Retry-After} des Dienstes erzwingen kann. */
    static final long MAX_RETRY_AFTER_MILLIS = 5000L;

    private final RequestLane lane = new RequestLane();

    /** Wartet zwischen zwei Versuchen (in Tests ersetzbar). */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public OpenAiCompatibleEmbeddingAdapter(OpenAiCompatibleEmbeddingConfiguration configuration,
                                            BearerTokenSource tokenSource) {
        this(configuration, tokenSource, new UrlConnectionEmbeddingHttpTransport(requireConfiguration(configuration)));
    }

    OpenAiCompatibleEmbeddingAdapter(OpenAiCompatibleEmbeddingConfiguration configuration,
                                     BearerTokenSource tokenSource, EmbeddingHttpTransport transport) {
        this(configuration, tokenSource, transport, Thread::sleep);
    }

    OpenAiCompatibleEmbeddingAdapter(OpenAiCompatibleEmbeddingConfiguration configuration,
                                     BearerTokenSource tokenSource, EmbeddingHttpTransport transport,
                                     Sleeper sleeper) {
        this.configuration = requireConfiguration(configuration);
        if (tokenSource == null) {
            throw new IllegalArgumentException("tokenSource must not be null");
        }
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        this.tokenSource = tokenSource;
        this.transport = transport;
        this.identity = configuration.modelIdentity();
        this.sleeper = sleeper;
    }

    @Override
    public EmbeddingModelIdentity modelIdentity() {
        return identity;
    }

    @Override
    public EmbeddingBatch embed(List<String> texts) {
        return embed(texts, false);
    }

    /** Wie {@link #embed}, aber mit Vortritt vor wartenden Aufbereitungs-Requests (gleiche Vektoren). */
    @Override
    public EmbeddingBatch embedQueries(List<String> queries) {
        return embed(queries, true);
    }

    private EmbeddingBatch embed(List<String> texts, boolean query) {
        List<String> inputs = EmbeddingInputs.requireValid(texts);
        if (inputs.isEmpty()) {
            return EmbeddingBatch.empty(identity);
        }
        List<EmbeddingVector> vectors = new ArrayList<EmbeddingVector>(inputs.size());
        if (configuration.inputMode() == EmbeddingInputMode.ARRAY_UNVERIFIED) {
            for (int from = 0; from < inputs.size(); from += configuration.maxBatchSize()) {
                List<String> chunk = inputs.subList(from, Math.min(inputs.size(), from + configuration.maxBatchSize()));
                vectors.addAll(call(EmbeddingRequestWriter.arrayInput(configuration.modelId(), chunk), chunk.size(),
                        query));
            }
        } else {
            for (String text : inputs) {
                vectors.addAll(call(EmbeddingRequestWriter.singleInput(configuration.modelId(), text), 1, query));
            }
        }
        EmbeddingModelIdentity world = vectors.get(vectors.size() - 1).identity();
        try {
            return EmbeddingBatch.of(world, inputs.size(), vectors);
        } catch (IllegalArgumentException mixed) {
            // Der Dienst hat mitten im Batch die Dimension gewechselt: keine Teilergebnisse.
            throw new EmbeddingException(EmbeddingFailureKind.INVALID_RESPONSE,
                    "embeddings of model " + configuration.modelId() + " changed their dimension within one batch");
        }
    }

    /**
     * Misst die Vektordimension des Modells mit einem einzigen kleinen Text (Einrichtungsassistent: die Dimension
     * steht in keinem Modellkatalog). Anders als {@link #embed} prüft er nicht gegen die konfigurierte Dimension;
     * die dient hier nur als Obergrenze für die Antwortgröße.
     *
     * @return die Länge des zurückgegebenen Vektors
     * @throws EmbeddingException wie {@link #embed}
     */
    public int measureDimension(String sample) {
        List<String> inputs = EmbeddingInputs.requireValid(Collections.singletonList(sample));
        HttpResult result = postInLane(EmbeddingRequestWriter.singleInput(configuration.modelId(), inputs.get(0)),
                maxResponseBytes(1), true);
        if (result.status != 200) {
            throw EmbeddingErrorMapper.fromStatus(endpointPath(), result.status, result.body);
        }
        float[] vector = EmbeddingResponseParser.parse(result.body, 1).get(0);
        if (vector == null || vector.length == 0) {
            throw new EmbeddingException(EmbeddingFailureKind.INVALID_RESPONSE,
                    "embedding of model " + configuration.modelId() + " is empty");
        }
        return vector.length;
    }

    private List<EmbeddingVector> call(String requestBody, int expectedCount, boolean query) {
        HttpResult result = postInLane(requestBody, maxResponseBytes(expectedCount), query);
        for (int retry = 0; retry < RETRY_DELAYS_MILLIS.length && overloaded(result.status); retry++) {
            try {
                // Pause außerhalb der Spur: andere Requests (vor allem Suchanfragen) warten nicht hinter ihr.
                sleeper.sleep(delay(RETRY_DELAYS_MILLIS[retry], result.retryAfterMillis));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            result = postInLane(requestBody, maxResponseBytes(expectedCount), query);
        }
        if (result.status != 200) {
            throw EmbeddingErrorMapper.fromStatus(endpointPath(), result.status, result.body);
        }
        List<float[]> matrix = EmbeddingResponseParser.parse(result.body, expectedCount);
        EmbeddingModelIdentity world = worldOf(matrix);
        List<EmbeddingVector> vectors = new ArrayList<EmbeddingVector>(matrix.size());
        for (int i = 0; i < matrix.size(); i++) {
            try {
                vectors.add(EmbeddingVector.of(world, matrix.get(i)));
            } catch (IllegalArgumentException invalid) {
                throw new EmbeddingException(EmbeddingFailureKind.INVALID_RESPONSE,
                        "embedding " + i + " of model " + configuration.modelId() + " is invalid: "
                                + invalid.getMessage());
            }
        }
        return Collections.unmodifiableList(vectors);
    }

    /**
     * Die Welt der gelieferten Vektoren: die bisherige, oder die erkannte, wenn alle Vektoren der Antwort einheitlich
     * eine andere Länge haben. Uneinheitliche oder leere Vektoren bleiben bei der bisherigen Welt und scheitern an
     * deren Prüfung.
     */
    private EmbeddingModelIdentity worldOf(List<float[]> matrix) {
        EmbeddingModelIdentity current = identity;
        int length = matrix.isEmpty() || matrix.get(0) == null ? 0 : matrix.get(0).length;
        if (length == 0 || length == current.dimension()) {
            return current;
        }
        for (float[] vector : matrix) {
            if (vector == null || vector.length != length) {
                return current;
            }
        }
        synchronized (this) {
            if (identity.dimension() != length) {
                EmbeddingModelIdentity detected = configuration.modelIdentity(length);
                LOG.warning("Embedding-Dimension: konfiguriert " + configuration.dimension() + ", vom Dienst geliefert "
                        + length + " (Modell " + configuration.modelId() + ", " + endpointPath() + "); übernommen. "
                        + "Index und Suche nutzen ab jetzt den Namensraum " + detected.fingerprint().substring(0, 12)
                        + "; „embedding.dimension“ in den Einstellungen auf " + length + " setzen.");
                identity = detected;
            }
            return identity;
        }
    }

    /** Die vom Dienst erkannte Dimension, sonst die konfigurierte. */
    public int dimension() {
        return identity.dimension();
    }

    /**
     * Der Endpunkt für Fehlermeldungen und damit für das Protokoll: nur der Pfad ({@code /v1/embeddings}), nie der
     * Host; welcher Dienst gemeint ist, steht in der Konfiguration.
     */
    private String endpointPath() {
        String path = configuration.endpoint().getRawPath();
        return path == null || path.isEmpty() ? "/embeddings" : path;
    }

    /** Überlast des Dienstes, die sich mit kurzem Warten erfahrungsgemäß löst. */
    private static boolean overloaded(int status) {
        return status == 429 || status == 503;
    }

    /** Pause vor dem nächsten Versuch: die eigene Staffel, mindestens aber ein {@code Retry-After} (gedeckelt). */
    static long delay(long backoffMillis, long retryAfterMillis) {
        return retryAfterMillis <= backoffMillis ? backoffMillis : Math.min(retryAfterMillis, MAX_RETRY_AFTER_MILLIS);
    }

    /** Ein Request auf der Spur dieses Adapters (siehe {@link RequestLane}). */
    private HttpResult postInLane(String requestBody, long maxResponseBytes, boolean query) {
        try {
            lane.enter(query);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException(EmbeddingFailureKind.UNAVAILABLE, "embedding request to " + endpointPath()
                    + " interrupted while waiting for the previous request");
        }
        try {
            return post(requestBody, maxResponseBytes);
        } finally {
            lane.leave();
        }
    }

    private HttpResult post(String requestBody, long maxResponseBytes) {
        char[] token = tokenSource.bearerToken();
        try {
            requireHeaderSafe(token);
            return transport.post(configuration.endpoint(), requestBody, token, maxResponseBytes);
        } catch (ResponseTooLargeException ex) {
            throw new EmbeddingException(EmbeddingFailureKind.INVALID_RESPONSE, "embedding response from "
                    + endpointPath() + " is implausibly large: " + ex.getMessage(), ex);
        } catch (IOException ex) {
            // Nur Typ und Endpunkt: IOException-Texte können Proxy-/Host-Details, aber keine Header enthalten.
            throw new EmbeddingException(EmbeddingFailureKind.UNAVAILABLE, "embedding request to "
                    + endpointPath() + " failed: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() == null ? "" : " " + ex.getMessage()), ex);
        } finally {
            if (token != null) {
                Arrays.fill(token, '\0');
            }
        }
    }

    /**
     * Großzügige Obergrenze für eine Antwort mit {@code expectedCount} Vektoren: 64 Bytes je Komponente (auch bei
     * Pretty-Printing) plus Rahmen. Schützt vor unbegrenztem Puffern einer fehlerhaften Antwort.
     */
    private long maxResponseBytes(int expectedCount) {
        long components = Math.max(identity.dimension(), MAX_DETECTABLE_DIMENSION);
        return expectedCount * (components * 64L + 1024L) + 64L * 1024L;
    }

    /**
     * Ein Token mit Steuer- oder Nicht-ASCII-Zeichen würde {@code HttpURLConnection} mit einer Exception ablehnen,
     * deren Text den kompletten Header samt Token enthält. Deshalb vorher prüfen und ohne Token-Inhalt melden.
     */
    private static void requireHeaderSafe(char[] token) {
        if (token == null) {
            return;
        }
        for (char c : token) {
            if (c < 0x21 || c > 0x7E) {
                throw new EmbeddingException(EmbeddingFailureKind.AUTHENTICATION,
                        "bearer token contains characters that are not allowed in an HTTP header");
            }
        }
    }

    private static OpenAiCompatibleEmbeddingConfiguration requireConfiguration(
            OpenAiCompatibleEmbeddingConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        return configuration;
    }

    @Override
    public String toString() {
        return "OpenAiCompatibleEmbeddingAdapter{" + configuration + "}";
    }
}
