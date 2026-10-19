package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.signin.SignInRequest;
import com.aresstack.spaceharvester.application.signin.SignInRequests;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Zusätzliche Anmeldung während eigener Aufträge (ADR 0018): verlangt die Quelle beim Beschaffen von Bildern einen
 * Einmalcode, wartet der Auftrag, bis ein Client ihn hier sendet. Sichtbar nur Anforderungen, auf die ein eigener
 * Auftrag wartet; ohne {@code space-harvester.export.sign-in-wait} wartet nie ein Auftrag und die Liste bleibt leer.
 * Der Code geht nur an die Quelle; er steht in keiner Antwort und keinem Log.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/sign-ins", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Anmeldung", description = "Einmalcode (OTP), den eine Quelle für Dateien verlangt")
public class SignInController {

    private final SignInRequests signIns;
    private final Jobs jobs;

    public SignInController(SignInRequests signIns, Jobs jobs) {
        this.signIns = signIns;
        this.jobs = jobs;
    }

    @GetMapping
    @Operation(summary = "Offene Anmeldungen",
            description = "Anforderungen, auf die ein eigener Auftrag wartet („Wartet auf Anmeldung“), mit den Texten "
                    + "der Quelle für die Eingabe.")
    @ApiResponse(responseCode = "200", description = "SignInsResponse")
    public SignInsResponse list(@Parameter(hidden = true) AccessContext caller) {
        if (caller == null) {
            return new SignInsResponse(List.of());
        }
        return new SignInsResponse(signIns.pending().stream().map(request -> visible(caller, request))
                .flatMap(Optional::stream).toList());
    }

    @PostMapping(path = "/{requestId}/code", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Einmalcode senden",
            description = "accepted: die wartenden Aufträge laden weiter; rejected: die Quelle hat den Code nicht "
                    + "angenommen, error nennt den Grund; failed: Senden gescheitert; gone: keine offene Anforderung.")
    @ApiResponse(responseCode = "200", description = "AnswerResponse")
    @ApiResponse(responseCode = "404", description = "Keine Anforderung, auf die ein eigener Auftrag wartet oder die der Aufrufer hält")
    public ResponseEntity<AnswerResponse> submit(@Parameter(hidden = true) AccessContext caller,
                                                 @PathVariable String requestId, @RequestBody CodeRequest body) {
        char[] code = body == null || body.code() == null ? new char[0] : body.code();
        try {
            if (own(caller, requestId).isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            if (code.length == 0) {
                return ResponseEntity.badRequest().build();
            }
            SignInRequest.Answer answer = signIns.submit(requestId, code);
            return ResponseEntity.ok(new AnswerResponse(answer.name().toLowerCase(Locale.ROOT),
                    signIns.request(requestId).flatMap(request -> visible(caller, request)).orElse(null)));
        } finally {
            Arrays.fill(code, '\0');
        }
    }

    @PostMapping("/{requestId}/resend")
    @Operation(summary = "Code erneut anfordern", description = "Nur wenn resendAvailable.")
    @ApiResponse(responseCode = "200", description = "SignInResponse")
    @ApiResponse(responseCode = "404", description = "Keine Anforderung, auf die ein eigener Auftrag wartet oder die der Aufrufer hält")
    @ApiResponse(responseCode = "502", description = "Die Quelle hat nicht geantwortet")
    public ResponseEntity<SignInResponse> resend(@Parameter(hidden = true) AccessContext caller,
                                                 @PathVariable String requestId) {
        if (own(caller, requestId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            return signIns.resend(requestId).flatMap(request -> visible(caller, request)).map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (SourceAccessException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }

    @DeleteMapping("/{requestId}")
    @Operation(summary = "Anmeldung ablehnen",
            description = "Die wartenden Aufträge laden ohne diese Dateien weiter; sie fehlen mit sign-in-required.")
    @ApiResponse(responseCode = "204", description = "Abgelehnt")
    @ApiResponse(responseCode = "404", description = "Keine Anforderung, auf die ein eigener Auftrag wartet oder die der Aufrufer hält")
    public ResponseEntity<Void> decline(@Parameter(hidden = true) AccessContext caller,
                                        @PathVariable String requestId) {
        if (own(caller, requestId).isEmpty() || !signIns.decline(requestId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    private Optional<SignInResponse> own(AccessContext caller, String requestId) {
        if (caller == null) {
            return Optional.empty();
        }
        return signIns.request(requestId).flatMap(request -> visible(caller, request));
    }

    /**
     * Die Anforderung mit den eigenen wartenden Aufträgen, oder ohne Aufträge, wenn der Aufrufer sie beim Lesen auf
     * Abruf angelegt hat ({@link SignInRequests#ownedBy}); leer, wenn beides nicht zutrifft.
     */
    private Optional<SignInResponse> visible(AccessContext caller, SignInRequest request) {
        String owner = ExportsController.owner(caller);
        boolean held = signIns.ownedBy(request.id(), owner);
        List<String> own = request.jobIds().stream().filter(jobId -> {
            try {
                return owner.equals(jobs.status(jobId).owner());
            } catch (UnknownJobException e) {
                return false;
            }
        }).toList();
        return own.isEmpty() && !held ? Optional.empty() : Optional.of(SignInResponse.of(request, own));
    }

    /** @param code der Einmalcode; wird nach dem Senden überschrieben */
    public record CodeRequest(@Schema(format = "password") char[] code) {

        @Override
        public String toString() {
            return "CodeRequest[code=***]";
        }
    }

    public record SignInsResponse(List<SignInResponse> signIns) {
    }

    /**
     * @param jobIds eigene Aufträge, die warten
     */
    public record SignInResponse(String id, String sourceId, String service, String title, String message,
                                 String error, boolean resendAvailable, List<String> jobIds, Instant since) {

        static SignInResponse of(SignInRequest request, List<String> jobIds) {
            return new SignInResponse(request.id(), request.sourceId(), request.service(), request.title(),
                    request.message(), request.error(), request.resendAvailable(), jobIds, request.since());
        }
    }

    /**
     * @param answer  {@code accepted}, {@code rejected}, {@code failed} oder {@code gone}
     * @param signIn  die weiter offene Anforderung (bei {@code rejected} mit Fehler), sonst {@code null}
     */
    public record AnswerResponse(@Schema(allowableValues = {"accepted", "rejected", "failed", "gone"}) String answer,
                                 @Schema(nullable = true) SignInResponse signIn) {
    }
}
