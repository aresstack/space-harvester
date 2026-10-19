package com.aresstack.spaceharvester.application.facts;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Leitet die Fakten einer Seite aus einem gespeicherten Anreicherungsergebnis ab, deterministisch und ohne Quelle oder
 * Text: dasselbe Ergebnis ergibt dieselben Fakten mit denselben Kennungen. Nur Ergebnisse mit
 * {@link Enrichment.Status#OK} der Prozessoren {@value #ACRONYMS}, {@value #ENTITIES} und {@value #PROPERTIES} tragen
 * Fakten (Aufbau: {@link FactTypes}); ein Ergebnis, das sich nicht lesen lässt, trägt keine. Die Ergebnisformate
 * (Prozessorversionen) bleiben unverändert.
 */
public final class EnrichmentFacts {

    public static final String ACRONYMS = "acronyms";
    public static final String ENTITIES = "entities";
    public static final String PROPERTIES = "properties";

    /** Prozessoren, deren Ergebnisse Fakten tragen. */
    public static final Set<String> PROCESSORS = Set.of(ACRONYMS, ENTITIES, PROPERTIES);

    /** Höchstens so viele Fakten je Ergebnis. */
    public static final int MAX_PER_RESULT = 2_000;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int ID_BYTES = 16;

    private EnrichmentFacts() {
    }

    /** Ob die Ergebnisse dieses Prozessors Fakten tragen. */
    public static boolean derivesFrom(String processorId) {
        return PROCESSORS.contains(processorId);
    }

    /** Die Fakten des Ergebnisses, leer für andere Prozessoren, fehlgeschlagene oder unlesbare Ergebnisse. */
    public static List<MetadataFact> of(Enrichment enrichment) {
        if (!derivesFrom(enrichment.processorId()) || enrichment.status() != Enrichment.Status.OK) {
            return List.of();
        }
        JsonNode payload;
        try {
            payload = JSON.readTree(enrichment.payloadJson());
        } catch (JacksonException e) {
            return List.of();
        }
        List<MetadataFact> facts = new ArrayList<>();
        MetadataFact.Provenance provenance = MetadataFact.Provenance.of(enrichment);
        try {
            switch (enrichment.processorId()) {
                case ACRONYMS -> acronyms(enrichment.key(), payload, provenance, facts);
                case ENTITIES -> entities(enrichment.key(), payload, provenance, facts);
                case PROPERTIES -> properties(enrichment.key(), payload, provenance, facts);
                default -> {
                    return List.of();
                }
            }
        } catch (IllegalArgumentException e) {
            // Ein Eintrag passt nicht zum Format: lieber keine Fakten als halbe.
            return List.of();
        }
        return facts.size() > MAX_PER_RESULT ? List.copyOf(facts.subList(0, MAX_PER_RESULT)) : List.copyOf(facts);
    }

    private static void acronyms(PageKey key, JsonNode payload, MetadataFact.Provenance provenance,
                                 List<MetadataFact> facts) {
        for (JsonNode entry : payload.path("acronyms")) {
            String acronym = text(entry, "acronym");
            if (acronym == null) {
                continue;
            }
            String expansion = text(entry, "expansion");
            String section = text(entry, "section");
            MetadataFact.Anchor anchor = anchor(section, entry, "charStart", null);
            ObjectNode candidate = JSON.createObjectNode();
            candidate.put("shortForm", acronym);
            candidate.put("count", entry.path("count").asInt());
            putNullable(candidate, "expansion", expansion);
            putNullable(candidate, "section", section);
            facts.add(fact(key, FactTypes.ACRONYM_CANDIDATE, FactTypes.MENTIONS, candidate, provenance, anchor,
                    facts.size(), List.of(argument(FactTypes.SHORT_FORM, acronym))));
            if (expansion != null) {
                ObjectNode definition = JSON.createObjectNode();
                definition.put("shortForm", acronym);
                definition.put("longForm", expansion);
                putNullable(definition, "section", section);
                facts.add(fact(key, FactTypes.ACRONYM_DEFINITION, FactTypes.DEFINES, definition, provenance, anchor,
                        facts.size(), List.of(argument(FactTypes.SHORT_FORM, acronym),
                                argument(FactTypes.LONG_FORM, expansion))));
            }
        }
    }

    private static void entities(PageKey key, JsonNode payload, MetadataFact.Provenance provenance,
                                 List<MetadataFact> facts) {
        for (JsonNode entry : payload.path("entities")) {
            String term = text(entry, "term");
            String normalized = text(entry, "normalized");
            if (term == null || normalized == null) {
                continue;
            }
            String kind = text(entry, "kind");
            String section = text(entry, "section");
            ObjectNode mention = JSON.createObjectNode();
            mention.put("term", term);
            mention.put("normalized", normalized);
            putNullable(mention, "kind", kind);
            mention.put("count", entry.path("count").asInt());
            putNullable(mention, "section", section);
            List<MetadataFact.Argument> arguments = new ArrayList<>();
            arguments.add(argument(FactTypes.TERM, normalized));
            if (kind != null) {
                arguments.add(argument(FactTypes.KIND, kind));
            }
            facts.add(fact(key, FactTypes.MENTION, FactTypes.MENTIONS, mention, provenance,
                    anchor(section, entry, "charStart", "charEnd"), facts.size(), arguments));
        }
    }

    private static void properties(PageKey key, JsonNode payload, MetadataFact.Provenance provenance,
                                   List<MetadataFact> facts) {
        for (JsonNode entry : payload.path("properties")) {
            String subject = text(entry, "subject");
            String subjectNormalized = text(entry, "subjectNormalized");
            String property = text(entry, "property");
            String normalized = text(entry, "normalized");
            if (subject == null || subjectNormalized == null || property == null || normalized == null) {
                continue;
            }
            String kind = text(entry, "kind");
            String form = text(entry, "form");
            boolean negated = entry.path("negated").asBoolean();
            String section = text(entry, "section");
            ObjectNode value = JSON.createObjectNode();
            value.put("subject", subject);
            value.put("property", property);
            value.put("normalized", normalized);
            putNullable(value, "kind", kind);
            putNullable(value, "form", form);
            value.put("negated", negated);
            putNullable(value, "section", section);
            List<MetadataFact.Argument> arguments = new ArrayList<>();
            arguments.add(argument(FactTypes.SUBJECT, subjectNormalized));
            arguments.add(argument(FactTypes.VALUE, normalized));
            if (kind != null) {
                arguments.add(argument(FactTypes.KIND, kind));
            }
            arguments.add(argument(FactTypes.STATUS, negated ? "negated" : "asserted"));
            facts.add(fact(key, FactTypes.PROPERTY, null, value, provenance,
                    anchor(section, entry, "charStart", "charEnd"), facts.size(), arguments));
        }
    }

    private static MetadataFact fact(PageKey key, String type, String role, ObjectNode payload,
                                     MetadataFact.Provenance provenance, MetadataFact.Anchor anchor, int position,
                                     List<MetadataFact.Argument> arguments) {
        return new MetadataFact(id(key, provenance.processorId(), position), key, type, role, payload.toString(),
                arguments, provenance, anchor);
    }

    private static MetadataFact.Argument argument(String role, String value) {
        String normalized = FactTypes.normalize(value);
        if (normalized.length() > MetadataFact.Argument.MAX_VALUE_LENGTH) {
            normalized = normalized.substring(0, MetadataFact.Argument.MAX_VALUE_LENGTH);
        }
        return new MetadataFact.Argument(role, 0, normalized);
    }

    private static MetadataFact.Anchor anchor(String section, JsonNode entry, String start, String end) {
        Integer charStart = entry.path(start).canConvertToInt() && entry.path(start).isNumber()
                ? entry.path(start).asInt() : null;
        Integer charEnd = end != null && entry.path(end).isNumber() ? entry.path(end).asInt() : null;
        return section == null && charStart == null ? null : new MetadataFact.Anchor(section, charStart, charEnd);
    }

    private static String text(JsonNode entry, String field) {
        JsonNode value = entry.get(field);
        if (value == null || !value.isString()) {
            return null;
        }
        String text = value.asString().strip();
        return text.isEmpty() ? null : text;
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    /** Kennung aus Seite, Prozessor und Position; Hash, damit sie weder Quelle noch Seite verrät. */
    static String id(PageKey key, String processorId, int position) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((key.sourceId() + '\u0000' + key.pageId()
                    + '\u0000' + processorId + '\u0000' + position).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, ID_BYTES);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
