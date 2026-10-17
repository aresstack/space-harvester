package com.aresstack.spaceharvester.security;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.UserCredential;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Liest {@code Authorization: Bearer <token>} und meldet den passenden technischen Client an. Ohne oder mit unbekanntem
 * Token bleibt die Anfrage anonym; geschützte Pfade antworten dann mit 401. Das Token wird nie geloggt.
 *
 * <p>Reicht der Client zusätzlich die Anmeldung eines Endnutzers durch ({@value #DELEGATED_SOURCE} und
 * {@value #DELEGATED_AUTHORIZATION}, #24 Teil C), läuft die Anfrage als dieser Endnutzer. Ist der Client dafür nicht
 * freigegeben, lehnt die Quelle das Token ab oder antwortet sie unklar, endet die Anfrage hier; sie läuft nie
 * stattdessen mit den Rechten des Clients weiter.
 */
final class ClientTokenAuthenticationFilter extends OncePerRequestFilter {

    static final String DELEGATED_SOURCE = "X-Delegated-Source";
    static final String DELEGATED_AUTHORIZATION = "X-Delegated-Authorization";

    private static final Logger LOG = LoggerFactory.getLogger(ClientTokenAuthenticationFilter.class);
    private static final String BEARER = "Bearer ";

    private final ClientTokenAuthenticationProvider provider;
    private final DelegatedUsers delegatedUsers;

    ClientTokenAuthenticationFilter(ClientTokenAuthenticationProvider provider, DelegatedUsers delegatedUsers) {
        this.provider = provider;
        this.delegatedUsers = delegatedUsers;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> token = bearer(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token.isPresent()) {
            try {
                ClientTokenAuthentication client = (ClientTokenAuthentication) provider.authenticate(
                        ClientTokenAuthentication.unauthenticated(token.get()));
                ClientTokenAuthentication caller = delegate(request, response, client.getPrincipal());
                if (caller == null) {
                    return;
                }
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(caller);
                SecurityContextHolder.setContext(context);
            } catch (AuthenticationException e) {
                SecurityContextHolder.clearContext();
                LOG.info("Anmeldung mit unbekanntem Bearer-Token abgelehnt: {} {}", request.getMethod(),
                        request.getRequestURI());
            }
        }
        chain.doFilter(request, response);
    }

    /** Der angemeldete Aufrufer, oder {@code null}, wenn die Antwort schon geschrieben ist. */
    private ClientTokenAuthentication delegate(HttpServletRequest request, HttpServletResponse response,
                                               AccessContext client) throws IOException {
        String sourceId = request.getHeader(DELEGATED_SOURCE);
        String authorization = request.getHeader(DELEGATED_AUTHORIZATION);
        if (sourceId == null && authorization == null) {
            return ClientTokenAuthentication.authenticated(client);
        }
        Optional<String> userToken = bearer(authorization);
        if (sourceId == null || sourceId.isBlank() || userToken.isEmpty()
                || !delegatedUsers.allows(client.principal(), sourceId)) {
            LOG.info("Durchreichung für Client {} abgelehnt (nicht freigegeben oder unvollständig): {} {}",
                    client.principal(), request.getMethod(), request.getRequestURI());
            ProblemResponses.delegationNotAllowed(response);
            return null;
        }
        try {
            Optional<AccessContext> user = delegatedUsers.authenticate(client.principal(),
                    new UserCredential(sourceId, userToken.get()));
            if (user.isEmpty()) {
                LOG.info("Quelle {} lehnt das durchgereichte Token ab (Client {})", sourceId, client.principal());
                ProblemResponses.delegatedTokenRejected(response);
                return null;
            }
            return ClientTokenAuthentication.authenticated(user.get());
        } catch (RuntimeException e) {
            LOG.warn("Durchgereichtes Token für Quelle {} nicht prüfbar (Client {}, {})", sourceId,
                    client.principal(), e.getClass().getSimpleName());
            ProblemResponses.delegationUnavailable(response);
            return null;
        }
    }

    private static Optional<String> bearer(String header) {
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return Optional.empty();
        }
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
