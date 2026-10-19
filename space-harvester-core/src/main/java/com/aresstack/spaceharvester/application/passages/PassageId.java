package com.aresstack.spaceharvester.application.passages;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stabile Adresse eines Absatzes, Kopie von {@code KnowledgePassageId} im Enterprise AI Client (wie askai arch:
 * unveränderliche Fassung + Position + Zerlegung + Embedding-Modell; EAC-Lücke 5). Form:
 * {@code <sourceId>:<pageId>@<capture>#p<position>@<segmentation>-<embedding>}, z. B.
 * {@code confluence:47110815@3fa9c2d1…(64 Hex)#p3@9f2c41ab-5e07d1c3}.
 * <ul>
 *   <li>{@code sourceId:pageId}: die Seite, stabil über Inhaltsänderungen (im EAC die Resource-ID);</li>
 *   <li>{@code capture}: der vollständige SHA-256 der angezeigten Fassung, Text und Gliederung
 *       ({@code PageText.inputSha256}, ohne Präfix, {@value #CAPTURE_LENGTH} Hex-Zeichen, nie gekürzt); ändert sich
 *       der Text oder die Gliederung, aus der die Grenzen folgen, ändert sich jede ID (im EAC nur der Text);</li>
 *   <li>{@code position} zählt die Absätze dieser Fassung ab 0;</li>
 *   <li>{@code segmentation} und {@code embedding} sind Kurzformen (8 Hex-Zeichen, {@link #versionTag}) des
 *       Fingerabdrucks der Zerlegung ({@link PassageSegmentation#fingerprint()}) und der ganzen Prozessorversion des
 *       Ergebnisses samt Modell ({@link #processorTag}: Format, Zerlegung, Modell oder {@value #NO_MODEL}); so ändert
 *       jede neue Berechnungsart die ID, nicht nur ein neuer Modellname.</li>
 * </ul>
 * Ändert sich eines davon, ändert sich die ID: Ein Beleg zeigt nie still auf einen anderen Absatz oder neuen Text.
 */
public final class PassageId {

    /** Länge der Capture: der vollständige SHA-256-Hex-Hash des Textes. */
    public static final int CAPTURE_LENGTH = 64;
    /** Fingerabdruck des Modells bei Absätzen nach Struktur (kein Embedding-Modell im Spiel). */
    static final String NO_MODEL = "structure";

    private static final String RESOURCE = ":";
    private static final String CAPTURE = "@";
    private static final String POSITION = "#p";
    private static final String VERSION = "@";
    private static final String SEPARATOR = "-";
    private static final String SHA256_PREFIX = "sha256:";
    private static final Pattern HEX = Pattern.compile("[0-9a-f]+");
    private static final Pattern TAG = Pattern.compile("[a-z0-9]{1,32}");
    /** Kanonisch wie {@link #of}: ohne führende Nullen, sonst gälte {@code #p007} als {@code #p7}. */
    private static final Pattern DIGITS = Pattern.compile("0|[1-9][0-9]{0,8}");

    private final String sourceId;
    private final String pageId;
    private final String capture;
    private final int position;
    private final String segmentation;
    private final String embedding;
    private final String value;

    private PassageId(String sourceId, String pageId, String capture, int position, String segmentation,
                      String embedding) {
        this.sourceId = sourceId;
        this.pageId = pageId;
        this.capture = capture;
        this.position = position;
        this.segmentation = segmentation;
        this.embedding = embedding;
        this.value = sourceId + RESOURCE + pageId + CAPTURE + capture + POSITION + position + VERSION + segmentation
                + SEPARATOR + embedding;
    }

    /**
     * @param capture die Capture des Textes, siehe {@link #captureOf(String)}
     * @throws IllegalArgumentException wenn ein Teil nicht die verlangte Form hat
     */
    public static PassageId of(String sourceId, String pageId, String capture, int position, String segmentation,
                               String embedding) {
        if (sourceId == null || sourceId.isEmpty() || sourceId.contains(RESOURCE) || pageId == null
                || pageId.isEmpty()) {
            throw new IllegalArgumentException("Seite fehlt oder ist ungültig");
        }
        if (!isCapture(capture)) {
            throw new IllegalArgumentException("Capture muss " + CAPTURE_LENGTH + " Hex-Zeichen haben");
        }
        if (position < 0) {
            throw new IllegalArgumentException("position darf nicht negativ sein: " + position);
        }
        if (!isTag(segmentation) || !isTag(embedding)) {
            throw new IllegalArgumentException("Versionskennungen müssen [a-z0-9] sein");
        }
        return new PassageId(sourceId, pageId, capture, position, segmentation, embedding);
    }

    /**
     * Die Capture einer Fassung: ihr vollständiger SHA-256 (hex), mit oder ohne Präfix {@code sha256:}.
     *
     * @throws IllegalArgumentException wenn der Hash fehlt oder kein vollständiger SHA-256-Hex ist
     */
    public static String captureOf(String sha256) {
        String capture = sha256 == null ? "" : sha256.toLowerCase(Locale.ROOT);
        if (capture.startsWith(SHA256_PREFIX)) {
            capture = capture.substring(SHA256_PREFIX.length());
        }
        if (!isCapture(capture)) {
            throw new IllegalArgumentException("Hash fehlt oder ist kein SHA-256-Hex");
        }
        return capture;
    }

    /** {@code true}, wenn {@code value} die Form einer Passage-ID hat. */
    public static boolean looksLike(String value) {
        try {
            parse(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Liest eine Passage-ID. Die Seiten-ID darf {@code @}, {@code #} und {@code :} enthalten: gelesen wird von hinten,
     * die Quellen-ID endet am ersten {@code :}.
     *
     * @throws IllegalArgumentException wenn {@code value} keine Passage-ID ist; die Meldung nennt den Wert nicht
     */
    public static PassageId parse(String value) {
        int at = value == null ? -1 : value.lastIndexOf(VERSION);
        int hash = at <= 0 ? -1 : value.lastIndexOf(POSITION, at);
        int captureAt = hash <= 0 ? -1 : value.lastIndexOf(CAPTURE, hash);
        int resource = captureAt <= 0 ? -1 : value.indexOf(RESOURCE);
        if (resource <= 0 || resource >= captureAt - 1) {
            throw new IllegalArgumentException("Ungültige Passage-ID");
        }
        String position = value.substring(hash + POSITION.length(), at);
        String versions = value.substring(at + VERSION.length());
        int separator = versions.indexOf(SEPARATOR);
        if (!DIGITS.matcher(position).matches() || separator <= 0) {
            throw new IllegalArgumentException("Ungültige Passage-ID");
        }
        return of(value.substring(0, resource), value.substring(resource + RESOURCE.length(), captureAt),
                value.substring(captureAt + CAPTURE.length(), hash), Integer.parseInt(position),
                versions.substring(0, separator), versions.substring(separator + 1));
    }

    /** Kurzform (8 Hex-Zeichen aus SHA-256) eines Fingerabdrucks für die Versionsteile der ID. */
    public static String versionTag(String fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(fingerprint.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt", e);
        }
    }

    /**
     * Modellteil der ID aus der Prozessorversion des Ergebnisses ({@code Enrichment.processorVersion}) und dem Modell,
     * mit dem es geschnitten wurde ({@code null}: nach Struktur), nicht aus dem Modellnamen allein: Ändern sich die
     * Grenzen bei gleichem Namen (neues Format, anderer Dienst, sobald sein Fingerabdruck in der Version steht), ändert
     * sich auch die ID; eine Zerlegung nach Struktur trägt nie die ID einer semantischen derselben Version.
     */
    public static String processorTag(String processorVersion, String model) {
        Objects.requireNonNull(processorVersion, "processorVersion");
        return versionTag(processorVersion + "\n" + (model == null ? NO_MODEL : model));
    }

    private static boolean isCapture(String capture) {
        return capture != null && capture.length() == CAPTURE_LENGTH && HEX.matcher(capture).matches();
    }

    private static boolean isTag(String tag) {
        return tag != null && TAG.matcher(tag).matches();
    }

    public String sourceId() {
        return sourceId;
    }

    public String pageId() {
        return pageId;
    }

    /** Die Capture, also die Fassung der Seite (Text und Gliederung), zu der der Absatz gehört. */
    public String capture() {
        return capture;
    }

    public int position() {
        return position;
    }

    public String segmentation() {
        return segmentation;
    }

    public String embedding() {
        return embedding;
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof PassageId id && value.equals(id.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
