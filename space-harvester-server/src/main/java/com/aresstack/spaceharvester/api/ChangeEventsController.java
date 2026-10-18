package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.sync.ChangeEvents;
import java.io.IOException;
import java.io.InputStream;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Empfangsstelle für Webhooks der Quelle (ADR 0014, optional, Standard aus). Liegt außerhalb von {@code /api}, weil
 * Confluence keinen Client-Token schickt: Statt der Anmeldung prüft der Server die Signatur ({@code X-Hub-Signature},
 * HMAC-SHA256 mit {@code space-harvester.webhooks.secrets.<Quelle>}). Ein Aufruf markiert nur den Katalog und lädt
 * keine Inhalte; die Antwort enthält keine Daten.
 */
@RestController
@Tag(name = "Webhooks", description = "Änderungsereignisse der Quelle, signiert statt angemeldet")
public class ChangeEventsController {

    /** Pfad der Empfangsstelle; {@code {sourceId}} ist die Quellen-ID. */
    public static final String PATH = "/webhooks/v1/sources/{sourceId}/events";

    private final ChangeEvents events;

    public ChangeEventsController(ChangeEvents events) {
        this.events = events;
    }

    @PostMapping(path = PATH)
    @Operation(summary = "Webhook-Aufruf der Quelle",
            description = "Confluence Data Center ruft hier mit dem Ereignis im Kopf X-Event-Key auf. Gemeldete Seiten "
                    + "werden einzeln nachgefragt und nur im Katalog als veraltet, nicht geladen, gelöscht oder "
                    + "gesperrt markiert.")
    @ApiResponse(responseCode = "202", description = "Signatur gültig, Ereignisse angenommen")
    @ApiResponse(responseCode = "400", description = "Signatur gültig, aber keine lesbare Meldung")
    @ApiResponse(responseCode = "401", description = "Signatur fehlt oder passt nicht")
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle, Ereignisse aus oder kein Geheimnis")
    @ApiResponse(responseCode = "413", description = "Rumpf größer als 1 MiB")
    public ResponseEntity<Void> receive(@PathVariable String sourceId, InputStream body,
                                        @RequestHeader(name = "X-Hub-Signature", required = false) String signature,
                                        @RequestHeader(name = "X-Event-Key", required = false) String eventKey,
                                        @RequestHeader(name = "Content-Length", required = false) Long length)
            throws IOException {
        // Abgelehnte Aufrufe lesen den Rumpf nicht; angenommene höchstens bis zur Grenze.
        ChangeEvents.Receipt open = events.receiving(sourceId);
        if (open != ChangeEvents.Receipt.ACCEPTED) {
            return ResponseEntity.notFound().build();
        }
        if (length != null && length > ChangeEvents.MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        byte[] read = body.readNBytes(ChangeEvents.MAX_BODY_BYTES + 1);
        if (read.length > ChangeEvents.MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        ChangeEvents.Receipt receipt = events.receive(sourceId, read, signature, eventKey);
        HttpStatus status = switch (receipt) {
            case ACCEPTED -> HttpStatus.ACCEPTED;
            case UNREADABLE -> HttpStatus.BAD_REQUEST;
            case BAD_SIGNATURE -> HttpStatus.UNAUTHORIZED;
            case DISABLED, UNKNOWN_SOURCE -> HttpStatus.NOT_FOUND;
        };
        return ResponseEntity.status(status).build();
    }
}
