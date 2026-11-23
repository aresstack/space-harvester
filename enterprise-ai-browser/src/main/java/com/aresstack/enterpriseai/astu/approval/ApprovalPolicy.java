package com.aresstack.enterpriseai.astu.approval;

/** What happens with a tool call before it runs. */
public enum ApprovalPolicy {

    /** Run without asking. */
    ALLOW("Erlauben"),
    /** Run only after the user approved exactly this call. */
    ASK("Nachfragen"),
    /** Never run. */
    DENY("Verbieten");

    private final String label;

    ApprovalPolicy(String label) {
        this.label = label;
    }

    /** German display name, e.g. "Nachfragen". */
    public String label() {
        return label;
    }

    /** The policy with this name (case-insensitive), or {@code null} for an unknown or empty value. */
    public static ApprovalPolicy parse(String value) {
        if (value == null) {
            return null;
        }
        String name = value.trim();
        for (ApprovalPolicy policy : values()) {
            if (policy.name().equalsIgnoreCase(name)) {
                return policy;
            }
        }
        return null;
    }
}
