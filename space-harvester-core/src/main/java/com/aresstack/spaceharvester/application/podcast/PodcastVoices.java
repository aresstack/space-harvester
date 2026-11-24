package com.aresstack.spaceharvester.application.podcast;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * Die Stimmen der TTS-Modelle für den Podcast, aus {@code tts-voices.properties} neben dieser Klasse (Kopie der Datei des
 * Enterprise AI Client, {@code TtsVoiceCatalog}): je Modellfamilie (Orpheus, Qwen3-TTS) männliche und weibliche Stimmen
 * und eine Standardstimme. Der Podcast ist ein Gespräch zweier Stimmen: Gastgeber ist die Standardstimme, Co-Host die
 * erste Stimme der anderen Stimmlage. Jede Anfrage nennt ihre Stimme, denn ohne feste Stimme wählen Modelle mit mehreren
 * Sprechern je Stück selbst und der Podcast klänge jedes Mal anders.
 */
public final class PodcastVoices {

    static final String RESOURCE = "tts-voices.properties";

    /** Zwei Stimmen eines Modells; {@code null}, wenn die Familie unbekannt ist (dann wählt der Dienst). */
    public record Pair(String host, String cohost) {
    }

    private record Family(String id, List<String> match, List<String> male, List<String> female, String fallback) {
    }

    private final List<Family> families;

    PodcastVoices(Properties properties) {
        List<Family> parsed = new ArrayList<>();
        for (String id : split(properties.getProperty("families", ""))) {
            List<String> match = split(properties.getProperty(id + ".match", "")).stream()
                    .map(token -> token.toLowerCase(Locale.ROOT)).toList();
            List<String> male = split(properties.getProperty(id + ".male", ""));
            List<String> female = split(properties.getProperty(id + ".female", ""));
            String fallback = properties.getProperty(id + ".default", "").strip();
            if (!match.isEmpty() && (!male.isEmpty() || !female.isEmpty())) {
                parsed.add(new Family(id, match, male, female, fallback));
            }
        }
        this.families = List.copyOf(parsed);
    }

    /** Die mitgelieferten Stimmen. */
    public static PodcastVoices bundled() {
        Properties properties = new Properties();
        try (InputStream in = PodcastVoices.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
            }
        } catch (IOException e) {
            properties = new Properties();
        }
        return new PodcastVoices(properties);
    }

    /**
     * Die Stimmen zur Auswahl in den Einstellungen: die der Familie des Modells, Standardstimme zuerst; ohne Modell
     * oder bei unbekannter Familie die Stimmen aller Familien.
     */
    public List<String> voices(String modelId) {
        List<Family> chosen = family(modelId).map(List::of).orElse(families);
        java.util.LinkedHashSet<String> voices = new java.util.LinkedHashSet<>();
        for (Family family : chosen) {
            if (!family.fallback().isEmpty()) {
                voices.add(family.fallback());
            }
            voices.addAll(family.female());
            voices.addAll(family.male());
        }
        return List.copyOf(voices);
    }

    /** Ob das Modell einer bekannten Familie angehört. */
    public boolean knows(String modelId) {
        return family(modelId).isPresent();
    }

    /**
     * Gastgeber und Co-Host: die Standardstimme der Familie und die erste Stimme der anderen Stimmlage; gibt es keine
     * andere Stimmlage, die nächste Stimme derselben. Unbekannte Familie: beide {@code null}.
     */
    public Pair pair(String modelId) {
        Optional<Family> found = family(modelId);
        if (found.isEmpty()) {
            return new Pair(null, null);
        }
        Family family = found.get();
        String host = !family.fallback().isEmpty() ? family.fallback()
                : !family.female().isEmpty() ? family.female().get(0) : family.male().get(0);
        List<String> other = family.female().contains(host) ? family.male() : family.female();
        List<String> same = family.female().contains(host) ? family.female() : family.male();
        String cohost = !other.isEmpty() ? other.get(0)
                : same.stream().filter(voice -> !voice.equals(host)).findFirst().orElse(host);
        return new Pair(host, cohost);
    }

    /**
     * Das bevorzugte Modell aus den TTS-Modellen des Dienstes: zuerst eines der Familie Orpheus (deutsche Stimmen), dann
     * Qwen3-TTS, dann jede andere bekannte Familie, zuletzt ein unbekanntes; die übrigen folgen als Ausweichmodelle.
     */
    public List<String> ranked(List<String> models) {
        return models.stream().sorted(Comparator.comparingInt(this::rank)).toList();
    }

    private int rank(String modelId) {
        Optional<Family> found = family(modelId);
        if (found.isEmpty()) {
            return families.size();
        }
        return families.indexOf(found.get());
    }

    private Optional<Family> family(String modelId) {
        if (modelId == null) {
            return Optional.empty();
        }
        String id = modelId.toLowerCase(Locale.ROOT);
        Family best = null;
        int bestLength = 0;
        for (Family family : families) {
            for (String token : family.match()) {
                if (id.contains(token) && token.length() > bestLength) {
                    best = family;
                    bestLength = token.length();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        for (String part : text.split(",")) {
            if (!part.isBlank()) {
                parts.add(part.strip());
            }
        }
        return parts;
    }
}
