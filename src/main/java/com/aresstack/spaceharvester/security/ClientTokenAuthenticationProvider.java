package com.aresstack.spaceharvester.security;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;

/** Prüft Bearer-Tokens technischer Clients gegen die konfigurierten SHA-256-Werte. */
final class ClientTokenAuthenticationProvider implements AuthenticationProvider {

    private final ClientTokens tokens;

    ClientTokenAuthenticationProvider(ClientTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String token = (String) authentication.getCredentials();
        return tokens.authenticate(token)
                .map(ClientTokenAuthentication::authenticated)
                .orElseThrow(() -> new BadCredentialsException("Unbekanntes Token"));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return ClientTokenAuthentication.class.isAssignableFrom(authentication);
    }
}
