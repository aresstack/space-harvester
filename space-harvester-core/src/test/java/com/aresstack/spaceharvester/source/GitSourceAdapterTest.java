package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.git.GitCredentials;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Git mit Anmeldung über den SecretProvider (#188), wie {@code SecretBackedGitCredentialsProvider} im Client. */
class GitSourceAdapterTest {

    private static final SecretRef REF = SecretRef.of("keepass:Git/Repo");

    @TempDir
    Path clones;

    @Test
    void acceptsACredentialRefForHttps() {
        GitSourceAdapter adapter = new GitSourceAdapter(clones);

        assertThat(adapter.check(config(Map.of("url", "https://git.example.org/team/repo.git",
                "credentialRef", REF.id())))).isEmpty();
    }

    @Test
    void resolvesUserAndTokenOnEveryFetchAndAllowsAnEntryWithoutUser() throws Exception {
        int[] calls = {0};
        SecretProvider secrets = ref -> {
            calls[0]++;
            return new SecretMaterial(ref, calls[0] == 1 ? "angelo" : null, "pat-123".toCharArray());
        };

        GitCredentials first = GitSourceAdapter.credentials(secrets, REF).credentials();
        GitCredentials second = GitSourceAdapter.credentials(secrets, REF).credentials();

        assertThat(first.username()).isEqualTo("angelo");
        assertThat(second.username()).isEqualTo("git");
        assertThat(first.toString()).doesNotContain("pat-123");
        assertThat(calls[0]).isEqualTo(2);
    }

    @Test
    void anEntryWithoutTokenIsNotFound() {
        SecretProvider secrets = ref -> new SecretMaterial(ref, "angelo", new char[0]);

        assertThatThrownBy(() -> GitSourceAdapter.credentials(secrets, REF).credentials())
                .isInstanceOf(SecretUnavailableException.class);
    }

    private static SourceSettingsConfig config(Map<String, String> options) {
        return new SourceSettingsConfig("repo", GitSourceAdapter.TYPE, null, null, null, options);
    }
}
