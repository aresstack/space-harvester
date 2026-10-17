package com.aresstack.spaceharvester.app.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der Tresor ohne Windows: Eine Ersatz-Shell spielt DPAPI (umkehrbar, aber nicht der Klartext). Geprüft wird, was die
 * App mit PowerShell austauscht und ablegt, nicht DPAPI selbst.
 */
public class SecretVaultTest {

    @TempDir
    Path home;

    /** „Verschlüsselt“ durch Umdrehen der Base64-Zeichen; merkt sich jede Eingabe. */
    public static final class FakeDpapi implements SecretVault.Shell {
        final List<String> inputs = new ArrayList<>();

        public FakeDpapi() {
        }

        @Override
        public String run(String script, String input) {
            inputs.add(input);
            String reversed = new StringBuilder(input.trim()).reverse().toString();
            return script.contains("ConvertFrom-SecureString") ? "01000000d08c" + reversed
                    : new StringBuilder(input.trim().substring("01000000d08c".length())).reverse().toString();
        }
    }

    @Test
    void storesOnlyTheProtectedValueAndReadsItBack() throws Exception {
        FakeDpapi shell = new FakeDpapi();
        SecretVault vault = new SecretVault(home.resolve("secrets"), shell);
        char[] token = "Tökén-123".toCharArray();

        vault.store("wiki", "pat", token);

        assertThat(token).as("Eingabe überschrieben").containsOnly('\0');
        assertThat(vault.has("wiki")).isTrue();
        String stored = Files.readString(home.resolve("secrets").resolve("wiki.secret"));
        assertThat(stored).contains("\n01000000d08c").doesNotContain("Tökén");
        assertThat(shell.inputs.get(0)).as("Klartext nur als Base64 über die Eingabe")
                .isEqualTo(Base64.getEncoder().encodeToString("Tökén-123".getBytes(StandardCharsets.UTF_8)));
        assertThat(vault.load("wiki", "pat")).containsExactly("Tökén-123".toCharArray());
        assertThat(vault.load("wiki", "basic:angelo")).as("Token taugt nicht als Passwort").isNull();
        assertThat(vault.has("wiki", "pat")).isTrue();
        assertThat(vault.has("wiki", "basic:angelo")).isFalse();
        String raw = vault.raw("wiki");
        vault.remove("wiki");
        vault.restore("wiki", raw);
        assertThat(vault.load("wiki", "pat")).as("zurückgeholt").containsExactly("Tökén-123".toCharArray());

        vault.remove("wiki");
        assertThat(vault.has("wiki")).isFalse();
        assertThat(vault.load("wiki", "pat")).isNull();
    }

    @Test
    void withoutWindowsThereIsNoVault() throws Exception {
        SecretVault none = new SecretVault(home.resolve("secrets"), null);
        assertThat(none.available()).isFalse();
        assertThat(none.has("wiki")).isFalse();
        assertThat(none.describe()).contains("nur für diese Sitzung");
        assertThatThrownBy(() -> none.store("wiki", "pat", "x".toCharArray())).hasMessageContaining("keinen Tresor");
        assertThatThrownBy(() -> new SecretVault(home, new FakeDpapi()).store("../x", "pat", "x".toCharArray()))
                .hasMessageContaining("Ungültige Quellen-ID");
    }
}
