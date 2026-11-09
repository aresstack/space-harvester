package com.aresstack.spaceharvester.app.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.SecretVault;
import com.aresstack.spaceharvester.app.settings.SecretVaultTest;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der Pairing-Schlüssel im Tresor: Nach dem SRP-Handshake speichert der KeePassRPC-Provider den Schlüssel und öffnet
 * mit demselben Array sofort die erste Sitzung. Das Speichern darf ihn deshalb nicht überschreiben.
 */
class KeePassPairingKeyStoreTest {

    @TempDir
    Path home;

    @Test
    void savingKeepsTheCallersKeyUsableAndStoresIt() {
        SecretVault vault = new SecretVault(home.resolve("secrets"), new SecretVaultTest.FakeDpapi())
                .scope(KeePassLink.PAIRING_VAULT);
        KeePassLink.VaultKeyStore store = new KeePassLink.VaultKeyStore(vault);
        char[] key = new char[64];
        Arrays.fill(key, 'a'); // Testwert, kein echter Schlüssel

        store.save(key);

        assertThat(key).as("Schlüssel für die erste Sitzung unverändert").containsOnly('a');
        assertThat(store.load()).containsExactly(key);
        store.clear();
        assertThat(store.load()).isNull();
    }
}
