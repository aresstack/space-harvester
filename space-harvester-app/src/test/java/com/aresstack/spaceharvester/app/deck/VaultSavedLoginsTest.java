package com.aresstack.spaceharvester.app.deck;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.SecretVault;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SharePoint-Zugangsdaten im Tresor; die „Verschlüsselung“ gibt im Test nur zurück, was sie bekommt. */
class VaultSavedLoginsTest {

    @TempDir
    Path dir;

    @Test
    void savesAfterSuccessAndLoadsForTheSameHost() throws Exception {
        VaultSavedLogins logins = new VaultSavedLogins(new SecretVault(dir, (script, input) -> input).scope("sharepoint"));
        assertThat(logins.load("intranet.example.org@SSL")).isEmpty();

        char[] password = "geheim".toCharArray();
        logins.save("intranet.example.org@SSL", "BEISPIEL\\nutzer", password);

        assertThat(password).as("Kennwort des Aufrufers bleibt").containsExactly("geheim".toCharArray());
        var loaded = logins.load("INTRANET.example.org@SSL").orElseThrow();
        assertThat(loaded.user()).isEqualTo("BEISPIEL\\nutzer");
        assertThat(loaded.password()).containsExactly("geheim".toCharArray());
        assertThat(logins.load("anderer.example.org")).isEmpty();
    }

    @Test
    void withoutVaultKeepsLoginsUntilTheAppCloses() throws Exception {
        VaultSavedLogins logins = new VaultSavedLogins(new SecretVault(dir, null));
        logins.save("intranet.example.org", "nutzer", "geheim".toCharArray());
        assertThat(logins.load("intranet.example.org").orElseThrow().user()).isEqualTo("nutzer");
        assertThat(VaultSavedLogins.id("Intranet.Example.org@SSL")).isEqualTo("sp-intranet-example-org-ssl");
    }
}
