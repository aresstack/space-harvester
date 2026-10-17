package com.aresstack.spaceharvester.output.convert;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.time.Instant;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.json.JsonMapper;

/** Gemeinsame JSON-Darstellung einer Seite in Exportdateien: Kopfdaten und eingebettete native Payload. */
public final class PageJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PageJson() {
    }

    /** Schreibt die Eigenschaft {@code header} mit den aktuellen Kopfdaten der Seite. */
    public static void writeHeader(JsonGenerator generator, PageHeader header) {
        PageMetadata metadata = header.metadata();
        generator.writeName("header");
        generator.writeStartObject();
        generator.writeStringProperty("sourceId", header.key().sourceId());
        generator.writeStringProperty("pageId", header.key().pageId());
        generator.writeStringProperty("spaceKey", metadata.partitionKey());
        generator.writeStringProperty("title", metadata.title());
        generator.writeStringProperty("url", metadata.url() == null ? null : metadata.url().toString());
        generator.writeStringProperty("parentPageId", metadata.parentPageId());
        generator.writeStringProperty("version", metadata.version());
        generator.writeStringProperty("modifiedAt", text(metadata.modifiedAt()));
        generator.writeName("attributes");
        generator.writeStartObject();
        for (Map.Entry<String, String> attribute : metadata.attributes().entrySet()) {
            generator.writeStringProperty(attribute.getKey(), attribute.getValue());
        }
        generator.writeEndObject();
        generator.writeStringProperty("metadataHash", header.metadataHash());
        generator.writeStringProperty("attributesHash", header.attributesHash());
        generator.writeStringProperty("observedAt", text(header.observedAt()));
        generator.writeEndObject();
    }

    /**
     * Schreibt die Payload als JSON-Wert, inhaltlich gleich mit der nativen Antwort; {@code null}, wenn sie kein
     * vollständig lesbares JSON ist.
     *
     * @return ob die Payload eingebettet wurde
     */
    public static boolean writeContent(JsonGenerator generator, String mediaType, byte[] payload) {
        if (!isJson(mediaType, payload)) {
            generator.writeNull();
            return false;
        }
        try (JsonParser parser = JSON.createParser(payload)) {
            parser.nextToken();
            generator.copyCurrentStructureExact(parser);
        }
        return true;
    }

    /** Vollständig lesbares JSON mit genau einem Wert. */
    public static boolean isJson(String mediaType, byte[] payload) {
        if (!"application/json".equals(mediaType)) {
            return false;
        }
        try (JsonParser parser = JSON.createParser(payload)) {
            if (parser.nextToken() == null) {
                return false;
            }
            parser.skipChildren();
            return parser.nextToken() == null;
        } catch (JacksonException e) {
            return false;
        }
    }

    public static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
