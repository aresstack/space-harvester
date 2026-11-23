package com.aresstack.enterpriseai.astu.approval;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Configured policies, keyed by tool name, {@code tool:action} or {@code tool:action/scope}; the most specific key
 * wins, a tool without a rule is {@link ApprovalPolicy#ALLOW}. Text form (setting {@code tools.approval}): entries
 * {@code key=POLICY} separated by {@code ;}, e.g. {@code memory_delete:delete/project=ASK;web_fetch=DENY}.
 */
public final class ApprovalRules {

    /** No rules: every tool is allowed. */
    public static ApprovalRules none() {
        return new ApprovalRules(Collections.<String, ApprovalPolicy>emptyMap());
    }

    private final Map<String, ApprovalPolicy> rules;

    public ApprovalRules(Map<String, ApprovalPolicy> rules) {
        Map<String, ApprovalPolicy> copy = new LinkedHashMap<String, ApprovalPolicy>();
        if (rules != null) {
            for (Map.Entry<String, ApprovalPolicy> rule : rules.entrySet()) {
                String key = normalizeKey(rule.getKey());
                if (key != null && rule.getValue() != null) {
                    copy.put(key, rule.getValue());
                }
            }
        }
        this.rules = Collections.unmodifiableMap(copy);
    }

    /** Rule key for a tool, an action of it or an action in one scope. */
    public static String key(String tool, String action, String scope) {
        StringBuilder key = new StringBuilder(tool.trim());
        if (action != null && !action.trim().isEmpty()) {
            key.append(':').append(action.trim().toLowerCase(Locale.ROOT));
            if (scope != null && !scope.trim().isEmpty()) {
                key.append('/').append(scope.trim().toLowerCase(Locale.ROOT));
            }
        }
        return key.toString();
    }

    private static String normalizeKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            return null;
        }
        String trimmed = key.trim();
        int colon = trimmed.indexOf(':');
        if (colon < 0) {
            return trimmed;
        }
        String rest = trimmed.substring(colon + 1);
        int slash = rest.indexOf('/');
        return key(trimmed.substring(0, colon), slash < 0 ? rest : rest.substring(0, slash),
                slash < 0 ? null : rest.substring(slash + 1));
    }

    /** Parses the text form; unknown policies and malformed entries are skipped. */
    public static ApprovalRules parse(String text) {
        Map<String, ApprovalPolicy> rules = new LinkedHashMap<String, ApprovalPolicy>();
        if (text != null) {
            for (String entry : text.split(";")) {
                int eq = entry.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                ApprovalPolicy policy = ApprovalPolicy.parse(entry.substring(eq + 1));
                if (policy != null) {
                    rules.put(entry.substring(0, eq), policy);
                }
            }
        }
        return new ApprovalRules(rules);
    }

    /** The text form, parseable by {@link #parse}. */
    public String format() {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, ApprovalPolicy> rule : rules.entrySet()) {
            if (text.length() > 0) {
                text.append(';');
            }
            text.append(rule.getKey()).append('=').append(rule.getValue().name());
        }
        return text.toString();
    }

    /** The effective policy for this call: {@code tool:action/scope}, then {@code tool:action}, then {@code tool}. */
    public ApprovalPolicy policy(String tool, ApprovalTarget target) {
        ApprovalTarget t = target == null ? ApprovalTarget.invoke() : target;
        if (t.scope() != null) {
            ApprovalPolicy scoped = rules.get(key(tool, t.action(), t.scope()));
            if (scoped != null) {
                return scoped;
            }
        }
        ApprovalPolicy action = rules.get(key(tool, t.action(), null));
        if (action != null) {
            return action;
        }
        ApprovalPolicy whole = rules.get(key(tool, null, null));
        return whole == null ? ApprovalPolicy.ALLOW : whole;
    }

    /** The configured rule for exactly this key, or {@code null}. */
    public ApprovalPolicy rule(String key) {
        return rules.get(normalizeKey(key));
    }

    /** All configured rules in their order. */
    public Map<String, ApprovalPolicy> rules() {
        return rules;
    }

    /** Copy with {@code key} set to {@code policy}; {@code null} removes the rule. */
    public ApprovalRules with(String key, ApprovalPolicy policy) {
        Map<String, ApprovalPolicy> copy = new LinkedHashMap<String, ApprovalPolicy>(rules);
        String normalized = normalizeKey(key);
        if (normalized == null) {
            return this;
        }
        if (policy == null) {
            copy.remove(normalized);
        } else {
            copy.put(normalized, policy);
        }
        return new ApprovalRules(copy);
    }
}
