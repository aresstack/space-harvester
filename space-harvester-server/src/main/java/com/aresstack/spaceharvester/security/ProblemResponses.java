package com.aresstack.spaceharvester.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;

/**
 * Problem Details (RFC 9457) für Anfragen, die die Spring-Security-Filter abweisen, bevor ein Controller läuft. Feste
 * Texte, nichts aus der Anfrage.
 */
final class ProblemResponses {

    static final String TYPE_BASE = "https://github.com/aresstack/space-harvester/problems/";

    private ProblemResponses() {
    }

    static void unauthenticated(HttpServletRequest request, HttpServletResponse response,
                                AuthenticationException exception) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthenticated", "Anmeldung erforderlich",
                "Diese Anfrage verlangt ein gültiges Bearer-Token eines technischen Clients.");
    }

    static void forbidden(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        write(response, HttpServletResponse.SC_FORBIDDEN, "access-denied", "Zugriff verweigert",
                "Für diese Anfrage fehlt die Freigabe.");
    }

    static void delegationNotAllowed(HttpServletResponse response) throws IOException {
        write(response, HttpServletResponse.SC_FORBIDDEN, "delegation-not-allowed", "Durchreichung nicht erlaubt",
                "Dieser Client darf für diese Quelle keine Endnutzer-Anmeldung durchreichen, oder "
                        + "X-Delegated-Source bzw. X-Delegated-Authorization fehlt.");
    }

    static void delegatedTokenRejected(HttpServletResponse response) throws IOException {
        write(response, HttpServletResponse.SC_UNAUTHORIZED, "delegated-token-rejected", "Endnutzer-Anmeldung abgelehnt",
                "Die Quelle lehnt die durchgereichte Anmeldung des Endnutzers ab.");
    }

    static void delegationUnavailable(HttpServletResponse response) throws IOException {
        write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "delegation-unavailable",
                "Endnutzer-Anmeldung nicht prüfbar",
                "Die Quelle konnte die durchgereichte Anmeldung des Endnutzers nicht eindeutig prüfen.");
    }

    private static void write(HttpServletResponse response, int status, String type, String title, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"" + TYPE_BASE + type + "\",\"title\":\"" + title
                + "\",\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }
}
