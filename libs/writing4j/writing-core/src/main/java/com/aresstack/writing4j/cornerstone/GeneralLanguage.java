package com.aresstack.writing4j.cornerstone;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * How common a word is in the general language, on the Zipf scale (log10 of the occurrences per billion words). A
 * term of the basket that is much more frequent there than in the general language belongs to the subject; a word
 * as frequent as everywhere does not. German comes with Writing4j (the word list of wordfreq, CC BY-SA 4.0, see
 * {@code wordfreq-de.NOTICE.txt} beside the list); for another language the list is empty and every word counts as
 * unknown.
 */
public final class GeneralLanguage {

    private static final String GERMAN = "wordfreq-de.txt.gz";
    private static volatile GeneralLanguage german;

    private final Map<String, Double> zipf;

    private GeneralLanguage(Map<String, Double> zipf) {
        this.zipf = zipf;
    }

    /** The list of the language ({@code de} or empty for others). */
    public static GeneralLanguage forLanguage(String language) {
        String l = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        return l.isEmpty() || l.startsWith("de") ? german() : of(Collections.<String, Double>emptyMap());
    }

    /** The German list shipped with Writing4j, read once. */
    public static GeneralLanguage german() {
        GeneralLanguage loaded = german;
        if (loaded == null) {
            synchronized (GeneralLanguage.class) {
                loaded = german;
                if (loaded == null) {
                    loaded = new GeneralLanguage(read(GERMAN));
                    german = loaded;
                }
            }
        }
        return loaded;
    }

    /** A list given by the caller (word to Zipf value), the words normalised as {@link #key} does. */
    public static GeneralLanguage of(Map<String, Double> zipf) {
        Map<String, Double> copy = new HashMap<String, Double>();
        for (Map.Entry<String, Double> entry : zipf.entrySet()) {
            copy.put(key(entry.getKey()), entry.getValue());
        }
        return new GeneralLanguage(copy);
    }

    /** The Zipf value of the word, {@code 0} when the list does not know it. */
    public double zipf(String word) {
        Double value = zipf.get(key(word));
        return value == null ? 0 : value;
    }

    public boolean knows(String word) {
        return zipf.containsKey(key(word));
    }

    public int size() {
        return zipf.size();
    }

    /** A word as the list writes it: lower case, ß as ss, digits as 0 (the normalisation of wordfreq). */
    public static String key(String word) {
        String lower = word == null ? "" : word.trim().toLowerCase(Locale.ROOT).replace("ß", "ss");
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            out.append(Character.isDigit(c) ? '0' : c);
        }
        return out.toString();
    }

    private static Map<String, Double> read(String name) {
        Map<String, Double> out = new HashMap<String, Double>(1 << 19);
        try (InputStream raw = GeneralLanguage.class.getResourceAsStream(name)) {
            if (raw == null) {
                throw new IllegalStateException("word list missing: " + name);
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw),
                    StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                double value = 9.0 - Integer.parseInt(line.substring(0, tab)) / 100.0;
                for (String word : line.substring(tab + 1).split(" ")) {
                    if (!word.isEmpty()) {
                        out.putIfAbsent(word, value);
                    }
                }
            }
        } catch (IOException failed) {
            throw new UncheckedIOException("word list not readable: " + name, failed);
        }
        return out;
    }
}
