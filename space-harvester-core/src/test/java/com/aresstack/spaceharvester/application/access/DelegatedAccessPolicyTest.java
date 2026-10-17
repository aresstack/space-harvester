package com.aresstack.spaceharvester.application.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DelegatedAccessPolicyTest {

    private static final UserCredential ALICE = new UserCredential("wiki", "alice-token");
    private static final AccessContext ALICE_VIA_ASSISTANT = AccessContext.endUser("alice", "assistant", ALICE);

    private final FakeSource source = new FakeSource();
    private final MutableClock clock = new MutableClock();
    private final CorpusReleasePolicy corpus = new CorpusReleasePolicy(Map.of("assistant", List.of(
            new CorpusReleasePolicy.Grant("wiki", false, Set.of("DEV", "HR"),
                    Set.of(AccessKind.METADATA, AccessKind.CONTENT, AccessKind.EXPORT)))));
    private final DelegatedAccessPolicy policy = new DelegatedAccessPolicy(corpus, Map.of("wiki", source),
            Duration.ofSeconds(60), 1000, clock);

    @Test
    void endUserNeedsBothTheClientsReleaseAndTheSourcesAnswer() {
        source.visible = Set.of(ResourceScope.page("wiki", "DEV", "100"));

        assertThat(policy.decide(ALICE_VIA_ASSISTANT, ResourceScope.page("wiki", "DEV", "100"), AccessKind.CONTENT))
                .isEqualTo(AccessDecision.ALLOW);
        assertThat(policy.decide(ALICE_VIA_ASSISTANT, ResourceScope.page("wiki", "DEV", "101"), AccessKind.CONTENT))
                .isEqualTo(AccessDecision.DENY);
        // Die Quelle würde zeigen, aber der Client hat den Space nicht frei: die Quelle wird gar nicht gefragt.
        source.visible = Set.of(ResourceScope.page("wiki", "OPS", "300"));
        int asked = source.asked.size();
        assertThat(policy.decide(ALICE_VIA_ASSISTANT, ResourceScope.page("wiki", "OPS", "300"), AccessKind.CONTENT))
                .isEqualTo(AccessDecision.DENY);
        assertThat(source.asked).hasSize(asked);
    }

    @Test
    void endUserExportsWhatTheSourceShowsButNeverReachesAnotherSource() {
        source.visible = Set.of(ResourceScope.space("wiki", "DEV"), ResourceScope.space("other", "DEV"));

        // Die Anmeldeart schränkt keine Funktion ein (ADR 0010): Export wie Lesen, geprüft über die Quelle.
        assertThat(policy.decide(ALICE_VIA_ASSISTANT, ResourceScope.space("wiki", "DEV"), AccessKind.EXPORT))
                .isEqualTo(AccessDecision.ALLOW);
        int asked = source.asked.size();
        assertThat(policy.decide(ALICE_VIA_ASSISTANT, ResourceScope.space("other", "DEV"), AccessKind.METADATA))
                .isEqualTo(AccessDecision.DENY);
        assertThat(source.asked).hasSize(asked);
    }

    @Test
    void endUserExportOfASpaceTheSourceHidesIsDenied() {
        source.visible = Set.of();

        assertThat(policy.decide(ALICE_VIA_ASSISTANT, ResourceScope.space("wiki", "DEV"), AccessKind.EXPORT))
                .isEqualTo(AccessDecision.DENY);
    }

    @Test
    void technicalClientsAreDecidedByTheBasePolicyAlone() {
        assertThat(policy.decide(AccessContext.technicalClient("assistant"), ResourceScope.space("wiki", "HR"),
                AccessKind.EXPORT)).isEqualTo(AccessDecision.ALLOW);
        assertThat(source.asked).isEmpty();
    }

    @Test
    void answersAreHeldForTheConfiguredTimeOnly() {
        source.visible = Set.of(ResourceScope.space("wiki", "DEV"));
        ResourceScope dev = ResourceScope.space("wiki", "DEV");

        policy.decide(ALICE_VIA_ASSISTANT, dev, AccessKind.METADATA);
        source.visible = Set.of();
        assertThat(policy.decide(ALICE_VIA_ASSISTANT, dev, AccessKind.CONTENT)).isEqualTo(AccessDecision.ALLOW);
        assertThat(source.asked).hasSize(1);

        clock.advance(Duration.ofSeconds(61));
        assertThat(policy.decide(ALICE_VIA_ASSISTANT, dev, AccessKind.METADATA)).isEqualTo(AccessDecision.DENY);
        assertThat(source.asked).hasSize(2);
    }

    @Test
    void anotherTokenIsAskedSeparately() {
        source.visible = Set.of(ResourceScope.space("wiki", "DEV"));
        policy.decide(ALICE_VIA_ASSISTANT, ResourceScope.space("wiki", "DEV"), AccessKind.METADATA);

        AccessContext bob = AccessContext.endUser("bob", "assistant", new UserCredential("wiki", "bob-token"));
        policy.decide(bob, ResourceScope.space("wiki", "DEV"), AccessKind.METADATA);

        assertThat(source.asked).hasSize(2);
    }

    @Test
    void unclearAnswersAreNotHeldAndFailClosed() {
        source.unclear = true;
        ResourceScope dev = ResourceScope.space("wiki", "DEV");
        AccessGuard guard = new AccessGuard(policy);

        assertThat(guard.permits(ALICE_VIA_ASSISTANT, dev, AccessKind.METADATA)).isFalse();
        assertThat(guard.permits(ALICE_VIA_ASSISTANT, dev, AccessKind.METADATA)).isFalse();
        assertThat(source.asked).hasSize(2);

        source.unclear = false;
        source.failing = true;
        assertThat(guard.permits(ALICE_VIA_ASSISTANT, dev, AccessKind.METADATA)).isFalse();
    }

    @Test
    void credentialNeverAppearsInToString() {
        assertThat(ALICE_VIA_ASSISTANT.toString()).doesNotContain("alice-token").contains("alice", "assistant");
        assertThat(ALICE.toString()).doesNotContain("alice-token");
    }

    private static final class FakeSource implements DelegatedSourceAccess {

        Set<ResourceScope> visible = Set.of();
        boolean unclear;
        boolean failing;
        final List<ResourceScope> asked = new ArrayList<>();

        @Override
        public String sourceId() {
            return "wiki";
        }

        @Override
        public Optional<String> identify(UserCredential credential) {
            return Optional.empty();
        }

        @Override
        public AccessDecision canSee(UserCredential credential, ResourceScope scope) {
            asked.add(scope);
            if (failing) {
                throw new DelegationUnavailableException("wiki");
            }
            if (unclear) {
                return AccessDecision.UNDETERMINED;
            }
            return visible.contains(scope) ? AccessDecision.ALLOW : AccessDecision.DENY;
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-16T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
