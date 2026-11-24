package com.aresstack.spaceharvester.app.chat.browser;

import com.aresstack.enterpriseai.astu.approval.ApprovalDecision;
import com.aresstack.enterpriseai.astu.approval.ApprovalRequest;
import com.aresstack.enterpriseai.astu.approval.ApprovalTarget;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Angelo 24.11.2026: Ändernde Browser-Aktionen fragen mit „Immer“ je Website und merken die Antwort. */
class ChangeApprovalsTest {

    private final Map<String, String> values = new HashMap<>();
    private final BrowserSettings settings = new BrowserSettings(new BrowserSettings.Store() {
        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public void putAll(Map<String, String> changes) {
            values.putAll(changes);
        }
    });
    private final List<ApprovalRequest> asked = new ArrayList<>();
    private ApprovalDecision.Outcome answer = ApprovalDecision.Outcome.APPROVED_ALWAYS;
    private final ChangeApprovals approvals = new ChangeApprovals(settings, () -> (request, timeout) -> {
        asked.add(request);
        return ApprovalDecision.of(request, answer);
    });

    private static ApprovalRequest change(String target) {
        return new ApprovalRequest("browser_act", new ApprovalTarget("change", null, target, null,
                "Browser: „Suche“ absenden"));
    }

    @Test
    void alwaysAllowRemembersTheSiteAndTheNextActionThereRunsWithoutQuestion() {
        ApprovalRequest first = change("portal.example#ctl-1");
        ApprovalDecision decision = approvals.ask(first, 1000);
        assertThat(decision.approves(first)).isTrue();
        assertThat(asked).singleElement().satisfies(shown -> assertThat(shown.offersAlways()).isTrue());

        ApprovalRequest second = change("portal.example#ctl-7");
        assertThat(approvals.ask(second, 1000).approves(second)).isTrue();
        assertThat(asked).hasSize(1);
        assertThat(settings.changes().allow()).containsExactly("portal.example");

        // Eine andere Website fragt weiter.
        answer = ApprovalDecision.Outcome.APPROVED;
        approvals.ask(change("shop.example#ctl-1"), 1000);
        assertThat(asked).hasSize(2);
    }

    @Test
    void alwaysForbidRemembersTheSiteAndWithoutHostThereIsNoAlways() {
        answer = ApprovalDecision.Outcome.REJECTED_ALWAYS;
        ApprovalRequest first = change("shop.example#ctl-2");
        assertThat(approvals.ask(first, 1000).approves(first)).isFalse();
        ApprovalRequest again = change("shop.example#ctl-3");
        assertThat(approvals.ask(again, 1000).approves(again)).isFalse();
        assertThat(asked).hasSize(1);
        assertThat(settings.changes().block()).containsExactly("shop.example");

        answer = ApprovalDecision.Outcome.APPROVED;
        approvals.ask(change(null), 1000);
        assertThat(asked.get(1).offersAlways()).isFalse();
    }
}
