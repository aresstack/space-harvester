package com.aresstack.enterpriseai.astu.approval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The single check point before a tool runs: resolve the real target, pick the policy, ask the user on
 * {@link ApprovalPolicy#ASK}. Fails closed: DENY, rejection, timeout, cancel, no channel, a stale decision or a
 * resolver error all mean the tool does not run. After an approval the target is resolved again; it must still be the
 * same object (id, version, action, scope), otherwise the call does not run either. Tools with a version still check it
 * themselves when they write (optimistic locking), which closes the remaining gap up to the actual write.
 * Thread-safe; parallel calls get their own requests.
 */
public final class ToolApprovalGate {

    /** Default wait for an answer before the call counts as {@link ApprovalDecision.Outcome#TIMEOUT}. */
    public static final long DEFAULT_TIMEOUT_MILLIS = 5L * 60L * 1000L;

    /** Everything allowed, nobody asked: the behaviour without configured rules. */
    public static ToolApprovalGate allowAll() {
        return new ToolApprovalGate(ApprovalRules::none, ApprovalPromptPort.unavailable(), DEFAULT_TIMEOUT_MILLIS);
    }

    private final Supplier<ApprovalRules> rules;
    private final ApprovalPromptPort prompt;
    private final long timeoutMillis;
    private final Map<String, ApprovalTargetResolver> resolvers = new ConcurrentHashMap<String, ApprovalTargetResolver>();
    private final Map<String, List<String>> subRules = new ConcurrentHashMap<String, List<String>>();
    private final Map<String, Map<String, ApprovalPolicy>> defaults =
            new ConcurrentHashMap<String, Map<String, ApprovalPolicy>>();

    /**
     * @param rules  current rules, asked on every call so saved settings apply without restart
     * @param prompt where ASK questions go
     */
    public ToolApprovalGate(Supplier<ApprovalRules> rules, ApprovalPromptPort prompt, long timeoutMillis) {
        if (rules == null || prompt == null || timeoutMillis <= 0) {
            throw new IllegalArgumentException("rules and prompt must not be null, timeout must be positive");
        }
        this.rules = rules;
        this.prompt = prompt;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Registers the side-effect-free target resolver of {@code toolName}; without one the target is "invoke".
     *
     * @param subRules the {@code action} or {@code action/scope} combinations the settings offer their own rule for
     *                 (e.g. {@code delete/chat}, {@code delete/project}), each with the tool's built-in default policy,
     *                 or {@code null} to follow the tool-level rule. A rule in the settings ({@code tools.approval})
     *                 for the same key replaces the default. May be empty.
     */
    public void registerResolver(String toolName, ApprovalTargetResolver resolver,
                                 Map<String, ApprovalPolicy> subRules) {
        if (toolName == null || resolver == null) {
            throw new IllegalArgumentException("toolName and resolver must not be null");
        }
        resolvers.put(toolName, resolver);
        List<String> keys = new ArrayList<String>();
        Map<String, ApprovalPolicy> defaults = new LinkedHashMap<String, ApprovalPolicy>();
        if (subRules != null) {
            for (Map.Entry<String, ApprovalPolicy> rule : subRules.entrySet()) {
                if (rule.getKey() == null || rule.getKey().trim().isEmpty()) {
                    continue;
                }
                String key = rule.getKey().trim().toLowerCase(Locale.ROOT);
                keys.add(key);
                if (rule.getValue() != null) {
                    int slash = key.indexOf('/');
                    defaults.put(ApprovalRules.key(toolName, slash < 0 ? key : key.substring(0, slash),
                            slash < 0 ? null : key.substring(slash + 1)), rule.getValue());
                }
            }
        }
        this.subRules.put(toolName, Collections.unmodifiableList(keys));
        this.defaults.put(toolName, Collections.unmodifiableMap(defaults));
    }

    /** The sub-rules ({@code action} or {@code action/scope}) the settings offer for {@code toolName}; may be empty. */
    public List<String> subRules(String toolName) {
        List<String> rules = toolName == null ? null : subRules.get(toolName);
        return rules == null ? Collections.<String>emptyList() : rules;
    }

    /** The rules in force now: built-in defaults of the tools plus the configured rules. */
    public ApprovalRules rules() {
        return currentRules();
    }

    /** The effective policy of this call (for display); a resolver error counts as {@link ApprovalPolicy#DENY}. */
    public ApprovalPolicy policy(String toolName, Map<String, Object> arguments) {
        try {
            return currentRules().policy(toolName, resolve(toolName, arguments));
        } catch (RuntimeException e) {
            return ApprovalPolicy.DENY;
        }
    }

    /** Decides whether {@code call} may run now; on ASK this blocks until the user answers. */
    public Verdict check(String toolName, Map<String, Object> arguments) {
        if (toolName == null || toolName.trim().isEmpty()) {
            return Verdict.refused("kein Aufruf");
        }
        ApprovalTarget target;
        try {
            target = resolve(toolName, arguments);
        } catch (RuntimeException e) {
            return Verdict.refused("Ziel des Aufrufs ließ sich nicht bestimmen");
        }
        ApprovalPolicy policy = currentRules().policy(toolName, target);
        if (policy == ApprovalPolicy.ALLOW) {
            return Verdict.allowed();
        }
        if (policy == ApprovalPolicy.DENY) {
            return Verdict.refused("in den Einstellungen verboten");
        }
        ApprovalRequest request = new ApprovalRequest(toolName, target);
        ApprovalDecision decision;
        try {
            decision = prompt.ask(request, timeoutMillis);
        } catch (RuntimeException e) {
            decision = ApprovalDecision.of(request, ApprovalDecision.Outcome.UNAVAILABLE);
        }
        if (decision == null || !decision.approves(request)) {
            return Verdict.refused(reason(decision, request));
        }
        // The user may have taken a while: resolve the target again right before execution. The approval holds only
        // for the same object in the same version and scope; a changed, moved or deleted target needs a new question.
        ApprovalTarget current;
        try {
            current = resolve(toolName, arguments);
        } catch (RuntimeException e) {
            return Verdict.refused("Ziel des Aufrufs ist nicht mehr vorhanden");
        }
        if (!target.equals(current)) {
            return Verdict.refused("Ziel hat sich seit der Bestätigung geändert");
        }
        if (currentRules().policy(toolName, current) == ApprovalPolicy.DENY) {
            return Verdict.refused("in den Einstellungen verboten");
        }
        return Verdict.allowed();
    }

    /** The tools' built-in defaults, overridden key by key by the configured rules. */
    private ApprovalRules currentRules() {
        ApprovalRules current = rules.get();
        Map<String, ApprovalPolicy> merged = new LinkedHashMap<String, ApprovalPolicy>();
        for (Map<String, ApprovalPolicy> builtIn : defaults.values()) {
            merged.putAll(builtIn);
        }
        if (current != null) {
            merged.putAll(current.rules());
        }
        return new ApprovalRules(merged);
    }

    private ApprovalTarget resolve(String toolName, Map<String, Object> arguments) {
        ApprovalTargetResolver resolver = resolvers.get(toolName);
        Map<String, Object> args = arguments == null ? Collections.<String, Object>emptyMap() : arguments;
        ApprovalTarget target = (resolver == null ? ApprovalTargetResolver.invoke() : resolver).resolve(toolName, args);
        if (target == null) {
            throw new IllegalStateException("resolver returned no target");
        }
        return target;
    }

    private static String reason(ApprovalDecision decision, ApprovalRequest request) {
        if (decision == null || !request.id().equals(decision.requestId())) {
            return "keine gültige Bestätigung";
        }
        switch (decision.outcome()) {
            case REJECTED:
            case REJECTED_ALWAYS:
                return "vom Benutzer abgelehnt";
            case TIMEOUT:
                return "keine Antwort auf die Rückfrage";
            case CANCELLED:
                return "Rückfrage abgebrochen";
            case UNAVAILABLE:
                return "keine Rückfrage möglich";
            default:
                return "keine gültige Bestätigung";
        }
    }

    /** Result of {@link #check}: run or not, and why not. */
    public static final class Verdict {

        static Verdict allowed() {
            return new Verdict(true, "");
        }

        private final boolean permitted;
        private final String reason;

        private Verdict(boolean permitted, String reason) {
            this.permitted = permitted;
            this.reason = reason;
        }

        static Verdict refused(String reason) {
            return new Verdict(false, reason);
        }

        public boolean permitted() {
            return permitted;
        }

        /** Why the call does not run; empty when permitted. */
        public String reason() {
            return reason;
        }
    }
}
