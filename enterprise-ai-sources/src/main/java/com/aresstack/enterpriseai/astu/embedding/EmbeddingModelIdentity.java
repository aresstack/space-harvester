package com.aresstack.enterpriseai.astu.embedding;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Semantische Identität einer Embedding-Welt: Modell-ID, Dimension und die Konfigurationsmerkmale, die die
 * erzeugten Vektoren beeinflussen (z. B. Modellversion, Normalisierung, angefragte {@code dimensions}).
 *
 * <p>Zwei Identitäten sind genau dann gleich, wenn ihre Vektoren vergleichbar sind. Der {@link #fingerprint()}
 * ist ein SHA-256 über eine kanonische Darstellung aller Merkmale und eignet sich als stabiler Schlüssel, z. B.
 * als Namespace eines Vektorindex. Bewusst <em>nicht</em> Teil der Identität sind Transportdetails wie URL,
 * Timeout oder Zugangsdaten: dasselbe Modell hinter zwei Endpunkten ist dieselbe Welt, und Secrets dürfen nie
 * in einen Fingerprint oder Index gelangen.
 *
 * <p>Konzept übernommen aus askai-java8 ({@code EmbeddingEndpointDescriptor#embeddingFingerprint}), hier ohne
 * Endpunktdaten und mit beliebigen, sortierten Konfigurationsmerkmalen.
 */
public final class EmbeddingModelIdentity {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String CANONICAL_FORMAT_VERSION = "embedding-identity/v1";

    private final String modelId;
    private final int dimension;
    private final SortedMap<String, String> attributes;
    private final String fingerprint;

    private EmbeddingModelIdentity(String modelId, int dimension, SortedMap<String, String> attributes) {
        this.modelId = modelId;
        this.dimension = dimension;
        this.attributes = Collections.unmodifiableSortedMap(attributes);
        this.fingerprint = sha256Hex(canonicalForm());
    }

    /**
     * @param modelId   Modell-ID wie vom Provider verwendet, nicht leer
     * @param dimension Anzahl der Vektorkomponenten, größer 0
     */
    public static EmbeddingModelIdentity of(String modelId, int dimension) {
        return new EmbeddingModelIdentity(requireText(modelId, "modelId"), requireDimension(dimension),
                new TreeMap<String, String>());
    }

    /**
     * Liefert eine neue Identität mit einem zusätzlichen (oder ersetzten) Konfigurationsmerkmal. Merkmale dürfen
     * keine Secrets enthalten, weil sie in den Fingerprint und damit in Indizes eingehen.
     */
    public EmbeddingModelIdentity withAttribute(String key, String value) {
        String k = requireText(key, "attribute key");
        if (value == null) {
            throw new IllegalArgumentException("attribute value must not be null: " + k);
        }
        requireWellFormed(value, "attribute value of " + k);
        SortedMap<String, String> copy = new TreeMap<String, String>(attributes);
        copy.put(k, value);
        return new EmbeddingModelIdentity(modelId, dimension, copy);
    }

    public String modelId() {
        return modelId;
    }

    public int dimension() {
        return dimension;
    }

    /** Unveränderliche, nach Schlüssel sortierte Konfigurationsmerkmale. */
    public SortedMap<String, String> attributes() {
        return attributes;
    }

    /** SHA-256 (hex, 64 Zeichen) über Modell-ID, Dimension und alle Merkmale. */
    public String fingerprint() {
        return fingerprint;
    }

    /** Wahr genau dann, wenn Vektoren beider Identitäten miteinander verglichen werden dürfen. */
    public boolean isSameWorldAs(EmbeddingModelIdentity other) {
        return equals(other);
    }

    /**
     * Wirft {@link EmbeddingWorldMismatchException}, wenn {@code other} eine andere Embedding-Welt ist.
     */
    public void requireSameWorldAs(EmbeddingModelIdentity other) {
        if (!isSameWorldAs(other)) {
            throw new EmbeddingWorldMismatchException(this, other);
        }
    }

    private String canonicalForm() {
        StringBuilder sb = new StringBuilder(CANONICAL_FORMAT_VERSION);
        appendField(sb, "model", modelId);
        appendField(sb, "dimension", Integer.toString(dimension));
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            appendField(sb, "attr." + entry.getKey(), entry.getValue());
        }
        return sb.toString();
    }

    /** Längenpräfix statt Trennzeichen, damit kein Merkmal ein anderes vortäuschen kann. */
    private static void appendField(StringBuilder sb, String name, String value) {
        sb.append('\n').append(name.length()).append(':').append(name)
                .append('=').append(value.length()).append(':').append(value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EmbeddingModelIdentity)) {
            return false;
        }
        EmbeddingModelIdentity that = (EmbeddingModelIdentity) o;
        return dimension == that.dimension && modelId.equals(that.modelId) && attributes.equals(that.attributes);
    }

    @Override
    public int hashCode() {
        return fingerprint.hashCode();
    }

    @Override
    public String toString() {
        return "EmbeddingModelIdentity{model=" + modelId + ", dimension=" + dimension
                + ", attributes=" + attributes + ", fingerprint=" + fingerprint.substring(0, 12) + "}";
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return requireWellFormed(value.trim(), name);
    }

    /**
     * Ungepaarte Surrogate würde {@code getBytes(UTF-8)} durch {@code ?} ersetzen; zwei verschiedene Identitäten
     * bekämen dann denselben Fingerprint. Deshalb nur wohlgeformtes UTF-16 zulassen.
     */
    private static String requireWellFormed(String value, String name) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new IllegalArgumentException(name + " contains an unpaired surrogate at index " + i);
            }
        }
        return value;
    }

    private static int requireDimension(int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive: " + dimension);
        }
        return dimension;
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by every Java 8 platform", ex);
        }
    }
}
