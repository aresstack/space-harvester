package com.aresstack.askai.agent.model.port.resource;

import java.util.Objects;

/**
 * An OPAQUE identity of one model on one endpoint, as the resource view reports it (ADR 0001, 4.5). It is
 * deliberately not the model name: plugins never see model names (ADR 0001, 3.2). Two slots bound to equal
 * handles share the same loaded weights, which is all a caller needs to know to group work by model and to
 * see that a model switch is coming. The token is stable for the lifetime of the platform's configuration
 * and carries no meaning beyond equality.
 */
public final class ModelHandle {

    private final String token;

    private ModelHandle(String token) {
        this.token = token;
    }

    /** For implementations: wraps the platform's opaque token; non-blank. */
    public static ModelHandle of(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("model handle token must not be blank");
        }
        return new ModelHandle(token);
    }

    public String getToken() {
        return token;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ModelHandle && ((ModelHandle) other).token.equals(token);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(token);
    }

    @Override
    public String toString() {
        return token;
    }
}
