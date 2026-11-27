package com.aresstack.askai.research.runtime.role;

/**
 * The output shape a {@link ResearchRole} answers in (ADR 0001, 4.2: every decision is a structured output).
 * The kind tells the transport how to constrain the generation; the schema is either fixed by the role or,
 * for roles whose schema depends on the request (a list length, an allowed id set), built per call by the
 * role itself. Code never interprets free text: a TEXT role is parsed by a strict, named contract.
 */
public final class RoleOutput {

    /** How the model's answer is constrained at generation time. */
    public enum Kind {
        /** Plain text, read by a strict parser named in {@link #getContract()}. */
        TEXT,
        /** Generic JSON mode; the role's validator checks the shape. */
        JSON,
        /** A JSON schema enforced at generation time. */
        JSON_SCHEMA
    }

    private final Kind kind;
    private final String schemaJson;
    private final String contract;

    private RoleOutput(Kind kind, String schemaJson, String contract) {
        if (contract == null || contract.trim().isEmpty()) {
            throw new IllegalArgumentException("an output needs a named contract");
        }
        this.kind = kind;
        this.schemaJson = schemaJson == null ? "" : schemaJson;
        this.contract = contract.trim();
    }

    /** Plain text, parsed by {@code contract} (for example the TeamAgent's turn parser or a label list). */
    public static RoleOutput text(String contract) {
        return new RoleOutput(Kind.TEXT, "", contract);
    }

    /** Generic JSON mode, validated by {@code contract}. */
    public static RoleOutput json(String contract) {
        return new RoleOutput(Kind.JSON, "", contract);
    }

    /** A schema the role builds per request (its shape depends on the request), validated by {@code contract}. */
    public static RoleOutput schemaPerRequest(String contract) {
        return new RoleOutput(Kind.JSON_SCHEMA, "", contract);
    }

    /** A fixed JSON schema, validated by {@code contract}. */
    public static RoleOutput schema(String schemaJson, String contract) {
        if (schemaJson == null || schemaJson.trim().isEmpty()) {
            throw new IllegalArgumentException("a fixed schema must not be blank");
        }
        return new RoleOutput(Kind.JSON_SCHEMA, schemaJson, contract);
    }

    public Kind getKind() {
        return kind;
    }

    /** The fixed schema; empty for TEXT, JSON and per-request schemas. */
    public String getSchemaJson() {
        return schemaJson;
    }

    /** Whether a JSON_SCHEMA role builds its schema per request rather than declaring a fixed one. */
    public boolean isSchemaPerRequest() {
        return kind == Kind.JSON_SCHEMA && schemaJson.isEmpty();
    }

    /** The name of the parser or validator that reads the answer, for diagnostics and the role overview. */
    public String getContract() {
        return contract;
    }

    @Override
    public String toString() {
        return kind + (isSchemaPerRequest() ? "(per request)" : "") + " via " + contract;
    }
}
