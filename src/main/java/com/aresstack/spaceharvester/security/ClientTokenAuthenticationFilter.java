package com.aresstack.spaceharvester.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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
 */
final class ClientTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(ClientTokenAuthenticationFilter.class);
    private static final String BEARER = "Bearer ";

    private final ClientTokenAuthenticationProvider provider;

    ClientTokenAuthenticationFilter(ClientTokenAuthenticationProvider provider) {
        this.provider = provider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            try {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(provider.authenticate(
                        ClientTokenAuthentication.unauthenticated(header.substring(BEARER.length()).trim())));
                SecurityContextHolder.setContext(context);
            } catch (AuthenticationException e) {
                SecurityContextHolder.clearContext();
                LOG.info("Anmeldung mit unbekanntem Bearer-Token abgelehnt: {} {}", request.getMethod(),
                        request.getRequestURI());
            }
        }
        chain.doFilter(request, response);
    }
}
