package com.aresstack.spaceharvester.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.CertificateStoreType;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Die YAML-Schreibweise aus README/docs/MTLS.md bindet an die Quelle. */
class ClientCertificatePropertiesTest {

    private static SourceSettingsConfig bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("space-harvester", SpaceHarvesterProperties.class).get().sources().get(0).toSettings();
    }

    @Test
    void pkcs12WithTrustBundle() {
        SourceSettingsConfig source = bind(Map.of(
                "space-harvester.sources[0].id", "confluence",
                "space-harvester.sources[0].client-certificate.enabled", "true",
                "space-harvester.sources[0].client-certificate.type", "pkcs12",
                "space-harvester.sources[0].client-certificate.path", "/run/secrets/client.p12",
                "space-harvester.sources[0].client-certificate.alias", "confluence-client",
                "space-harvester.sources[0].client-certificate.password", "geheim",
                "space-harvester.sources[0].trust-bundle", "confluence-ca"));

        assertThat(source.clientCertificate().enabled()).isTrue();
        assertThat(source.clientCertificate().type()).isEqualTo(CertificateStoreType.PKCS12);
        assertThat(source.clientCertificate().path()).isEqualTo(Path.of("/run/secrets/client.p12"));
        assertThat(source.trustBundle()).isEqualTo("confluence-ca");
        assertThat(source.toString()).doesNotContain("geheim");
    }

    @Test
    void windowsStoreAndMissingCertificate() {
        SourceSettingsConfig windows = bind(Map.of(
                "space-harvester.sources[0].client-certificate.enabled", "true",
                "space-harvester.sources[0].client-certificate.type", "windows-my",
                "space-harvester.sources[0].client-certificate.alias", "confluence-client"));
        SourceSettingsConfig plain = bind(Map.of("space-harvester.sources[0].id", "wiki"));

        assertThat(windows.clientCertificate().type()).isEqualTo(CertificateStoreType.WINDOWS_MY);
        assertThat(plain.clientCertificate().enabled()).isFalse();
        assertThat(plain.trustBundle()).isNull();
    }
}
