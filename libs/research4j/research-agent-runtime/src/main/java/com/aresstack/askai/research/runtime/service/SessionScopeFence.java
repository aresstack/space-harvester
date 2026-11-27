package com.aresstack.askai.research.runtime.service;

/**
 * The runtime's holder for the AUTHORITATIVE scope projection the host sends before a turn ({@code
 * set_scope}). It is context, not state: the runtime never edits it and never derives the scope itself — the
 * host owns the draft, the model only proposes changes to it.
 * <p>
 * Empty until the host has sent one, which simply means "this session has no scope context yet".
 */
public final class SessionScopeFence {

    private volatile String rendered = "";

    public void update(String renderedScope) {
        this.rendered = renderedScope == null ? "" : renderedScope;
    }

    /** The current projection, or "" when the host has not sent one. */
    public String rendered() {
        return rendered;
    }

    public boolean isPresent() {
        return !rendered.isEmpty();
    }
}
