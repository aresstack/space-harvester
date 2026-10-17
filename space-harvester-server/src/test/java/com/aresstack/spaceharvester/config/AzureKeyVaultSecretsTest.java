package com.aresstack.spaceharvester.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.SpaceHarvesterApplication;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.source.tls.TestCertificates;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Azure Key Vault als optionale PropertySource (#22) gegen einen HTTPS-Ersatz des Vaults: Mit Profil
 * {@code azure-keyvault} löst Spring Cloud Azure Platzhalter aus dem Vault auf, und der Wert kommt beim Connector an;
 * ohne Profil gibt es keinen Kontakt zum Vault. Fehlende Secrets und ein nicht erreichbarer Vault verhindern den Start
 * mit einer Meldung ohne Secret-Werte.
 */
class AzureKeyVaultSecretsTest {

    private static final String VAULT_TOKEN = "kv-token-4711";
    private static final String OTHER_SECRET = "anderes-geheimnis-0815";
    private static final String[] TRUST_PROPERTIES = {
            "javax.net.ssl.trustStore", "javax.net.ssl.trustStoreType", "javax.net.ssl.trustStorePassword"};

    /** Einmal je Klasse: Der Standard-Truststore der JVM wird beim ersten TLS-Aufruf zwischengespeichert. */
    @TempDir
    static Path dir;
    private static TestCertificates certificates;

    private final FakeConfluence confluence = new FakeConfluence();
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final String[] savedTrust = new String[TRUST_PROPERTIES.length];
    private FakeKeyVault vault;
    private FakeConfluenceServer server;

    @BeforeAll
    static void createCertificates() throws Exception {
        certificates = new TestCertificates(dir);
    }

    @BeforeEach
    void start() throws Exception {
        // Das Azure SDK vertraut dem JVM-Truststore; für den Test-Vault vorübergehend dessen Zertifikat.
        for (int i = 0; i < TRUST_PROPERTIES.length; i++) {
            savedTrust[i] = System.getProperty(TRUST_PROPERTIES[i]);
        }
        System.setProperty("javax.net.ssl.trustStore", certificates.harvesterTrust.toString());
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
        System.setProperty("javax.net.ssl.trustStorePassword", TestCertificates.STORE_PASSWORD);
        vault = new FakeKeyVault(certificates.serverContext())
                .secret("confluence-api-token", VAULT_TOKEN)
                .secret("anderes-secret", OTHER_SECRET);
        resources.add(vault);
        confluence.space("DEV", "Entwicklung");
        server = new FakeConfluenceServer(confluence);
        resources.add(server);
    }

    @AfterEach
    void stop() throws Exception {
        for (AutoCloseable resource : resources) {
            resource.close();
        }
        for (int i = 0; i < TRUST_PROPERTIES.length; i++) {
            if (savedTrust[i] == null) {
                System.clearProperty(TRUST_PROPERTIES[i]);
            } else {
                System.setProperty(TRUST_PROPERTIES[i], savedTrust[i]);
            }
        }
    }

    @Test
    void placeholderIsResolvedFromKeyVaultAndReachesTheSource() throws Exception {
        try (ConfigurableApplicationContext context = run(true, vault.endpoint().toString(),
                "${confluence-api-token}")) {
            assertThat(spacesAuthorization(context)).isEqualTo("Bearer " + VAULT_TOKEN);
        }
        assertThat(vault.requests()).contains("GET /secrets/confluence-api-token");
    }

    @Test
    void withoutProfileTheVaultIsNeverContacted() throws Exception {
        try (ConfigurableApplicationContext context = run(false, vault.endpoint().toString(), "yaml-token")) {
            assertThat(spacesAuthorization(context)).isEqualTo("Bearer yaml-token");
        }
        assertThat(vault.requests()).isEmpty();
    }

    @Test
    void literalValueIsKeptWhenTheVaultIsActive() throws Exception {
        try (ConfigurableApplicationContext context = run(true, vault.endpoint().toString(), "yaml-token")) {
            assertThat(spacesAuthorization(context)).isEqualTo("Bearer yaml-token");
        }
    }

    @Test
    void missingSecretPreventsStartWithoutLeakingValues() {
        assertThatThrownBy(() -> run(true, vault.endpoint().toString(), "${fehlendes-secret}").close())
                .rootCause()
                .hasMessageContaining("auth.token: Platzhalter ${fehlendes-secret} hat keinen Wert")
                .hasMessageNotContaining(VAULT_TOKEN)
                .hasMessageNotContaining(OTHER_SECRET);
    }

    @Test
    void unreachableVaultPreventsStartWhenActive() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            closedPort = socket.getLocalPort();
        }
        assertThatThrownBy(() -> run(true, "https://127.0.0.1:" + closedPort + "/", "${confluence-api-token}")
                .close())
                .hasStackTraceContaining("127.0.0.1:" + closedPort)
                .satisfies(e -> assertThat(stackTrace(e)).doesNotContain(VAULT_TOKEN, OTHER_SECRET));
    }

    @Test
    void unreachableVaultIsIgnoredWithoutProfile() throws Exception {
        try (ConfigurableApplicationContext context = run(false, "https://127.0.0.1:1/", "yaml-token")) {
            assertThat(spacesAuthorization(context)).isEqualTo("Bearer yaml-token");
        }
    }

    private ConfigurableApplicationContext run(boolean keyVaultProfile, String endpoint, String token) {
        List<String> args = new ArrayList<>(List.of("--server.port=0",
                "--AZURE_KEYVAULT_ENDPOINT=" + endpoint,
                // Der Test-Vault läuft nicht unter *.vault.azure.net und verlangt keine Anmeldung.
                // Die Kommandozeile ersetzt die Liste aus dem Profil ganz; Name und Endpunkt daher wie dort.
                "--spring.cloud.azure.keyvault.secret.property-sources[0].name=azure-key-vault",
                "--spring.cloud.azure.keyvault.secret.property-sources[0].endpoint=${AZURE_KEYVAULT_ENDPOINT}",
                "--spring.cloud.azure.keyvault.secret.property-sources[0].challenge-resource-verification-enabled=false",
                "--spring.cloud.azure.keyvault.secret.property-sources[0].retry.exponential.max-retries=0",
                "--space-harvester.sources[0].id=wiki",
                "--space-harvester.sources[0].type=confluence",
                "--space-harvester.sources[0].base-url=" + server.baseUrl(),
                "--space-harvester.sources[0].options.allowInsecureHttp=true",
                "--space-harvester.sources[0].auth.type=pat",
                "--space-harvester.sources[0].auth.token=" + token));
        if (keyVaultProfile) {
            args.add("--spring.profiles.active=azure-keyvault");
        }
        return new SpringApplicationBuilder(SpaceHarvesterApplication.class).run(args.toArray(String[]::new));
    }

    private String spacesAuthorization(ConfigurableApplicationContext context) {
        context.getBean(SourceQueries.class).partitions("wiki");
        List<Map<String, String>> headers = confluence.requestHeaders();
        return headers.get(headers.size() - 1).entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase("Authorization"))
                .map(Map.Entry::getValue).findFirst().orElse("");
    }

    private static String stackTrace(Throwable e) {
        java.io.StringWriter out = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(out));
        return out.toString();
    }
}
