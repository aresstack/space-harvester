package com.aresstack.spaceharvester.app.chat.browser;

import com.aresstack.enterpriseai.astu.approval.ApprovalDecision;
import com.aresstack.enterpriseai.astu.approval.ApprovalPromptPort;
import com.aresstack.enterpriseai.astu.approval.ApprovalRequest;
import com.aresstack.enterpriseai.browser.api.BrowserAccessPolicy;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Die Rückfrage vor ändernden Browser-Aktionen ({@code browser_act}, Aktion {@code change}) mit gemerkten Antworten je
 * Website (Angelo 24.11.2026): Steht der Host unter „Immer erlauben“, läuft die Aktion ohne Frage, unter „Immer
 * verbieten“ nie; sonst fragt der Chat mit „Einmal erlauben“, „Immer erlauben“, „Immer verbieten“ und „Abbrechen“,
 * und „Immer“ merkt den Host. Der Host kommt aus dem Ziel, das die Laufzeit selbst gebildet hat
 * ({@code BrowserActApproval}: „host#id“), nie aus Argumenten des Modells. Ohne Host gibt es kein „Immer“.
 */
final class ChangeApprovals implements ApprovalPromptPort {

    /** Wohin die Antworten gehen. */
    interface Memory {
        BrowserAccessPolicy changes();

        void allowChanges(String host);

        void blockChanges(String host);
    }

    private final Memory memory;
    private final Supplier<ApprovalPromptPort> prompt;

    ChangeApprovals(Memory memory, Supplier<ApprovalPromptPort> prompt) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
    }

    @Override
    public ApprovalDecision ask(ApprovalRequest request, long timeoutMillis) {
        String host = host(request);
        if (host != null) {
            BrowserAccessPolicy.Verdict remembered = memory.changes().decide("https://" + host + "/");
            if (remembered == BrowserAccessPolicy.Verdict.ALLOWED) {
                return ApprovalDecision.of(request, ApprovalDecision.Outcome.APPROVED);
            }
            if (remembered == BrowserAccessPolicy.Verdict.BLOCKED) {
                return ApprovalDecision.of(request, ApprovalDecision.Outcome.REJECTED);
            }
        }
        ApprovalRequest shown = new ApprovalRequest(request.toolName(), request.target(), host != null);
        ApprovalDecision decision = prompt.get().ask(shown, timeoutMillis);
        ApprovalDecision.Outcome outcome = decision == null || !shown.id().equals(decision.requestId())
                ? ApprovalDecision.Outcome.UNAVAILABLE : decision.outcome();
        if (host != null && outcome == ApprovalDecision.Outcome.APPROVED_ALWAYS) {
            memory.allowChanges(host);
        } else if (host != null && outcome == ApprovalDecision.Outcome.REJECTED_ALWAYS) {
            memory.blockChanges(host);
        }
        return ApprovalDecision.of(request, outcome);
    }

    /** Der Host aus dem Ziel „host#id“; {@code null}, wenn keiner bekannt ist. */
    static String host(ApprovalRequest request) {
        String target = request.target() == null ? null : request.target().targetId();
        if (target == null) {
            return null;
        }
        int hash = target.indexOf('#');
        String host = (hash < 0 ? target : target.substring(0, hash)).trim();
        return host.isEmpty() ? null : host;
    }
}
