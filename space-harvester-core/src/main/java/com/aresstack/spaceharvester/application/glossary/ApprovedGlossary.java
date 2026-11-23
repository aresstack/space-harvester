package com.aresstack.spaceharvester.application.glossary;

import com.aresstack.spaceharvester.application.terms.TermText;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ein fachlich geprüftes Glossar, das der Space Harvester mitliefert (Angelo 23.11.2026): Begriffe und Erläuterungen im
 * Wortlaut einer veröffentlichten Quelle. {@link Glossary#adopt} übernimmt die Einträge in das vorhandene Fachglossar
 * als {@code MANUAL} und {@code CONFIRMED} im globalen Geltungsbereich; es gibt kein zweites Glossar. Die Quelle bleibt
 * je Eintrag nachvollziehbar über {@link #reference}.
 *
 * <p>Schema {@value #SCHEMA}: {@code id}, {@code title}, {@code url} und {@code entries} mit {@code term}
 * (Suchbegriff), {@code wording} (Begriff im Wortlaut der Quelle, nur wenn er vom Suchbegriff abweicht, etwa
 * „Annahmebeauftragte (pl.)“), {@code explanation} (wörtlich), {@code page} (Seite der Quelle) und wahlweise
 * {@code abbreviations} und {@code variants} aus dem Wortlaut des Begriffs.
 *
 * @param id      stabile Kennung des Glossars; zugleich Urheber der übernommenen Einträge ({@link #actor})
 * @param title   Titel der Quelle
 * @param url     Fundstelle der Quelle
 * @param entries Einträge in der Reihenfolge der Quelle, je Suchwert höchstens einer
 */
public record ApprovedGlossary(String id, String title, String url, List<Entry> entries) {

    public static final String SCHEMA = "approved-glossary/v1";

    /** Mitgelieferte geprüfte Glossare, neben dieser Klasse im Klassenpfad. */
    static final List<String> BUNDLED = List.of("geprueft-zrb-vv-bho-anlage-1-glossar.json");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * Ein Begriff des geprüften Glossars.
     *
     * @param term          Suchbegriff, wie er im Text vorkommt
     * @param wording       Begriff im Wortlaut der Quelle
     * @param explanation   Erläuterung im Wortlaut der Quelle
     * @param page          Seite der Quelle
     * @param abbreviations Abkürzungen aus dem Wortlaut, etwa {@code ABR}
     * @param variants      weitere Schreibweisen aus dem Wortlaut, etwa „Buchungsbeleg“ zu „(Buchungs-)Beleg“
     */
    public record Entry(String term, String wording, String explanation, int page, List<String> abbreviations,
                        List<String> variants) {

        public Entry {
            Objects.requireNonNull(term, "term");
            if (!TermText.usable(term.strip())) {
                throw new IllegalArgumentException("Unbrauchbarer Begriff: " + term);
            }
            term = term.strip();
            wording = wording == null || wording.isBlank() ? term : wording.strip();
            explanation = explanation == null || explanation.isBlank() ? null : explanation.strip();
            if (explanation != null && explanation.length() > Glossary.MAX_EXPLANATION_CHARS) {
                throw new IllegalArgumentException("Erläuterung zu lang: " + term);
            }
            abbreviations = abbreviations == null ? List.of() : List.copyOf(abbreviations);
            variants = variants == null ? List.of() : List.copyOf(variants);
        }
    }

    /**
     * Woher ein Eintrag des Fachglossars stammt.
     *
     * @param glossaryId Kennung des geprüften Glossars
     * @param title      Titel der Quelle
     * @param url        Fundstelle der Quelle
     * @param wording    Begriff im Wortlaut der Quelle
     * @param page       Seite der Quelle
     */
    public record Reference(String glossaryId, String title, String url, String wording, int page) {
    }

    public ApprovedGlossary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(url, "url");
        Map<String, Entry> byKey = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (byKey.putIfAbsent(TermText.normalize(entry.term()), entry) != null) {
                throw new IllegalArgumentException("Begriff doppelt im geprüften Glossar " + id + ": " + entry.term());
            }
        }
        entries = List.copyOf(entries);
    }

    /** Urheber der übernommenen Einträge im Fachglossar und im Änderungsprotokoll. */
    public String actor() {
        return "glossary:" + id;
    }

    /** Herkunft eines Suchwerts ({@link TermText#normalize}), wenn er in diesem Glossar steht. */
    public Optional<Reference> reference(String normalized) {
        for (Entry entry : entries) {
            if (TermText.normalize(entry.term()).equals(normalized)) {
                return Optional.of(new Reference(id, title, url, entry.wording(), entry.page()));
            }
        }
        return Optional.empty();
    }

    /** Herkunft eines Eintrags im Fachglossar: nur globale Einträge, deren Suchwert ein geprüftes Glossar nennt. */
    public static Optional<Reference> reference(List<ApprovedGlossary> glossaries, GlossaryEntry entry) {
        if (!entry.scope().global()) {
            return Optional.empty();
        }
        for (ApprovedGlossary glossary : glossaries) {
            Optional<Reference> found = glossary.reference(entry.normalized());
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** Die mitgelieferten geprüften Glossare, einmal gelesen. */
    public static List<ApprovedGlossary> bundled() {
        return Bundled.ALL;
    }

    /** @throws IllegalStateException wenn eine mitgelieferte Datei fehlt oder ein anderes Schema hat */
    static ApprovedGlossary read(String resource) {
        try (InputStream input = ApprovedGlossary.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Geprüftes Glossar fehlt: " + resource);
            }
            JsonNode root = JSON.readTree(input);
            if (!SCHEMA.equals(root.path("schema").asString(null))) {
                throw new IllegalStateException(resource + " hat nicht das Schema " + SCHEMA);
            }
            List<Entry> entries = new ArrayList<>();
            for (JsonNode item : root.path("entries")) {
                entries.add(new Entry(item.path("term").asString(), text(item, "wording"), text(item, "explanation"),
                        item.path("page").asInt(), strings(item.path("abbreviations")),
                        strings(item.path("variants"))));
            }
            return new ApprovedGlossary(root.path("id").asString(), root.path("title").asString(),
                    root.path("url").asString(), entries);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asString() : null;
    }

    private static List<String> strings(JsonNode items) {
        List<String> found = new ArrayList<>();
        for (JsonNode item : items) {
            found.add(item.asString());
        }
        return found;
    }

    private static final class Bundled {
        static final List<ApprovedGlossary> ALL = BUNDLED.stream().map(ApprovedGlossary::read).toList();
    }
}
