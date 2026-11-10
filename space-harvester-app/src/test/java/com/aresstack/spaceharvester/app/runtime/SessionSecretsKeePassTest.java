package com.aresstack.spaceharvester.app.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionSecretsKeePassTest {

    private static final SourceSettingsConfig SOURCE = new SourceSettingsConfig("wiki", "confluence", null,
            URI.create("https://confluence.example.org"),
            new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.PAT, null, null, null), Map.of());

    @Test
    void aKeePassEntryWithoutLoginSendsNoAuthorizationLikeTheEnterpriseAiClient() {
        // #571: EAC VaultEntryOptions.sendsLogin – MM_RequiresLogin=false heißt nur Zertifikat, kein Authorization-Header.
        AppRuntime.SessionSecrets secrets = new AppRuntime.SessionSecrets();
        secrets.put("wiki", "pat|", "geheim".toCharArray());

        assertThat(secrets.apply(SOURCE).auth().type()).isEqualTo(SourceSettingsConfig.AuthType.PAT);

        secrets.keePassFields("wiki", Map.of("MM_RequiresLogin", "false", "MM_CertAlias", "Karte"));
        assertThat(secrets.withoutLogin("wiki")).isTrue();
        assertThat(secrets.apply(SOURCE).auth()).isEqualTo(SourceSettingsConfig.Auth.anonymous());

        secrets.keePassFields("wiki", Map.of("MM_RequiresLogin", "true"));
        assertThat(secrets.apply(SOURCE).auth().token()).isEqualTo("geheim");
    }
}
