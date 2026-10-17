package com.aresstack.spaceharvester.application.access;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccessContextTest {

    @Test
    void jobOwnersOfTheSamePersonDifferPerForwardingClient() {
        UserCredential token = new UserCredential("wiki", "t0k3n");

        String viaAssistant = AccessContext.endUser("alice", "assistant", token).jobOwner();
        String viaPortal = AccessContext.endUser("alice", "portal", token).jobOwner();

        assertThat(viaAssistant).isEqualTo("END_USER:alice@assistant").isNotEqualTo(viaPortal);
        assertThat(AccessContext.technicalClient("etl").jobOwner()).isEqualTo("TECHNICAL_CLIENT:etl");
        assertThat(viaAssistant).doesNotContain("t0k3n");
    }
}
