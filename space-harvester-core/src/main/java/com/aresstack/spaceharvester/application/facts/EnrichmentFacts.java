package com.aresstack.spaceharvester.application.facts;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Leitet die Fakten einer Seite aus einem gespeicherten Anreicherungsergebnis ab, deterministisch und ohne Quelle oder
 * Text: dasselbe Ergebnis ergibt dieselben Fakten mit denselben Kennungen. Nur Ergebnisse mit
 * {@link Enrichment.Status#OK} der Prozessoren {@value #ACRONYMS}, {@value #ENTITIES}, {@value #PROPERTIES},
 * {@value #ACTIVITIES}, {@value #RELATIONS} und {@value #STATEMENTS} tragen
 * Fakten (Aufbau: {@link FactTypes}); ein Ergebnis, das sich nicht lesen lässt, trägt keine. Die Ergebnisformate
 * (Prozessorversionen) bleiben unverändert. {@value #STATEMENTS} (Satzanalyse über das Chat-Modell, #108) ergibt
 * dieselben Typen wie der regelbasierte Weg ({@code MENTION}, {@code EVENT}, {@code RELATION}) mit denselben
 * Argumentrollen; {@code STATUS} ist dort der Extraktionsstatus des Client ({@code extracted}, {@code model_validated},
 * {@code uncertain}, {@code unresolved_reference}), und die Herkunft nennt Prozessor und Version mit Chat-Modell.
 */
public final class EnrichmentFacts {

    public static final String ACRONYMS = "acronyms";
    public static final String ENTITIES = "entities";
    public static final String PROPERTIES = "properties";
    public static final String ACTIVITIES = "activities";
    public static final String RELATIONS = "relations";
    /** Satzanalyse über das Chat-Modell ({@code StatementProcessor}, #108). */
    public static final String STATEMENTS = "statements";

    /** Prozessoren, deren Ergebnisse Fakten tragen. */
    public static final Set<String> PROCESSORS = Set.of(ACRONYMS, ENTITIES, PROPERTIES, ACTIVITIES, RELATIONS,
            STATEMENTS);

    /** Höchstens so viele Fakten je Ergebnis, also je Prozessor und Seite; was darüber liegt, meldet {@link #truncated}. */
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
        List<MetadataFact> facts = derive(enrichment);
        return facts.size() > MAX_PER_RESULT ? List.copyOf(facts.subList(0, MAX_PER_RESULT)) : List.copyOf(facts);
    }

    /** Ob das Ergebnis mehr als {@value #MAX_PER_RESULT} Fakten trägt, {@link #of} also nicht alle liefert. */
    public static boolean truncated(Enrichment enrichment) {
        return derive(enrichment).size() > MAX_PER_RESULT;
    }

    private static List<MetadataFact> derive(Enrichment enrichment) {
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
                case ACTIVITIES -> activities(enrichment.key(), payload, provenance, facts);
                case RELATIONS -> relations(enrichment.key(), payload, provenance, facts);
                case STATEMENTS -> statements(enrichment.key(), payload, provenance, facts);
                default -> {
                    return List.of();
                }
            }
        } catch (IllegalArgumentException e) {
            // Ein Eintrag passt nicht zum Format: lieber keine Fakten als halbe.
            return List.of();
        }
        return facts;
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

    private static void activities(PageKey key, JsonNode payload, MetadataFact.Provenance provenance,
                                   List<MetadataFact> facts) {
        for (JsonNode entry : payload.path("activities")) {
            String predicate = text(entry, "predicate");
            String lemma = text(entry, "lemma");
            if (predicate == null || lemma == null) {
                continue;
            }
            String modal = text(entry, "modal");
            boolean negated = entry.path("negated").asBoolean();
            String section = text(entry, "section");
            ObjectNode value = JSON.createObjectNode();
            value.put("predicate", predicate);
            value.put("lemma", lemma);
            putNullable(value, "voice", text(entry, "voice"));
            putNullable(value, "mood", text(entry, "mood"));
            putNullable(value, "modal", modal);
            value.put("negated", negated);
            ArrayNode participants = value.putArray("participants");
            Arguments arguments = new Arguments();
            arguments.add(FactTypes.PREDICATE, lemma);
            for (JsonNode participant : entry.path("participants")) {
                String role = text(participant, "role");
                String term = text(participant, "term");
                String normalized = text(participant, "normalized");
                if (role == null || term == null) {
                    continue;
                }
                boolean pronoun = participant.path("pronoun").asBoolean();
                ObjectNode copy = participants.addObject();
                copy.put("role", role);
                copy.put("term", term);
                putNullable(copy, "normalized", normalized);
                copy.put("pronoun", pronoun);
                if (!pronoun && normalized != null) {
                    // Pronomen ohne aufgelösten Bezug wären als Suchwert nur Rauschen.
                    arguments.add(role, normalized);
                }
            }
            putNullable(value, "section", section);
            arguments.add(FactTypes.STATUS, negated ? "negated" : "asserted");
            qualifiers(arguments, negated, modal != null);
            facts.add(fact(key, FactTypes.EVENT, null, value, provenance,
                    anchor(section, entry, "charStart", "charEnd"), facts.size(), arguments.list));
        }
    }

    private static void relations(PageKey key, JsonNode payload, MetadataFact.Provenance provenance,
                                  List<MetadataFact> facts) {
        for (JsonNode entry : payload.path("relations")) {
            String kind = text(entry, "type");
            String subject = text(entry, "subject");
            String object = text(entry, "object");
            if (kind == null || subject == null || object == null) {
                continue;
            }
            String subjectNormalized = text(entry, "subjectNormalized");
            String objectNormalized = text(entry, "objectNormalized");
            String predicate = text(entry, "predicate");
            boolean negated = entry.path("negated").asBoolean();
            String section = text(entry, "section");
            ObjectNode value = JSON.createObjectNode();
            value.put("kind", kind);
            value.put("subject", subject);
            putNullable(value, "subjectNormalized", subjectNormalized);
            putNullable(value, "predicate", predicate);
            value.put("object", object);
            putNullable(value, "objectNormalized", objectNormalized);
            value.put("negated", negated);
            putNullable(value, "cue", text(entry, "cue"));
            putNullable(value, "section", section);
            Arguments arguments = new Arguments();
            arguments.add(FactTypes.KIND, kind);
            arguments.add(FactTypes.SUBJECT, subjectNormalized != null ? subjectNormalized : subject);
            arguments.add(FactTypes.OBJECT, objectNormalized != null ? objectNormalized : object);
            if (predicate != null) {
                arguments.add(FactTypes.VALUE, predicate);
            }
            arguments.add(FactTypes.STATUS, negated ? "negated" : "asserted");
            qualifiers(arguments, negated, false);
            facts.add(fact(key, FactTypes.RELATION, null, value, provenance,
                    anchor(section, entry, "charStart", "charEnd"), facts.size(), arguments.list));
        }
    }

    /**
     * Die Aussagen der Satzanalyse (#108) wie {@code FactCatalogStatements} im Client: je Erwähnung ein {@code MENTION},
     * je Handlung ein {@code EVENT}, je Eigenschaft oder Folge ein {@code RELATION}. Pronomen ohne aufgelösten Bezug
     * werden kein Suchwert ({@code TERM}, Rolle), wie bei {@code activities}.
     */
    private static void statements(PageKey key, JsonNode payload, MetadataFact.Provenance provenance,
                                   List<MetadataFact> facts) {
        for (JsonNode entry : payload.path("mentions")) {
            String surface = text(entry, "surface");
            String normalized = text(entry, "normalized");
            String status = text(entry, "status");
            if (surface == null || normalized == null || status == null) {
                continue;
            }
            boolean pronoun = entry.path("pronoun").asBoolean();
            String section = text(entry, "section");
            ObjectNode mention = JSON.createObjectNode();
            mention.put("term", surface);
            mention.put("normalized", normalized);
            putNullable(mention, "lemmaCandidate", text(entry.path("lemmaCandidate"), "value"));
            mention.put("pronoun", pronoun);
            mention.put("status", status);
            putNullable(mention, "section", section);
            Arguments arguments = new Arguments();
            if (!pronoun) {
                arguments.add(FactTypes.TERM, normalized);
            }
            arguments.add(FactTypes.STATUS, status);
            facts.add(fact(key, FactTypes.MENTION, FactTypes.MENTIONS, mention, provenance,
                    anchor(section, entry, "charStart", "charEnd"), facts.size(), arguments.list));
        }
        for (JsonNode entry : payload.path("events")) {
            String predicate = text(entry, "predicate");
            String normalized = text(entry, "normalized");
            String status = text(entry, "status");
            if (predicate == null || normalized == null || status == null) {
                continue;
            }
            String section = text(entry, "section");
            ObjectNode value = JSON.createObjectNode();
            value.put("predicate", predicate);
            value.put("lemma", normalized);
            ArrayNode participants = value.putArray("participants");
            Arguments arguments = new Arguments();
            arguments.add(FactTypes.PREDICATE, normalized);
            for (JsonNode participant : entry.path("participants")) {
                String role = text(participant, "role");
                String term = text(participant, "term");
                String participantNormalized = text(participant, "normalized");
                if (role == null || term == null) {
                    continue;
                }
                boolean pronoun = participant.path("pronoun").asBoolean();
                ObjectNode copy = participants.addObject();
                copy.put("role", role);
                copy.put("term", term);
                putNullable(copy, "normalized", participantNormalized);
                copy.put("pronoun", pronoun);
                if (!pronoun && participantNormalized != null) {
                    arguments.add(role, participantNormalized);
                }
            }
            boolean negated = contextText(entry, "negation", value);
            boolean modal = contextText(entry, "modality", value);
            boolean conditional = contextText(entry, "condition", value);
            value.put("status", status);
            putNullable(value, "section", section);
            arguments.add(FactTypes.STATUS, status);
            qualifiers(arguments, negated, modal, conditional);
            facts.add(fact(key, FactTypes.EVENT, null, value, provenance,
                    anchor(section, entry, "charStart", "charEnd"), facts.size(), arguments.list));
        }
        for (JsonNode entry : payload.path("relations")) {
            String kind = text(entry, "kind");
            String subject = text(entry.path("subject"), "term");
            String subjectNormalized = text(entry.path("subject"), "normalized");
            String literal = text(entry, "value");
            String normalized = text(entry, "normalized");
            String status = text(entry, "status");
            if (kind == null || subject == null || subjectNormalized == null || literal == null || normalized == null
                    || status == null) {
                continue;
            }
            String object = text(entry.path("object"), "term");
            String objectNormalized = text(entry.path("object"), "normalized");
            String section = text(entry, "section");
            ObjectNode value = JSON.createObjectNode();
            value.put("kind", kind.toLowerCase(java.util.Locale.ROOT));
            value.put("subject", subject);
            value.put("subjectNormalized", subjectNormalized);
            putNullable(value, "object", object);
            putNullable(value, "objectNormalized", objectNormalized);
            value.put("value", literal);
            value.put("normalized", normalized);
            boolean negated = contextText(entry, "negation", value);
            boolean modal = contextText(entry, "modality", value);
            boolean conditional = contextText(entry, "condition", value);
            value.put("status", status);
            putNullable(value, "section", section);
            Arguments arguments = new Arguments();
            arguments.add(FactTypes.KIND, kind);
            arguments.add(FactTypes.SUBJECT, subjectNormalized);
            if (objectNormalized != null) {
                arguments.add(FactTypes.OBJECT, objectNormalized);
            }
            arguments.add(FactTypes.VALUE, normalized);
            arguments.add(FactTypes.STATUS, status);
            qualifiers(arguments, negated, modal, conditional);
            facts.add(fact(key, FactTypes.RELATION, null, value, provenance,
                    anchor(section, entry, "charStart", "charEnd"), facts.size(), arguments.list));
        }
    }

    /** Übernimmt den Wortlaut eines Kontextteils ({@code negation}, …) und sagt, ob es ihn gibt. */
    private static boolean contextText(JsonNode entry, String field, ObjectNode value) {
        String words = text(entry.path(field), "text");
        putNullable(value, field, words);
        return words != null;
    }

    /** Wie {@link #qualifiers(Arguments, boolean, boolean)}, dazu {@code conditional} wie im Client. */
    private static void qualifiers(Arguments arguments, boolean negated, boolean modal, boolean conditional) {
        qualifiers(arguments, negated, modal);
        if (conditional) {
            arguments.add(FactTypes.QUALIFIER, "conditional");
        }
    }

    /** {@code QUALIFIER} wie {@code FactCatalogStatements} im Client: {@code negated}, {@code modal}. */
    private static void qualifiers(Arguments arguments, boolean negated, boolean modal) {
        if (negated) {
            arguments.add(FactTypes.QUALIFIER, "negated");
        }
        if (modal) {
            arguments.add(FactTypes.QUALIFIER, "modal");
        }
    }

    /** Argumente in Reihenfolge; gleiche Rollen zählen ihre Ordnungszahl hoch. */
    private static final class Arguments {

        private final List<MetadataFact.Argument> list = new ArrayList<>();
        private final Map<String, Integer> ordinals = new HashMap<>();

        void add(String role, String value) {
            int ordinal = ordinals.merge(role, 1, Integer::sum) - 1;
            MetadataFact.Argument first = argument(role, value);
            list.add(new MetadataFact.Argument(first.role(), ordinal, first.value()));
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
