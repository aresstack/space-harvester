package com.aresstack.spaceharvester.output.convert;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ExportConverter;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import java.io.OutputStream;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plain JSON (#30): ein JSON-Array mit einem Objekt je Seite, linear in Baumreihenfolge. Jedes Objekt trägt
 * {@code header} (Kopfdaten wie in {@code pages.ndjson}), {@code depth}, {@code revision}, {@code rawSha256} und
 * {@code content} (die native Payload inhaltlich gleich). Die Hierarchie steht nur in {@code tree.json}; das Array
 * bettet keinen Baum ein. Für große Spaces und Streaming bleibt {@code pages.ndjson} das Format.
 */
public final class JsonArrayConverter implements ExportConverter {

    public static final String FORMAT = "json";
    public static final String VERSION = "json-array/1";

    private final JsonMapper json = JsonMapper.builder().build();

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
        return "pages.json";
    }

    @Override
    public void convert(ConversionInput input, OutputStream out) {
        try (JsonGenerator generator = json.createGenerator(out)) {
            generator.writeStartArray();
            input.forEachPage(page -> {
                NativePayload payload = page.snapshot().payload();
                generator.writeStartObject();
                PageJson.writeHeader(generator, page.header());
                generator.writeNumberProperty("depth", page.depth());
                generator.writeStringProperty("revision", page.snapshot().revision());
                generator.writeStringProperty("rawSha256", payload.rawSha256());
                generator.writeName("content");
                PageJson.writeContent(generator, payload.mediaType(), payload.bytes());
                generator.writeEndObject();
            });
            generator.writeEndArray();
        }
    }
}
