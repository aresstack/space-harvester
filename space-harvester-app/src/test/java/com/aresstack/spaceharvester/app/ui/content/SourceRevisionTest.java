package com.aresstack.spaceharvester.app.ui.content;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceRevisionTest {

    @Test
    void doesNotTreatLiveRevalidationOfTheSameRevisionAsAnUpdate() {
        assertThat(SourceRevision.hasUpdate("revision-1", "revision-1")).isFalse();
        assertThat(SourceRevision.hasUpdate(null, null)).isFalse();
    }

    @Test
    void doesNotClaimAnUpdateWithoutAUsableNewRevision() {
        assertThat(SourceRevision.hasUpdate("revision-1", null)).isFalse();
        assertThat(SourceRevision.hasUpdate("revision-1", "  ")).isFalse();
    }

    @Test
    void reportsAnActualRevisionChange() {
        assertThat(SourceRevision.hasUpdate("revision-1", "revision-2")).isTrue();
        assertThat(SourceRevision.hasUpdate(null, "revision-2")).isTrue();
    }
}
