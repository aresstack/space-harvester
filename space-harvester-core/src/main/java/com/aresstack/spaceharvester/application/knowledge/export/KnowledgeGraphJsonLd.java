package com.aresstack.spaceharvester.application.knowledge.export;

import com.aresstack.spaceharvester.application.knowledge.*;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.terms.TermText;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.json.JsonMapper;

/**
 * Schreibt Aussagenanalysen als JSON-LD 1.1 (#499 Abschnitt G, ADR 0024): eingebettetes, versioniertes
 * {@code @context} (keine externen Downloads zur Interpretation), eigenes Vokabular {@value #VOCABULARY} für
 * Space-Harvester-Begriffe und W3C PROV-O für Herkunft. Eine Aussage ist ein eigener Knoten mit Rollen-Kanten zu
 * Erwähnungen und qualifizierten Beteiligungen (Rolle, UD-Relation, Präposition, Herkunft, Sicherheit); Erwähnungen
 * bleiben je Textstelle eigenständig. Es gibt keine {@code owl:sameAs}-Behauptungen: {@code sh:lexicalKey} ist nur die
 * normalisierte Grundform für lexikalische Kandidaten, keine Identität. Jede Aussage trägt drei getrennte Angaben:
 * {@code sh:extractionQuality} (maschinelle Ableitung, mit {@code sh:qualityReason}), {@code sh:basis} je Beteiligung
 * (Herkunft) und {@code sh:verificationStatus} (fachliche Prüfung: {@value #UNVERIFIED}, solange kein Mensch sie
 * bestätigt oder verworfen hat). Exportiert werden nur die Aussagen der Stufen aus {@link Header#qualities()},
 * standardmäßig nur plausible; Erwähnungen und nominale Bezüge bleiben vollständig.
 *
 * <p>Deterministisch: Seiten nach Quelle und Seite, Knoten nach Satz und Position, Mengen sortiert; nur der Kopf nennt
 * den Zeitpunkt. Streamend geschrieben, ohne den Graphen im Speicher aufzubauen.
 */
public final class KnowledgeGraphJsonLd {

    /** Medientyp der Datei. */
    public static final String MEDIA_TYPE = "application/ld+json";
    /** Dateiendung. */
    public static final String EXTENSION = ".jsonld";
    /** Namensraum des Vokabulars (docs/vocab/knowledge-graph-v1.md). */
    public static final String VOCABULARY = "https://aresstack.github.io/space-harvester/vocab/kg/v1#";
    /** Version des Exportformats. */
    public static final String FORMAT = "space-harvester/knowledge-graph-jsonld/v1";
    /** Prefix aller Knoten-IRIs. */
    public static final String IRI = "urn:space-harvester:kg:";
    /** Prüfstatus einer fachlich (noch) nicht geprüften Aussage. */
    public static final String UNVERIFIED = "UNVERIFIED_PARSER_OUTPUT";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private KnowledgeGraphJsonLd() {
    }

    /**
     * Kopf des Exports.
     *
     * @param scope         Umfang für Menschen, etwa {@code space:wiki/DEV}
     * @param includesStale ob veraltete Analysen enthalten sind
     * @param complete      ob alle Seiten des Umfangs enthalten sind, die der Aufrufer lesen darf
     * @param qualities     exportierte Qualitätsstufen der Aussagen ({@code sh:exportedQuality}); Erwähnungen und
     *                      nominale Bezüge bleiben immer vollständig
     */
    public record Header(String exportId, String scope, Instant generatedAt, boolean includesStale,
                         boolean complete, Set<ParsedStatements.Quality> qualities) {

        public Header {
            Objects.requireNonNull(exportId, "exportId");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(generatedAt, "generatedAt");
            qualities = Set.copyOf(qualities);
        }

        /** Kopf mit den Qualitätsstufen der Standardansicht: nur plausible Aussagen. */
        public Header(String exportId, String scope, Instant generatedAt, boolean includesStale, boolean complete) {
            this(exportId, scope, generatedAt, includesStale, complete, PageGraph.DEFAULT_QUALITIES);
        }
    }

    /** Eine Seite des Exports mit ihrem Stand. */
    public record Entry(PageStatementAnalysis analysis, PageStatements.State state, String reason) {

        public Entry {
            Objects.requireNonNull(analysis, "analysis");
            Objects.requireNonNull(state, "state");
        }
    }

    /** Schreibt den Export; schließt {@code out} nicht. */
    public static void write(OutputStream out, Header header, List<Entry> entries) {
        List<Entry> sorted = entries.stream().sorted(Comparator
                .comparing((Entry entry) -> entry.analysis().key().sourceId())
                .thenComparing(entry -> entry.analysis().key().pageId())
                .thenComparing(entry -> entry.analysis().document().value())).toList();
        try (JsonGenerator json = JSON.createGenerator(out, JsonEncoding.UTF8)) {
            json.writeStartObject();
            context(json);
            json.writeName("@graph");
            json.writeStartArray();
            exportNode(json, header, sorted);
            for (Entry entry : sorted) {
                page(json, entry, header.qualities());
            }
            json.writeEndArray();
            json.writeEndObject();
        }
    }

    // ---- IRIs

    /** IRI der Seite (alle Fassungen). */
    public static String page(PageKey key) {
        return IRI + "page:" + encode(key.sourceId()) + ":" + encode(key.pageId());
    }

    /**
     * IRI des analysierten Dokuments (ADR 0026, Punkt 10): für die Seite selbst {@link #page(PageKey)}, für einen
     * Anhang oder eine enthaltene Datei die Seite plus {@code DocumentKey#fileName()}.
     */
    public static String document(PageStatementAnalysis analysis) {
        return analysis.ofDocument() ? IRI + "document:" + encode(analysis.key().sourceId()) + ":"
                + encode(analysis.key().pageId()) + documentPart(analysis) : page(analysis.key());
    }

    /** IRI der analysierten Fassung (Texthash). */
    public static String revision(PageStatementAnalysis analysis) {
        return IRI + "revision:" + encode(analysis.key().sourceId()) + ":" + encode(analysis.key().pageId())
                + documentPart(analysis) + ":" + version(analysis);
    }

    /**
     * IRI einer Erwähnung oder Aussage aus ihrer lokalen ID ({@code quelle/seite@fassung#s1.t2}): Fassung plus lokaler
     * Teil, kollisionsfrei über Quellen, Seiten und Fassungen.
     */
    public static String node(PageStatementAnalysis analysis, String localId) {
        int hash = localId.lastIndexOf('#');
        String local = hash < 0 ? localId : localId.substring(hash + 1);
        return IRI + "node:" + encode(analysis.key().sourceId()) + ":" + encode(analysis.key().pageId())
                + documentPart(analysis) + ":" + version(analysis) + ":" + encode(local);
    }

    private static String run(PageStatementAnalysis analysis) {
        return IRI + "run:" + encode(analysis.key().sourceId()) + ":" + encode(analysis.key().pageId())
                + documentPart(analysis) + ":" + version(analysis) + ":" + encode(analysis.analyzerId());
    }

    /** Für Anhang und enthaltene Datei {@code :<DocumentKey#fileName()>} (nur {@code [0-9a-z-]}), für Seiten leer. */
    private static String documentPart(PageStatementAnalysis analysis) {
        return analysis.ofDocument() ? ":" + analysis.document().fileName() : "";
    }

    private static String version(PageStatementAnalysis analysis) {
        String sha = analysis.inputSha256();
        int colon = sha.indexOf(':');
        String hex = colon < 0 ? sha : sha.substring(colon + 1);
        return hex.substring(0, Math.min(12, hex.length()));
    }

    /** Prozentkodierung aller Zeichen außer den nicht reservierten (RFC 3986). */
    static String encode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '.'
                    || c == '_' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(String.format(Locale.ROOT, "%02X", c));
            }
        }
        return out.toString();
    }

    // ---- Kontext

    private static void context(JsonGenerator json) {
        json.writeName("@context");
        json.writeStartObject();
        json.writeNumberProperty("@version", 1.1);
        json.writeStringProperty("sh", VOCABULARY);
        json.writeStringProperty("prov", "http://www.w3.org/ns/prov#");
        json.writeStringProperty("rdfs", "http://www.w3.org/2000/01/rdf-schema#");
        json.writeStringProperty("xsd", "http://www.w3.org/2001/XMLSchema#");
        for (String type : List.of("GraphExport", "Page", "Document", "PageRevision", "AnalysisRun", "Mention",
                "Statement",
                "Participation", "NominalAttachment", "Evidence")) {
            json.writeStringProperty(type, "sh:" + type);
        }
        for (String term : List.of("format", "scope", "includesStale", "complete", "pageCount", "sourceId",
                "pageId", "attachmentId", "partId", "spaceKey", "title", "revision", "inputSha256", "status",
                "staleReason", "analyzer",
                "analyzerVersion", "text", "lemma", "headForm", "mentionKind", "unresolved", "lexicalKey", "start",
                "end", "sentence", "paragraph", "predicate", "predicateLemma", "voice", "clause", "core",
                "verificationStatus",
                "role", "relation", "marker", "basis", "priority", "certain", "attachmentKind")) {
            json.writeStringProperty(term, "sh:" + term);
        }
        set(json, "grammaticalCase", "sh:grammaticalCase");
        set(json, "finding", "sh:finding");
        set(json, "extractionQuality", "sh:extractionQuality");
        set(json, "qualityReason", "sh:qualityReason");
        set(json, "exportedQuality", "sh:exportedQuality");
        for (String link : List.of("revisionOf", "documentOf", "page", "mention", "participant", "head",
                "dependent", "evidence", "inRevision", "actor", "affectedObject", "recipient", "prepositionalObject",
                "instrument", "time", "circumstance")) {
            reference(json, link, "sh:" + link);
        }
        json.writeName("participation");
        json.writeStartObject();
        json.writeStringProperty("@id", "sh:participation");
        json.writeStringProperty("@container", "@set");
        json.writeEndObject();
        reference(json, "wasDerivedFrom", "prov:wasDerivedFrom");
        reference(json, "wasGeneratedBy", "prov:wasGeneratedBy");
        reference(json, "used", "prov:used");
        dateTime(json, "generatedAtTime", "prov:generatedAtTime");
        dateTime(json, "endedAtTime", "prov:endedAtTime");
        json.writeStringProperty("label", "rdfs:label");
        json.writeEndObject();
    }

    private static void reference(JsonGenerator json, String term, String iri) {
        json.writeName(term);
        json.writeStartObject();
        json.writeStringProperty("@id", iri);
        json.writeStringProperty("@type", "@id");
        json.writeEndObject();
    }

    private static void set(JsonGenerator json, String term, String iri) {
        json.writeName(term);
        json.writeStartObject();
        json.writeStringProperty("@id", iri);
        json.writeStringProperty("@container", "@set");
        json.writeEndObject();
    }

    private static void dateTime(JsonGenerator json, String term, String iri) {
        json.writeName(term);
        json.writeStartObject();
        json.writeStringProperty("@id", iri);
        json.writeStringProperty("@type", "xsd:dateTime");
        json.writeEndObject();
    }

    // ---- Knoten

    private static void exportNode(JsonGenerator json, Header header, List<Entry> entries) {
        json.writeStartObject();
        json.writeStringProperty("@id", IRI + "export:" + encode(header.exportId()));
        json.writeStringProperty("@type", "GraphExport");
        json.writeStringProperty("format", FORMAT);
        json.writeStringProperty("scope", header.scope());
        json.writeStringProperty("generatedAtTime", header.generatedAt().toString());
        json.writeBooleanProperty("includesStale", header.includesStale());
        json.writeBooleanProperty("complete", header.complete());
        json.writeNumberProperty("pageCount", entries.size());
        json.writeName("exportedQuality");
        json.writeStartArray();
        for (ParsedStatements.Quality quality : new TreeSet<>(header.qualities())) {
            json.writeString(quality.name());
        }
        json.writeEndArray();
        json.writeEndObject();
    }

    private static void page(JsonGenerator json, Entry entry, Set<ParsedStatements.Quality> qualities) {
        PageStatementAnalysis analysis = entry.analysis();
        String page = page(analysis.key());
        String revision = revision(analysis);
        String run = run(analysis);

        json.writeStartObject();
        json.writeStringProperty("@id", page);
        json.writeStringProperty("@type", "Page");
        json.writeStringProperty("sourceId", analysis.key().sourceId());
        json.writeStringProperty("pageId", analysis.key().pageId());
        optional(json, "spaceKey", analysis.spaceKey());
        if (!analysis.ofDocument()) {
            optional(json, "title", analysis.title());
        }
        json.writeEndObject();

        if (analysis.ofDocument()) {
            // Anhang oder enthaltene Datei (ADR 0026): eigene Ressource unter der Seite, Kennungen getrennt.
            json.writeStartObject();
            json.writeStringProperty("@id", document(analysis));
            json.writeStringProperty("@type", "Document");
            json.writeStringProperty("documentOf", page);
            optional(json, "attachmentId", analysis.document().attachmentId().orElse(null));
            optional(json, "partId", analysis.document().part().map(part -> part.value()).orElse(null));
            optional(json, "title", analysis.title());
            json.writeEndObject();
        }

        json.writeStartObject();
        json.writeStringProperty("@id", revision);
        json.writeStringProperty("@type", "PageRevision");
        json.writeStringProperty("revisionOf", document(analysis));
        optional(json, "revision", analysis.revision());
        json.writeStringProperty("inputSha256", analysis.inputSha256());
        json.writeStringProperty("status", entry.state().name());
        optional(json, "staleReason", entry.reason());
        json.writeEndObject();

        json.writeStartObject();
        json.writeStringProperty("@id", run);
        json.writeStringProperty("@type", "AnalysisRun");
        json.writeStringProperty("analyzer", analysis.analyzerId());
        json.writeStringProperty("analyzerVersion", analysis.analyzerVersion());
        json.writeStringProperty("used", revision);
        json.writeStringProperty("endedAtTime", analysis.analyzedAt().toString());
        json.writeEndObject();

        ParsedStatements result = analysis.result();
        List<ParsedStatements.Mention> mentions = result.mentions().stream()
                .sorted(Comparator.comparingInt(ParsedStatements.Mention::start)
                        .thenComparingInt(ParsedStatements.Mention::end)
                        .thenComparing(ParsedStatements.Mention::id)).toList();
        for (ParsedStatements.Mention mention : mentions) {
            json.writeStartObject();
            json.writeStringProperty("@id", node(analysis, mention.id()));
            json.writeStringProperty("@type", "Mention");
            json.writeStringProperty("text", mention.text());
            optional(json, "lemma", mention.lemma());
            optional(json, "headForm", mention.headForm());
            json.writeStringProperty("mentionKind", mention.kind().name());
            json.writeBooleanProperty("unresolved", mention.unresolved());
            if (!mention.unresolved() && mention.lemma() != null && !TermText.normalize(mention.lemma()).isEmpty()) {
                json.writeStringProperty("lexicalKey", TermText.normalize(mention.lemma()));
            }
            if (!mention.cases().isEmpty()) {
                json.writeName("grammaticalCase");
                json.writeStartArray();
                for (String grammaticalCase : new TreeSet<>(mention.cases())) {
                    json.writeString(grammaticalCase);
                }
                json.writeEndArray();
            }
            json.writeNumberProperty("sentence", mention.sentence());
            json.writeNumberProperty("start", mention.start());
            json.writeNumberProperty("end", mention.end());
            json.writeStringProperty("inRevision", revision);
            json.writeStringProperty("wasDerivedFrom", revision);
            json.writeEndObject();
        }

        List<ParsedStatements.Statement> statements = result.statements().stream()
                .filter(statement -> qualities.contains(statement.quality()))
                .sorted(Comparator.comparingInt(ParsedStatements.Statement::start)
                        .thenComparing(ParsedStatements.Statement::id)).toList();
        for (ParsedStatements.Statement statement : statements) {
            String id = node(analysis, statement.id());
            json.writeStartObject();
            json.writeStringProperty("@id", id);
            json.writeStringProperty("@type", "Statement");
            json.writeStringProperty("predicate", statement.predicateText());
            optional(json, "predicateLemma", statement.predicateLemma());
            json.writeStringProperty("voice", statement.voice().name());
            json.writeStringProperty("clause", statement.clause().name());
            json.writeBooleanProperty("core", statement.core());
            json.writeStringProperty("verificationStatus", statement.review() == ParsedStatements.Review.UNREVIEWED
                    ? UNVERIFIED : statement.review().name());
            json.writeStringProperty("extractionQuality", statement.quality().name());
            if (!statement.qualityReasons().isEmpty()) {
                json.writeName("qualityReason");
                json.writeStartArray();
                for (String reason : statement.qualityReasons()) {
                    json.writeString(reason);
                }
                json.writeEndArray();
            }
            if (statement.paragraph() >= 0) {
                json.writeNumberProperty("paragraph", statement.paragraph());
            }
            if (!statement.findings().isEmpty()) {
                json.writeName("finding");
                json.writeStartArray();
                for (String finding : new TreeSet<>(statement.findings())) {
                    json.writeString(finding);
                }
                json.writeEndArray();
            }
            json.writeNumberProperty("sentence", statement.sentence());
            json.writeNumberProperty("start", statement.start());
            json.writeNumberProperty("end", statement.end());
            // Direkte Rollen-Kanten für einfache RDF-Abfragen; Qualifikatoren in sh:participation.
            for (ParsedStatements.Role role : ParsedStatements.Role.values()) {
                List<String> targets = statement.arguments().stream().filter(argument -> argument.role() == role)
                        .map(argument -> node(analysis, argument.mention())).sorted().distinct().toList();
                if (targets.isEmpty()) {
                    continue;
                }
                json.writeName(roleTerm(role));
                if (targets.size() == 1) {
                    json.writeString(targets.getFirst());
                } else {
                    json.writeStartArray();
                    for (String target : targets) {
                        json.writeString(target);
                    }
                    json.writeEndArray();
                }
            }
            json.writeName("participation");
            json.writeStartArray();
            int index = 0;
            for (ParsedStatements.Argument argument : statement.arguments()) {
                json.writeStartObject();
                json.writeStringProperty("@id", id + ":p" + index++);
                json.writeStringProperty("@type", "Participation");
                json.writeStringProperty("role", argument.role().name());
                json.writeStringProperty("participant", node(analysis, argument.mention()));
                optional(json, "relation", argument.relation());
                optional(json, "marker", argument.marker());
                json.writeStringProperty("priority", argument.priority().name());
                json.writeStringProperty("basis", argument.basis().name());
                json.writeBooleanProperty("certain", argument.certain());
                json.writeEndObject();
            }
            json.writeEndArray();
            json.writeName("evidence");
            json.writeStartObject();
            json.writeStringProperty("@id", id + ":evidence");
            json.writeStringProperty("@type", "Evidence");
            json.writeStringProperty("inRevision", revision);
            json.writeNumberProperty("sentence", statement.sentence());
            result.sentences().stream().filter(sentence -> sentence.index() == statement.sentence()).findFirst()
                    .ifPresent(sentence -> {
                        json.writeNumberProperty("start", sentence.start());
                        json.writeNumberProperty("end", sentence.end());
                    });
            json.writeEndObject();
            json.writeStringProperty("inRevision", revision);
            json.writeStringProperty("wasDerivedFrom", revision);
            json.writeStringProperty("wasGeneratedBy", run);
            json.writeEndObject();
        }

        List<ParsedStatements.Attachment> attachments = result.attachments().stream()
                .sorted(Comparator.comparing(ParsedStatements.Attachment::id)).toList();
        for (ParsedStatements.Attachment attachment : attachments) {
            json.writeStartObject();
            json.writeStringProperty("@id", node(analysis, attachment.id()));
            json.writeStringProperty("@type", "NominalAttachment");
            json.writeStringProperty("head", node(analysis, attachment.head()));
            json.writeStringProperty("dependent", node(analysis, attachment.dependent()));
            json.writeStringProperty("attachmentKind", attachment.kind().name());
            optional(json, "relation", attachment.relation());
            optional(json, "marker", attachment.marker());
            json.writeStringProperty("basis", attachment.basis().name());
            json.writeStringProperty("inRevision", revision);
            json.writeStringProperty("wasDerivedFrom", revision);
            json.writeEndObject();
        }
    }

    static String roleTerm(ParsedStatements.Role role) {
        return switch (role) {
            case ACTOR -> "actor";
            case AFFECTED_OBJECT -> "affectedObject";
            case RECIPIENT -> "recipient";
            case PREPOSITIONAL_OBJECT -> "prepositionalObject";
            case INSTRUMENT -> "instrument";
            case TIME -> "time";
            case CIRCUMSTANCE -> "circumstance";
        };
    }

    private static void optional(JsonGenerator json, String name, String value) {
        if (value != null) {
            json.writeStringProperty(name, value);
        }
    }
}
