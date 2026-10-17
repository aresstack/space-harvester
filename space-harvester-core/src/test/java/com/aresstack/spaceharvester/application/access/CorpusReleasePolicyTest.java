package com.aresstack.spaceharvester.application.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CorpusReleasePolicyTest {

    private static final AccessContext READER = AccessContext.technicalClient("reader");
    private static final AccessContext EXPORTER = AccessContext.technicalClient("exporter");

    private final CorpusReleasePolicy policy = new CorpusReleasePolicy(Map.of(
            "reader", List.of(new CorpusReleasePolicy.Grant("wiki", false, Set.of("DEV"), Set.of(AccessKind.METADATA))),
            "exporter", List.of(new CorpusReleasePolicy.Grant("wiki", true, Set.of(),
                    Set.of(AccessKind.METADATA, AccessKind.CONTENT, AccessKind.EXPORT)))));

    @Test
    void grantedSpaceIsAllowedOthersAreDenied() {
        assertThat(policy.decide(READER, ResourceScope.space("wiki", "DEV"), AccessKind.METADATA))
                .isEqualTo(AccessDecision.ALLOW);
        assertThat(policy.decide(READER, ResourceScope.space("wiki", "HR"), AccessKind.METADATA))
                .isEqualTo(AccessDecision.DENY);
        assertThat(policy.decide(READER, ResourceScope.page("wiki", "DEV", "1"), AccessKind.METADATA))
                .isEqualTo(AccessDecision.ALLOW);
        assertThat(policy.decide(READER, ResourceScope.page("wiki", "HR", "1"), AccessKind.METADATA))
                .isEqualTo(AccessDecision.DENY);
    }

    @Test
    void sourceIsVisibleWithAnyGrantOnIt() {
        assertThat(policy.decide(READER, ResourceScope.source("wiki"), AccessKind.METADATA))
                .isEqualTo(AccessDecision.ALLOW);
        assertThat(policy.decide(READER, ResourceScope.source("other"), AccessKind.METADATA))
                .isEqualTo(AccessDecision.DENY);
    }

    @Test
    void kindsAreIndependent() {
        assertThat(policy.decide(READER, ResourceScope.space("wiki", "DEV"), AccessKind.CONTENT))
                .isEqualTo(AccessDecision.DENY);
        assertThat(policy.decide(READER, ResourceScope.space("wiki", "DEV"), AccessKind.EXPORT))
                .isEqualTo(AccessDecision.DENY);
    }

    @Test
    void pageWithoutKnownSpaceIsOnlyAllowedForAllSpaces() {
        assertThat(policy.decide(READER, ResourceScope.page("wiki", null, "1"), AccessKind.METADATA))
                .isEqualTo(AccessDecision.UNDETERMINED);
        assertThat(policy.decide(EXPORTER, ResourceScope.page("wiki", null, "1"), AccessKind.CONTENT))
                .isEqualTo(AccessDecision.ALLOW);
    }

    @Test
    void unknownClientsAndOtherPrincipalsAreDenied() {
        assertThat(policy.decide(AccessContext.technicalClient("stranger"), ResourceScope.source("wiki"),
                AccessKind.METADATA)).isEqualTo(AccessDecision.DENY);
        assertThat(policy.decide(AccessContext.endUser("reader", "reader", new UserCredential("wiki", "token")),
                ResourceScope.space("wiki", "DEV"), AccessKind.METADATA)).isEqualTo(AccessDecision.DENY);
        assertThat(policy.decide(new AccessContext("exporter", AccessContext.PrincipalKind.DEVELOPMENT),
                ResourceScope.space("wiki", "DEV"), AccessKind.METADATA)).isEqualTo(AccessDecision.DENY);
    }
}
