package com.aresstack.askai.research.domain.scope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The changes ONE scoping turn proposes. The application applies them to the existing draft — the assistant
 * never replaces the draft, so nothing it forgot to repeat can be lost.
 * <p>
 * Applying is all-or-nothing per turn: the operations run in order onto a builder of the NEXT revision, and
 * an empty patch is a legitimate turn (a purely conversational reply changes nothing).
 */
public final class ScopePatch {

    private final List<ScopePatchOperation> operations;

    public ScopePatch(List<ScopePatchOperation> operations) {
        this.operations = operations == null || operations.isEmpty()
                ? Collections.<ScopePatchOperation>emptyList()
                : Collections.unmodifiableList(new ArrayList<ScopePatchOperation>(operations));
    }

    public static ScopePatch empty() {
        return new ScopePatch(null);
    }

    public List<ScopePatchOperation> getOperations() {
        return operations;
    }

    public boolean isEmpty() {
        return operations.isEmpty();
    }

    /**
     * The next revision of {@code draft} with these operations applied. An EMPTY patch returns the draft
     * unchanged — including its revision, so a chatty turn does not inflate the history.
     */
    public ResearchScopeDraft applyTo(ResearchScopeDraft draft) {
        if (operations.isEmpty()) {
            return draft;
        }
        ResearchScopeDraft.Builder builder = draft.toBuilder();
        applyToBuilder(builder);
        return builder.nextRevision().build();
    }

    /**
     * Apply the operations WITHOUT deciding the revision — for a caller that combines several sources into
     * ONE new revision (and whose store owns the counter). Bumping here as well would count one turn twice.
     */
    public void applyToBuilder(ResearchScopeDraft.Builder builder) {
        for (ScopePatchOperation operation : operations) {
            operation.applyTo(builder);
        }
    }

    /** One line per change — what this turn actually did to the scope. */
    public List<String> describeOperations() {
        List<String> described = new ArrayList<String>();
        for (ScopePatchOperation operation : operations) {
            described.add(operation.describe());
        }
        return Collections.unmodifiableList(described);
    }
}
