package com.aresstack.spaceharvester.source.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.TrustBundles;
import com.aresstack.spaceharvester.source.tls.HttpsFakeConfluenceServer;
import com.aresstack.spaceharvester.source.tls.TestCertificates;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Vertrauen ohne eigenes {@code trust-bundle} (#189): JVM-Truststore, Windows-Speicher und eine CA-Datei wie
 * {@code network.tls} im Enterprise AI Client.
 */
class TrustPolicyTest {

    @TempDir
    static Path dir;

    private static HttpsFakeConfluenceServer server;
    private static Path serverCertificate;

    @BeforeAll
    static void start() throws Exception {
        TestCertificates certificates = new TestCertificates(dir);
        serverCertificate = dir.resolve("server.crt");
        FakeConfluence confluence = new FakeConfluence();
        confluence.space("DEV", "Entwicklung");
        server = new HttpsFakeConfluenceServer(confluence, certificates.serverContext(), false);
    }

    @AfterAll
    static void stop() throws Exception {
        server.close();
    }

    private static HarvestSource source(TrustSettings trust, ConfiguredSourceCatalog.AsyncTransport transport) {
        TrustBundles bundles = TrustBundles.withDefault(TrustBundles.NONE, TrustPolicy.defaultTrust(trust));
        return ConfiguredSourceCatalog.of(List.of(new SourceSettingsConfig("wiki", "confluence", null,
                        server.baseUrl(), null, Map.of(), null, null)), "test", bundles, ProxySettings.disabled(),
                transport).find("wiki").orElseThrow();
    }

    @Test
    void aCaFileTrustsTheServerWithoutTrustBundle() {
        TrustSettings trust = new TrustSettings(true, false, false, serverCertificate);

        assertThat(source(trust, ConfiguredSourceCatalog.AsyncTransport.DISABLED).partitions())
                .extracting(partition -> partition.key()).containsExactly("DEV");
        assertThat(source(trust, ConfiguredSourceCatalog.AsyncTransport.WEB_CLIENT).partitions())
                .extracting(partition -> partition.key()).containsExactly("DEV");
    }

    @Test
    void withTheJvmTruststoreAloneTheSelfSignedServerIsRefused() {
        assertThat(TrustPolicy.defaultTrust(TrustSettings.jvm())).isNull();

        assertThatThrownBy(() -> source(TrustSettings.jvm(), ConfiguredSourceCatalog.AsyncTransport.DISABLED)
                .partitions()).isInstanceOf(RuntimeException.class);
    }

    @Test
    void windowsStoresStayOffOutsideWindowsAndTheJvmTruststoreRemains() {
        TrustPolicy policy = TrustPolicy.build(TrustSettings.system(), false);

        assertThat(policy.sources()).containsExactly(TrustPolicy.JVM_SOURCE);
        assertThat(policy.notices()).anySatisfy(notice -> assertThat(notice).contains("nur unter Windows"));
        assertThat(policy.trustManager().getAcceptedIssuers()).isNotEmpty();
    }

    @Test
    void onlyTheCaFileTrustsOnlyItsCertificates() {
        TrustPolicy policy = TrustPolicy.build(new TrustSettings(false, false, false, serverCertificate), false);

        assertThat(policy.sources()).containsExactly(TrustPolicy.FILE_SOURCE + " (1 Zertifikate)");
        assertThat(policy.trustManager().getAcceptedIssuers()).hasSize(1);
    }

    @Test
    void problemsNameTheFieldNeverTheContent() throws Exception {
        Path garbage = Files.writeString(dir.resolve("kein-zertifikat.pem"), "geheim-123", StandardCharsets.UTF_8);

        assertThat(TrustPolicy.problems(new TrustSettings(true, false, false, garbage)))
                .singleElement().satisfies(problem -> assertThat(problem).startsWith("tls.ca-certificates-file")
                        .doesNotContain("geheim-123"));
        assertThat(TrustPolicy.problems(new TrustSettings(true, false, false, dir.resolve("fehlt.pem"))))
                .singleElement().satisfies(problem -> assertThat(problem).contains("nicht lesbar"));
        assertThat(TrustPolicy.problems(new TrustSettings(false, false, false, null)))
                .containsExactly("tls: mindestens eine Vertrauensquelle einschalten");
        assertThat(TrustPolicy.problems(TrustSettings.system())).isEmpty();
        assertThat(new TrustSettings(true, true, false, serverCertificate).describe())
                .isEqualTo("JVM-Truststore, Windows-ROOT, CA-Datei server.crt");
    }
}
