package com.aresstack.enterpriseai.astu.approval;

/**
 * What a tool call actually acts on, as resolved from trusted runtime data (never copied unchecked from model
 * arguments): the action ({@code delete}), the scope of the real target object ({@code project}), its id and the
 * version the approval refers to. Only action and scope select a rule; id and version bind an approval to one object.
 */
public final class ApprovalTarget {

    /** Action of tools without their own resolver. */
    public static final String INVOKE = "invoke";

    private final String action;
    private final String scope;
    private final String targetId;
    private final String version;
    private final String description;

    public ApprovalTarget(String action, String scope, String targetId, String version, String description) {
        this.action = normalize(action) == null ? INVOKE : normalize(action);
        this.scope = normalize(scope);
        this.targetId = targetId == null ? null : targetId.trim();
        this.version = version == null ? null : version.trim();
        this.description = description == null ? "" : description.trim();
    }

    /** The plain call of a tool without action or scope. */
    public static ApprovalTarget invoke() {
        return new ApprovalTarget(INVOKE, null, null, null, "");
    }

    private static String normalize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public String action() {
        return action;
    }

    /** Scope of the real target object, or {@code null}. */
    public String scope() {
        return scope;
    }

    /** Id of the target object, or {@code null}. */
    public String targetId() {
        return targetId;
    }

    /** Version of the target object the approval refers to, or {@code null}. */
    public String version() {
        return version;
    }

    /** Human-readable description of the action, without secrets; may be empty. */
    public String description() {
        return description;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ApprovalTarget)) {
            return false;
        }
        ApprovalTarget that = (ApprovalTarget) other;
        return action.equals(that.action) && eq(scope, that.scope) && eq(targetId, that.targetId)
                && eq(version, that.version);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(new Object[]{action, scope, targetId, version});
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    @Override
    public String toString() {
        return "ApprovalTarget[" + action + (scope == null ? "" : "/" + scope) + "]";
    }
}
