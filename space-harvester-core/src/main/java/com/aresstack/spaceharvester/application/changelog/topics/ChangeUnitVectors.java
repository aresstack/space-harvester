package com.aresstack.spaceharvester.application.changelog.topics;

import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingUnavailableException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vektoren der Änderungseinheiten (ADR 0030, Nachtrag Abschnitt 3) über den vorhandenen {@link EmbeddingProvider}.
 * Nur Einheiten mit KI-Freigabe und Text werden eingebettet, in Stapeln, mit Obergrenze und Prüfpunkt je Stapel. Läuft
 * nur im Auftrag „Zusammenfassen“, nie beim Öffnen einer Ansicht (Auflage des Architekten).
 */
public final class ChangeUnitVectors {

    /** Texte je Aufruf des Dienstes. */
    public static final int BATCH = 32;
    /** Zeichen je eingebettetem Text. */
    static final int MAX_TEXT = 2_000;

    /**
     * Ablage der Vektoren, Schlüssel aus Text-Hash und Modell. Die dauerhafte Ablage liegt neben dem Unterschied und
     * verschwindet mit ihm und beim Entzug der KI-Freigabe; sie erscheint in keiner Abfrage, keinem Zähler, keinem
     * Export.
     */
    public interface Cache {

        Optional<float[]> find(String key);

        void put(String key, float[] vector);

        /** Nur im Speicher, für Tests und Läufe ohne Ablage. */
        static Cache inMemory() {
            Map<String, float[]> vectors = new ConcurrentHashMap<>();
            return new Cache() {
                @Override
                public Optional<float[]> find(String key) {
                    return Optional.ofNullable(vectors.get(key));
                }

                @Override
                public void put(String key, float[] vector) {
                    vectors.put(key, vector);
                }
            };
        }
    }

    /**
     * Das Ergebnis.
     *
     * @param modelId Kennung des Modells oder {@code null}, wenn der Dienst fehlte
     * @param vectors Vektor je Kennung einer Einheit
     * @param skipped freigegebene Einheiten ohne Vektor (Obergrenze erreicht oder Dienst nicht erreichbar)
     */
    public record Result(String modelId, Map<String, float[]> vectors, int skipped) {

        public Result {
            vectors = Map.copyOf(vectors);
        }
    }

    private final EmbeddingProvider provider;
    private final Cache cache;

    /** @param provider der Dienst oder {@code null}, wenn keiner eingerichtet ist */
    public ChangeUnitVectors(EmbeddingProvider provider, Cache cache) {
        this.provider = provider;
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    /**
     * Bettet die freigegebenen Einheiten ein.
     *
     * @param budget höchstens so viele Einheiten neu einbetten (Abgelegtes zählt nicht)
     * @param job    Fortschritt, Prüfpunkt und Anhalten; {@code checkpoint()} je Stapel
     */
    public Result embed(List<ChangeUnit> units, int budget, JobContext job) {
        Objects.requireNonNull(units, "units");
        Objects.requireNonNull(job, "job");
        if (provider == null) {
            return new Result(null, Map.of(), (int) units.stream().filter(ChangeUnitVectors::wanted).count());
        }
        String modelId = provider.modelId();
        Map<String, float[]> vectors = new LinkedHashMap<>();
        List<ChangeUnit> missing = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        int skipped = 0;
        for (ChangeUnit unit : units) {
            if (!wanted(unit)) {
                continue;
            }
            String key = key(text(unit), modelId);
            Optional<float[]> known = cache.find(key);
            if (known.isPresent()) {
                vectors.put(unit.id(), known.get());
            } else if (missing.size() < budget) {
                missing.add(unit);
                keys.add(key);
            } else {
                skipped++;
            }
        }
        for (int start = 0; start < missing.size(); start += BATCH) {
            job.checkpoint();
            int end = Math.min(missing.size(), start + BATCH);
            List<String> texts = missing.subList(start, end).stream().map(ChangeUnitVectors::text).toList();
            List<float[]> embedded;
            try {
                embedded = provider.embed(texts);
            } catch (EmbeddingUnavailableException unavailable) {
                skipped += missing.size() - start;
                break;
            }
            for (int i = 0; i < texts.size(); i++) {
                float[] vector = embedded.get(i);
                cache.put(keys.get(start + i), vector);
                vectors.put(missing.get(start + i).id(), vector);
            }
            job.processed(end - start);
        }
        return new Result(vectors.isEmpty() && skipped > 0 ? null : modelId, vectors, skipped);
    }

    private static boolean wanted(ChangeUnit unit) {
        return unit.aiReleased() && unit.hasText() && unit.nature() != ChangeUnit.Nature.CORRECTION;
    }

    /** Eingebetteter Text: Überschriften und die geänderten Sätze, neu vor alt; begrenzt. */
    static String text(ChangeUnit unit) {
        StringBuilder text = new StringBuilder();
        List<String> heading = unit.first().headingPath();
        if (!heading.isEmpty()) {
            text.append(String.join(" › ", heading)).append('\n');
        }
        List<String> after = unit.changedAfter().isEmpty() && unit.after() != null ? List.of(unit.after())
                : unit.changedAfter();
        List<String> before = unit.changedBefore().isEmpty() && unit.before() != null && after.isEmpty()
                ? List.of(unit.before()) : unit.changedBefore();
        text.append(String.join(" ", after));
        if (!before.isEmpty()) {
            text.append(after.isEmpty() ? "" : "\n").append(String.join(" ", before));
        }
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text.toString();
    }

    static String key(String text, String modelId) {
        try {
            return modelId + ":" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
