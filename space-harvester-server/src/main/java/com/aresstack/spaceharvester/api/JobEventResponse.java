package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.jobs.JobEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Ein Diagnoseereignis eines Auftrags für die API; alle Texte sind maskiert (docs/DIAGNOSTICS.md).
 */
@Schema(description = "Diagnoseereignis eines Auftrags; Texte maskiert, ohne Zugangsdaten, Tokens und URL-Pfade")
public record JobEventResponse(String jobId, long sequence, Instant at, String type, String sourceId,
                               String spaceKey, String severity, String category, String component, String phase,
                               String code, String message, Integer httpStatus, String proxyRoute, String tlsFinding,
                               String detail) {

    static JobEventResponse of(JobEvent event) {
        return new JobEventResponse(event.jobId(), event.sequence(), event.at(), lower(event.type().name()),
                event.sourceId(), event.partitionKey(), lower(event.severity().name()),
                lower(event.category().name()), event.component(), event.phase(), event.code(), event.message(),
                event.httpStatus(), event.proxyRoute(), event.tlsFinding(), event.detail());
    }

    static List<JobEventResponse> of(List<JobEvent> events) {
        return events.stream().map(JobEventResponse::of).toList();
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
