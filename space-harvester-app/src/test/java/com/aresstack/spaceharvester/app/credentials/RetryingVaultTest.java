package com.aresstack.spaceharvester.app.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException.Reason;
import com.aresstack.enterpriseai.adyton.SecretVault;
import com.aresstack.enterpriseai.adyton.VaultEntry;
import com.aresstack.enterpriseai.adyton.VaultEntryDraft;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Wiederholungen beim Lesen aus KeePass wie im EAC: nur wenn KeePass nicht antwortet, mit 300 ms und 750 ms Pause. */
class RetryingVaultTest {

    private final List<Long> pauses = new ArrayList<>();
    private final AtomicInteger reachable = new AtomicInteger();

    @Test
    void aShortOutageIsRetriedWithTheEacPauses() throws Exception {
        Flaky keePass = new Flaky(2, Reason.NOT_AVAILABLE);
        RetryingVault vault = new RetryingVault(keePass, RetryingVault.DELAYS_MILLIS, pauses::add, reachable::incrementAndGet);

        try (VaultEntryDraft read = vault.read("PAT_Confluence")) {
            assertThat(new String(read.copyPassword())).isEqualTo("pat");
        }
        assertThat(keePass.calls).isEqualTo(3);
        assertThat(pauses).containsExactly(300L, 750L);
        assertThat(reachable).as("gelungener Zugriff meldet: KeePass antwortet").hasValue(1);
    }

    @Test
    void aLongerOutageFailsAfterTheRetriesWithoutReportingReachable() {
        Flaky keePass = new Flaky(5, Reason.NOT_AVAILABLE);
        RetryingVault vault = new RetryingVault(keePass, RetryingVault.DELAYS_MILLIS, pauses::add, reachable::incrementAndGet);

        assertThatThrownBy(() -> vault.read("PAT_Confluence")).isInstanceOf(SecretUnavailableException.class)
                .extracting(e -> ((SecretUnavailableException) e).reason()).isEqualTo(Reason.NOT_AVAILABLE);
        assertThat(keePass.calls).isEqualTo(3);
        assertThat(reachable).hasValue(0);
    }

    @Test
    void aMissingEntryOrADeniedAccessIsNeverRetried() {
        for (Reason reason : List.of(Reason.NOT_FOUND, Reason.ACCESS_DENIED, Reason.CANCELLED)) {
            Flaky keePass = new Flaky(1, reason);
            RetryingVault vault = new RetryingVault(keePass, RetryingVault.DELAYS_MILLIS, pauses::add, () -> { });
            assertThatThrownBy(() -> vault.read("x")).isInstanceOf(SecretUnavailableException.class);
            assertThat(keePass.calls).as(reason.name()).isEqualTo(1);
        }
        assertThat(pauses).isEmpty();
    }

    @Test
    void writesRunExactlyOnce() {
        Flaky keePass = new Flaky(1, Reason.NOT_AVAILABLE);
        RetryingVault vault = new RetryingVault(keePass, RetryingVault.DELAYS_MILLIS, pauses::add, () -> { });
        assertThatThrownBy(() -> vault.create(new VaultEntryDraft("t", "", new char[] {'k'}, "", Map.of())))
                .isInstanceOf(SecretUnavailableException.class);
        assertThat(keePass.writes).isEqualTo(1);
        assertThat(pauses).isEmpty();
    }

    /** Scheitert die ersten {@code failures} Male mit {@code reason}, dann liefert er den Eintrag. */
    private static final class Flaky implements SecretVault {
        private final int failures;
        private final Reason reason;
        int calls;
        int writes;

        Flaky(int failures, Reason reason) {
            this.failures = failures;
            this.reason = reason;
        }

        private void maybeFail() throws SecretUnavailableException {
            if (calls++ < failures) {
                throw new SecretUnavailableException(reason, null, "test");
            }
        }

        @Override
        public List<VaultEntry> list() throws SecretUnavailableException {
            maybeFail();
            return List.of();
        }

        @Override
        public VaultEntryDraft read(String title) throws SecretUnavailableException {
            maybeFail();
            return new VaultEntryDraft(title, "", "pat".toCharArray(), "", Map.of());
        }

        @Override
        public void save(VaultEntryDraft entry) throws SecretUnavailableException {
            create(entry);
        }

        @Override
        public void create(VaultEntryDraft entry) throws SecretUnavailableException {
            writes++;
            throw new SecretUnavailableException(reason, null, "test");
        }

        @Override
        public void remove(String title) {
        }
    }
}
