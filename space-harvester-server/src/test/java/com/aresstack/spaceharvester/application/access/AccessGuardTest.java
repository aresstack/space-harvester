package com.aresstack.spaceharvester.application.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class AccessGuardTest {

    private static final AccessContext CLIENT = AccessContext.technicalClient("client");
    private static final ResourceScope DEV = ResourceScope.space("wiki", "DEV");

    @Test
    void onlyAnExplicitAllowPermits() {
        assertThat(new AccessGuard((c, s, k) -> AccessDecision.ALLOW).permits(CLIENT, DEV, AccessKind.METADATA))
                .isTrue();
        assertThat(new AccessGuard((c, s, k) -> AccessDecision.DENY).permits(CLIENT, DEV, AccessKind.METADATA))
                .isFalse();
        assertThat(new AccessGuard((c, s, k) -> AccessDecision.UNDETERMINED)
                .permits(CLIENT, DEV, AccessKind.METADATA)).isFalse();
        assertThat(new AccessGuard((c, s, k) -> null).permits(CLIENT, DEV, AccessKind.METADATA)).isFalse();
    }

    @Test
    void missingContextAndFailingPolicyDeny() {
        AccessGuard allowAll = new AccessGuard((c, s, k) -> AccessDecision.ALLOW);
        assertThat(allowAll.permits(null, DEV, AccessKind.METADATA)).isFalse();

        AccessGuard failing = new AccessGuard((c, s, k) -> {
            throw new IllegalStateException("Timeout bei https://wiki.example/rest?token=geheim");
        });
        assertThat(failing.permits(CLIENT, DEV, AccessKind.CONTENT)).isFalse();
    }

    @Test
    void requireDistinguishesPlainFromConcealedDenial() {
        AccessGuard denyAll = new AccessGuard((c, s, k) -> AccessDecision.DENY);

        assertThatThrownBy(() -> denyAll.require(CLIENT, DEV, AccessKind.METADATA))
                .isInstanceOfSatisfying(AccessDeniedException.class,
                        e -> assertThat(e.concealExistence()).isFalse());
        assertThatThrownBy(() -> denyAll.requireConcealed(CLIENT, ResourceScope.page("wiki", "HR", "7"),
                AccessKind.METADATA))
                .isInstanceOfSatisfying(AccessDeniedException.class, e -> {
                    assertThat(e.concealExistence()).isTrue();
                    assertThat(e.sourceId()).isEqualTo("wiki");
                    assertThat(e.getMessage()).doesNotContain("HR", "7");
                });
    }

    @Test
    void filterKeepsPermittedItemsInOrder() {
        AccessGuard devAndOps = new AccessGuard((c, s, k) ->
                List.of("DEV", "OPS").contains(s.spaceKey()) ? AccessDecision.ALLOW : AccessDecision.DENY);

        assertThat(devAndOps.filter(CLIENT, List.of("OPS", "HR", "DEV"), AccessKind.METADATA,
                key -> ResourceScope.space("wiki", key))).containsExactly("OPS", "DEV");
    }

    @Test
    void canReadAsksForContentOfThePage() {
        AccessGuard guard = new AccessGuard((c, s, k) -> k == AccessKind.CONTENT && "42".equals(s.pageId())
                && "DEV".equals(s.spaceKey()) ? AccessDecision.ALLOW : AccessDecision.DENY);

        assertThat(guard.canRead(CLIENT, "wiki", "DEV", "42")).isTrue();
        assertThat(guard.canRead(CLIENT, "wiki", null, "42")).isFalse();
    }
}
