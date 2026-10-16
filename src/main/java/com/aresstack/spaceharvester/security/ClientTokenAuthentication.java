package com.aresstack.spaceharvester.security;

import com.aresstack.spaceharvester.application.access.AccessContext;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Anmeldung per Bearer-Token: vor der Prüfung nur das Token, danach nur der {@link AccessContext} des Clients. Das
 * Token wird nach der Prüfung verworfen und erscheint nie in {@code toString}.
 */
final class ClientTokenAuthentication extends AbstractAuthenticationToken {

    private final AccessContext context;
    private String token;

    private ClientTokenAuthentication(String token) {
        super(List.of());
        this.context = null;
        this.token = token;
    }

    private ClientTokenAuthentication(AccessContext context) {
        super(List.of(new SimpleGrantedAuthority("ROLE_TECHNICAL_CLIENT")));
        this.context = context;
        setAuthenticated(true);
    }

    static ClientTokenAuthentication unauthenticated(String token) {
        return new ClientTokenAuthentication(token);
    }

    static ClientTokenAuthentication authenticated(AccessContext context) {
        return new ClientTokenAuthentication(context);
    }

    @Override
    public Object getCredentials() {
        return token;
    }

    @Override
    public AccessContext getPrincipal() {
        return context;
    }

    @Override
    public String getName() {
        return context == null ? "" : context.principal();
    }

    @Override
    public void eraseCredentials() {
        super.eraseCredentials();
        token = null;
    }

    @Override
    public String toString() {
        return "ClientTokenAuthentication[" + getName() + ", authenticated=" + isAuthenticated() + "]";
    }
}
