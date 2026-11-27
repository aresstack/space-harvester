package com.aresstack.askai.research.domain.scope;

/**
 * ONE proposed change to the scope. The assistant never hands back a complete new scope — it proposes
 * operations, and the application applies them to what already exists. That is the whole point: a full
 * replacement object would silently drop everything the model happened not to repeat this turn.
 * <p>
 * An operation knows how to apply itself, so adding a new kind of change never means extending a switch
 * somewhere else.
 */
public interface ScopePatchOperation {

    /** The wire/codec name of this operation kind (e.g. {@code addFacet}). */
    String kind();

    /** Apply this change to the builder of the next draft revision. */
    void applyTo(ResearchScopeDraft.Builder builder);

    /** A short human-readable form for diagnostics and audit ("what did this turn change?"). */
    String describe();
}
