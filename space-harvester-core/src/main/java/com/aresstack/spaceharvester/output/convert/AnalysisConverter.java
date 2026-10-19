package com.aresstack.spaceharvester.output.convert;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ExportConverter;
import com.aresstack.spaceharvester.application.convert.PageAnalyses;
import com.aresstack.spaceharvester.application.convert.PageAnalyses.PageAnalysis;
import com.aresstack.spaceharvester.application.convert.PageAnalyses.Section;
import com.aresstack.spaceharvester.application.facts.MetadataFact;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.io.OutputStream;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Analyse-Export (#359): die Analysedaten der exportierten Seiten als eigenes JSON, getrennt von den Originaldaten
 * und erst auf Abruf erzeugt.
 *
 * <pre>
 * { "format": "space-harvester/analysis-export/v1", "sourceId", "spaceKey", "rootPageId",
 *   "processors": [{ "id", "version" }],
 *   "pages": [{ "sourceId", "spaceKey", "pageId", "title", "url", "parentPageId", "depth", "revision",
 *               "rawSha256", "inputSha256",
 *               "enrichments": { "&lt;Prozessor&gt;": { "state", "processorVersion", "currentProcessorVersion",
 *                                "inputSha256", "revision", "computedAt", "warnings", "errorCode", "payload" } },
 *               "facts": [{ "id", "type", "role", "origin", "review", "processorId", "processorVersion",
 *                           "inputSha256", "revision", "payload", "arguments", "anchor" }] }] }
 * </pre>
 *
 * Seiten linear in Baumreihenfolge wie {@link JsonArrayConverter}; die Hierarchie steht nur in {@code parentPageId}.
 * Zugeordnet wird über {@code sourceId}, {@code pageId} und die exportierte {@code revision} mit {@code rawSha256};
 * {@code inputSha256} ist der Eingabe-Hash dieser Version, gegen den der {@code state} jedes Abschnitts bestimmt ist
 * ({@code CURRENT}, {@code STALE}, {@code FAILED}, {@code MISSING}). Je Prozessor ein Abschnitt; die Payload kommt
 * unverändert aus der Ablage. Nichts wird nachgerechnet. Fakten mit {@code origin} {@code AUTOMATIC} oder
 * {@code MANUAL} und Prüfstatus {@code review} ({@code UNREVIEWED}, {@code CONFIRMED}, {@code REJECTED}).
 */
public final class AnalysisConverter implements ExportConverter {

    public static final String FORMAT = "analysis";
    public static final String VERSION = "analysis/1";
    public static final String SCHEMA = "space-harvester/analysis-export/v1";

    private final JsonMapper json = JsonMapper.builder().build();
    private final PageAnalyses analyses;

    public AnalysisConverter(PageAnalyses analyses) {
        this.analyses = Objects.requireNonNull(analyses, "analyses");
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public String mediaType() {
        return "application/json";
    }

    @Override
    public String fileName() {
        return "analysis.json";
    }

    @Override
    public void convert(ConversionInput input, OutputStream out) {
        try (JsonGenerator generator = json.createGenerator(out)) {
            generator.writeStartObject();
            generator.writeStringProperty("format", SCHEMA);
            generator.writeStringProperty("sourceId", input.sourceId());
            generator.writeStringProperty("spaceKey", input.spaceKey());
            generator.writeStringProperty("rootPageId", input.rootPageId());
            generator.writeName("processors");
            generator.writeStartArray();
            for (PageAnalyses.Processor processor : analyses.processors()) {
                generator.writeStartObject();
                generator.writeStringProperty("id", processor.id());
                generator.writeStringProperty("version", processor.version());
                generator.writeEndObject();
            }
            generator.writeEndArray();
            generator.writeName("pages");
            generator.writeStartArray();
            input.forEachPage(page -> {
                PageMetadata metadata = page.header().metadata();
                PageAnalysis analysis = analyses.of(page.snapshot());
                generator.writeStartObject();
                generator.writeStringProperty("sourceId", page.header().key().sourceId());
                generator.writeStringProperty("spaceKey", metadata.partitionKey());
                generator.writeStringProperty("pageId", page.pageId());
                generator.writeStringProperty("title", metadata.title());
                generator.writeStringProperty("url", metadata.url() == null ? null : metadata.url().toString());
                generator.writeStringProperty("parentPageId", metadata.parentPageId());
                generator.writeNumberProperty("depth", page.depth());
                generator.writeStringProperty("revision", page.snapshot().revision());
                generator.writeStringProperty("rawSha256", page.snapshot().payload().rawSha256());
                generator.writeStringProperty("inputSha256", analysis.inputSha256());
                generator.writeName("enrichments");
                generator.writeStartObject();
                for (Section section : analysis.sections()) {
                    generator.writeName(section.processorId());
                    section(generator, section);
                }
                generator.writeEndObject();
                generator.writeName("facts");
                generator.writeStartArray();
                for (MetadataFact fact : analysis.facts()) {
                    fact(generator, fact);
                }
                generator.writeEndArray();
                generator.writeEndObject();
            });
            generator.writeEndArray();
            generator.writeEndObject();
        }
    }

    private void section(JsonGenerator generator, Section section) {
        generator.writeStartObject();
        generator.writeStringProperty("state", section.state().name());
        generator.writeStringProperty("processorVersion", section.processorVersion());
        generator.writeStringProperty("currentProcessorVersion", section.currentVersion());
        generator.writeStringProperty("inputSha256", section.inputSha256());
        generator.writeStringProperty("revision", section.revision());
        generator.writeStringProperty("computedAt", PageJson.text(section.computedAt()));
        generator.writeName("warnings");
        generator.writeStartArray();
        for (String warning : section.warnings()) {
            generator.writeString(warning);
        }
        generator.writeEndArray();
        generator.writeStringProperty("errorCode", section.errorCode());
        generator.writeName("payload");
        raw(generator, section.payloadJson());
        generator.writeEndObject();
    }

    private void fact(JsonGenerator generator, MetadataFact fact) {
        generator.writeStartObject();
        generator.writeStringProperty("id", fact.id());
        generator.writeStringProperty("type", fact.type());
        generator.writeStringProperty("role", fact.role());
        generator.writeStringProperty("origin", fact.origin().name());
        generator.writeStringProperty("review", fact.review().name());
        generator.writeStringProperty("processorId", fact.provenance().processorId());
        generator.writeStringProperty("processorVersion", fact.provenance().processorVersion());
        generator.writeStringProperty("inputSha256", fact.provenance().inputSha256());
        generator.writeStringProperty("revision", fact.provenance().revision());
        generator.writeName("payload");
        raw(generator, fact.payloadJson());
        generator.writeName("arguments");
        generator.writeStartArray();
        for (MetadataFact.Argument argument : fact.arguments()) {
            generator.writeStartObject();
            generator.writeStringProperty("role", argument.role());
            generator.writeNumberProperty("ordinal", argument.ordinal());
            generator.writeStringProperty("value", argument.value());
            generator.writeEndObject();
        }
        generator.writeEndArray();
        generator.writeName("anchor");
        if (fact.anchor() == null) {
            generator.writeNull();
        } else {
            generator.writeStartObject();
            generator.writeStringProperty("section", fact.anchor().section());
            if (fact.anchor().charStart() == null) {
                generator.writeNullProperty("charStart");
            } else {
                generator.writeNumberProperty("charStart", fact.anchor().charStart());
            }
            if (fact.anchor().charEnd() == null) {
                generator.writeNullProperty("charEnd");
            } else {
                generator.writeNumberProperty("charEnd", fact.anchor().charEnd());
            }
            generator.writeEndObject();
        }
        generator.writeEndObject();
    }

    /** Schreibt gespeichertes JSON als Baum, damit eine beschädigte Ablage nie ungültiges JSON erzeugt. */
    private void raw(JsonGenerator generator, String payloadJson) {
        if (payloadJson == null) {
            generator.writeNull();
            return;
        }
        JsonNode node;
        try {
            node = json.readTree(payloadJson);
        } catch (JacksonException e) {
            generator.writeNull();
            return;
        }
        generator.writeTree(node);
    }
}
