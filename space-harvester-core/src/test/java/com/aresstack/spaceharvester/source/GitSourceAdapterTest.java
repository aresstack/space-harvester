package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.git.GitCredentials;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.Auth;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.AuthType;
import com.aresstack.spaceharvester.application.sources.SourceField;
import com.aresstack.spaceharvester.application.sources.SourceType;
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

    @Test
    void anAccessTokenOrUserAndTokenGoThroughAuth() {
        GitSourceAdapter adapter = new GitSourceAdapter(clones);
        Map<String, String> https = Map.of("url", "https://git.example.org/team/repo.git");

        assertThat(adapter.check(config(new Auth(AuthType.PAT, null, null, "pat-123"), https))).isEmpty();
        assertThat(adapter.check(config(new Auth(AuthType.BASIC, "angelo", "pat-123", null), https))).isEmpty();
        // Ohne Token in der Konfiguration kommt es vom Anbieter des Programms.
        assertThat(adapter.check(config(new Auth(AuthType.PAT, null, null, null), https), true)).isEmpty();
    }

    @Test
    void incompleteOrMixedCredentialsAreRejectedWithoutValues() {
        GitSourceAdapter adapter = new GitSourceAdapter(clones);
        Map<String, String> https = Map.of("url", "https://git.example.org/team/repo.git");
        Map<String, String> withRef = Map.of("url", "https://git.example.org/team/repo.git", "credentialRef", REF.id());

        assertThat(adapter.check(config(new Auth(AuthType.PAT, null, null, null), https)))
                .containsExactly("auth.type pat braucht auth.token");
        assertThat(adapter.check(config(new Auth(AuthType.PAT, "angelo", "geheim", "pat-123"), https)))
                .containsExactly("auth.username und auth.password passen nicht zu auth.type pat");
        assertThat(adapter.check(config(new Auth(AuthType.PAT, null, null, "pat-123"), withRef)))
                .containsExactly("options.credentialRef passt nicht zu auth.type pat; es gilt auth");
        assertThat(String.join(" ", adapter.check(config(new Auth(AuthType.PAT, "angelo", "geheim", "pat-123"),
                withRef)))).doesNotContain("geheim", "pat-123", REF.id());
    }

    @Test
    void theFormOffersTokenAndTheOwnReference() {
        GitSourceAdapter adapter = new GitSourceAdapter(clones);

        assertThat(adapter.authModes()).containsExactly(SourceType.AuthMode.NONE, SourceType.AuthMode.BASIC,
                SourceType.AuthMode.PAT);
        assertThat(adapter.fields()).extracting(SourceField::key).contains("url", "credentialRef");
    }

    @Test
    void aCredentialRefMayNotBorrowTheCredentialsOfAnotherSource() {
        GitSourceAdapter adapter = new GitSourceAdapter(clones);

        assertThat(adapter.check(config(Map.of("url", "https://git.example.org/team/repo.git",
                "credentialRef", "source:wiki"))))
                .containsExactly("options.credentialRef darf nicht auf source:… zeigen; dafür gibt es auth");
    }

    private static SourceSettingsConfig config(Auth auth, Map<String, String> options) {
        return new SourceSettingsConfig("repo", GitSourceAdapter.TYPE, null, null, auth, options);
    }

    private static SourceSettingsConfig config(Map<String, String> options) {
        return new SourceSettingsConfig("repo", GitSourceAdapter.TYPE, null, null, null, options);
    }
}
