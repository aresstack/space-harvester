package com.aresstack.spaceharvester.output.export;

import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.export.ImportedAssets;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.output.convert.ExportFileJson;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ImportedAssets} in der Ablage der Anreicherungen, unter der vorbehaltenen Prozessor-ID
 * {@link Enrichments#IMPORTED_ASSETS}, wie die Lese-Ansicht: je Seite höchstens ein Eintrag, mit der Seite entfernt,
 * nie in der Liste der Anreicherungen. Das Manifest steht im Format von {@code assets.json}.
 */
public final class EnrichmentImportedAssets implements ImportedAssets {

    /** Aufbau des abgelegten JSON-Objekts. */
    static final String SCHEMA = "imported-assets/v1";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EnrichmentStore store;
    private final Clock clock;

    public EnrichmentImportedAssets(EnrichmentStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void save(PageKey page, String version, ExportAssetManifest assets) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(assets, "assets");
        StringWriter out = new StringWriter();
        try (JsonGenerator generator = JSON.createGenerator(out)) {
            generator.writeStartObject();
            generator.writeStringProperty("schema", SCHEMA);
            generator.writeName("assets");
            ExportFileJson.writeAssets(generator, assets);
            generator.writeEndObject();
        }
        String payload = out.toString();
        store.save(new Enrichment(page, Enrichments.IMPORTED_ASSETS, SCHEMA, sha256(payload), version,
                Enrichment.Status.OK, payload, List.of(), null, clock.instant()));
        store.flush();
    }

    @Override
    public Optional<ExportAssetManifest> find(PageKey page, String version) {
        for (Enrichment found : store.find(page)) {
            if (!Enrichments.IMPORTED_ASSETS.equals(found.processorId()) || found.status() != Enrichment.Status.OK
                    || !Objects.equals(found.revision(), version)) {
                continue;
            }
            try {
                JsonNode payload = JSON.readTree(found.payloadJson());
                if (!SCHEMA.equals(payload.path("schema").asString())) {
                    return Optional.empty();
                }
                return ExportFileJson.readAssets(payload.path("assets"));
            } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
