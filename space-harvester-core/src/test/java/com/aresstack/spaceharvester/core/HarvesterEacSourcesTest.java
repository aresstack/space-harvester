package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceField;
import com.aresstack.spaceharvester.application.sources.SourceType;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.InvalidSourceConfigurationException;
import com.aresstack.spaceharvester.source.SourceSecrets;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.Auth;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.AuthType;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.CertificateStoreType;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.ClientCertificate;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Die Quellen des Enterprise AI Client ohne eigenen Ordner (#187) sind als Typen {@code ftp}, {@code jes}, {@code ndv}
 * {@code sharepoint}, {@code betaview} und {@code outlook} registriert: gültige Einstellungen öffnen je eine Partition ohne Verbindung, die Prüfung des
 * Upstream-Typs meldet ihre Probleme beim Start. Anmeldung über {@code auth} oder den Anbieter des Programms (#188).
 */
class HarvesterEacSourcesTest {

    @TempDir
    Path data;

    @Test
    void theCopiedSourceTypesAreKnownWithoutOwnRegistration() {
        assertThat(ConfiguredSourceCatalog.TYPES).contains("ftp", "jes", "ndv", "sharepoint", "betaview", "outlook");
    }

    @Test
    void theHarvesterListsItsSourceTypesWithDisplayNames() {
        try (Harvester harvester = Harvester.builder().dataDirectory(data).build()) {
            assertThat(harvester.sourceTypes())
                    .extracting(SourceType::id, SourceType::displayName, SourceType::needsCredentials,
                            SourceType::addressOption)
                    .containsExactly(
                            tuple("confluence", "Confluence", false, null),
                            tuple("files", "Ordner", false, "directory"),
                            tuple("ftp", "FTP", false, "host"),
                            tuple("jes", "JES", true, "host"),
                            tuple("ndv", "NDV", true, "host"),
                            tuple("sharepoint", "SharePoint", false, "siteUrl"),
                            tuple("betaview", "BetaView", true, "baseUrl"),
                            tuple("outlook", "Outlook", false, "mailbox"),
                            tuple("mediawiki", "MediaWiki", false, "apiUrl"),
                            tuple("git", "Git-Repository", false, "url"));
        }
    }

    @Test
    void eachTypeDescribesItsFieldsAndAuthModesWithoutValues() {
        try (Harvester harvester = Harvester.builder().dataDirectory(data).build()) {
            Map<String, SourceType> types = new java.util.HashMap<>();
            harvester.sourceTypes().forEach(type -> types.put(type.id(), type));

            assertThat(types.get("ftp").fields()).extracting(SourceField::key).contains("host", "port", "startPoints");
            assertThat(types.get("ftp").fields()).filteredOn(field -> field.key().equals("host")).singleElement()
                    .satisfies(field -> assertThat(field.required()).isTrue())
                    .satisfies(field -> assertThat(field.kind()).isEqualTo(SourceField.Kind.TEXT));
            assertThat(types.get("jes").fields()).filteredOn(field -> field.key().equals("credentialRef"))
                    .singleElement()
                    .satisfies(field -> assertThat(field.kind()).isEqualTo(SourceField.Kind.SECRET_REF));
            assertThat(types.get("outlook").fields()).filteredOn(field -> field.key().equals("mailbox"))
                    .singleElement().satisfies(field -> assertThat(field.kind()).isEqualTo(SourceField.Kind.DIRECTORY));
            assertThat(types.get("files").fields()).extracting(SourceField::key).contains("directory")
                    .doesNotContain("title");
            // Titel, Basis-URL, Anmeldung und Zertifikat des Upstream-Typs stehen bei Confluence außerhalb von
            // options.
            assertThat(types.get("confluence").fields()).isEmpty();

            assertThat(types.get("confluence").authModes()).containsExactly(SourceType.AuthMode.NONE,
                    SourceType.AuthMode.BASIC, SourceType.AuthMode.PAT);
            assertThat(types.get("ftp").authModes()).containsExactly(SourceType.AuthMode.NONE,
                    SourceType.AuthMode.BASIC);
            assertThat(types.get("files").authModes()).containsExactly(SourceType.AuthMode.NONE);
            assertThat(types.get("outlook").authModes()).containsExactly(SourceType.AuthMode.NONE);
        }
    }

    @Test
    void eachTypeOpensOnePartitionWithoutConnecting() {
        try (Harvester harvester = Harvester.builder()
                .source(new SourceSettingsConfig("host-ftp", "ftp", "FTP", null, null,
                        Map.of("host", "mvs.example.org", "startPoints", "HLQ.DATA")))
                .source(new SourceSettingsConfig("host-jes", "jes", "JES", null, null,
                        Map.of("host", "mvs.example.org", "credentialRef", "keepass:jes")))
                .source(new SourceSettingsConfig("natural", "ndv", "NDV", null, null,
                        Map.of("host", "ndv.example.org", "credentialRef", "keepass:ndv", "startPoints", "MYLIB")))
                .source(new SourceSettingsConfig("team", "sharepoint", "Team", null, null,
                        Map.of("siteUrl", "https://sharepoint.example.org/sites/team")))
                .source(new SourceSettingsConfig("reports", "betaview", "Reports", null, null,
                        Map.of("baseUrl", "https://betaview.example.org/betaview/", "credentialRef", "keepass:bv",
                                "favoriteId", "F1")))
                .source(new SourceSettingsConfig("mail", "outlook", "Mail", null, null,
                        Map.of("mailbox", data.toString())))
                .dataDirectory(data).build()) {
            for (String id : new String[] {"host-ftp", "host-jes", "natural", "team", "reports", "mail"}) {
                assertThat(harvester.sources().partitions(id)).extracting(Partition::key).containsExactly(id);
            }
        }
    }

    @Test
    void theUpstreamValidationRejectsIncompleteSettings() {
        SourceSettingsConfig jes = new SourceSettingsConfig("host-jes", "jes", "JES", null, null,
                Map.of("host", "mvs.example.org"));
        assertThatThrownBy(() -> Harvester.builder().source(jes).dataDirectory(data).build())
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .satisfies(e -> assertThat(((InvalidSourceConfigurationException) e).problems())
                        .anySatisfy(problem -> assertThat(problem).startsWith("sources[0] (id=host-jes)")
                                .contains("credentialRef")));
    }

    @Test
    void basicAuthGoesToTheFtpLoginWithoutAppearingInOptions() throws Exception {
        try (FakeFtp ftp = new FakeFtp()) {
            SourceSettingsConfig config = new SourceSettingsConfig("host-ftp", "ftp", "FTP", null,
                    new Auth(AuthType.BASIC, "TSOUSER", "geheim", null), ftp.options());
            assertThat(config.toString()).doesNotContain("geheim");
            try (Harvester harvester = Harvester.builder().source(config).dataDirectory(data).build()) {
                harvester.await(harvester.sync("host-ftp", "host-ftp").id(), Duration.ofSeconds(60));
            }
            assertThat(ftp.commands).contains("USER TSOUSER", "PASS geheim");
        }
    }

    @Test
    void withoutPasswordTheEmbeddersProviderIsAskedForTheSource() throws Exception {
        try (FakeFtp ftp = new FakeFtp()) {
            List<String> asked = new CopyOnWriteArrayList<>();
            try (Harvester harvester = Harvester.builder()
                    .source(new SourceSettingsConfig("host-jes", "jes", "JES", null,
                            new Auth(AuthType.BASIC, "TSOUSER", null, null), ftp.options()))
                    .secretProvider(ref -> {
                        asked.add(ref);
                        return Optional.of(new SourceSecrets.Credential("TSOUSER", "aus-dem-tresor".toCharArray()));
                    }).dataDirectory(data).build()) {
                harvester.await(harvester.sync("host-jes", "host-jes").id(), Duration.ofSeconds(60));
            }
            assertThat(asked).contains("source:host-jes");
            assertThat(ftp.commands).contains("USER TSOUSER", "PASS aus-dem-tresor");
        }
    }

    @Test
    void anOwnCredentialRefPassesThroughToTheEmbeddersProvider() throws Exception {
        try (FakeFtp ftp = new FakeFtp()) {
            Map<String, String> options = new java.util.HashMap<>(ftp.options());
            options.put("credentialRef", "keepass:mvs");
            List<String> asked = new CopyOnWriteArrayList<>();
            try (Harvester harvester = Harvester.builder()
                    .source(new SourceSettingsConfig("host-ftp", "ftp", "FTP", null, null, options))
                    .secretProvider(ref -> {
                        asked.add(ref);
                        return Optional.of(new SourceSecrets.Credential("KPUSER", "aus-keepass".toCharArray()));
                    }).dataDirectory(data).build()) {
                harvester.await(harvester.sync("host-ftp", "host-ftp").id(), Duration.ofSeconds(60));
            }
            assertThat(asked).contains("keepass:mvs");
            assertThat(ftp.commands).contains("USER KPUSER", "PASS aus-keepass");
        }
    }

    @Test
    void basicAuthSatisfiesTheRequiredCredentialRef() {
        try (Harvester harvester = Harvester.builder()
                .source(new SourceSettingsConfig("host-jes", "jes", "JES", null,
                        new Auth(AuthType.BASIC, "TSOUSER", "geheim", null), Map.of("host", "mvs.example.org")))
                .source(new SourceSettingsConfig("natural", "ndv", "NDV", null,
                        new Auth(AuthType.BASIC, "NATUSER", "geheim", null),
                        Map.of("host", "ndv.example.org", "startPoints", "MYLIB")))
                .dataDirectory(data).build()) {
            assertThat(harvester.sources().partitions("host-jes")).hasSize(1);
            assertThat(harvester.sources().partitions("natural")).hasSize(1);
        }
    }

    @Test
    void conflictingOrUnsupportedSettingsAreRejectedAtStart() {
        ClientCertificate certificate = new ClientCertificate(true, CertificateStoreType.PKCS12,
                data.resolve("client.p12"), "client", "pw", null);
        List<SourceSettingsConfig> configs = new ArrayList<>(List.of(
                new SourceSettingsConfig("both", "jes", "JES", null, new Auth(AuthType.BASIC, "U", "P", null),
                        Map.of("host", "mvs.example.org", "credentialRef", "keepass:jes")),
                new SourceSettingsConfig("mtls", "ftp", "FTP", null, null, Map.of("host", "mvs.example.org"),
                        certificate, null),
                new SourceSettingsConfig("trust", "ndv", "NDV", null, new Auth(AuthType.BASIC, "U", "P", null),
                        Map.of("host", "ndv.example.org"), null, "-----BEGIN CERTIFICATE-----"),
                new SourceSettingsConfig("url", "sharepoint", "Team", java.net.URI.create("https://x.example.org"),
                        null, Map.of("siteUrl", "https://sharepoint.example.org/sites/team")),
                new SourceSettingsConfig("borrow", "jes", "JES", null, null,
                        Map.of("host", "mvs.example.org", "credentialRef", "source:host-ftp"))));
        Harvester.Builder builder = Harvester.builder().dataDirectory(data);
        configs.forEach(builder::source);
        assertThatThrownBy(builder::build)
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .satisfies(e -> assertThat(((InvalidSourceConfigurationException) e).problems())
                        .contains("sources[0] (id=both): options.credentialRef passt nicht zu auth.type basic; es gilt auth",
                                "sources[1] (id=mtls): client-certificate passt nicht zu type ftp",
                                "sources[2] (id=trust): trust-bundle passt nicht zu type ndv",
                                "sources[3] (id=url): base-url passt nicht zu type sharepoint; die Adresse steht unter "
                                        + "options",
                                "sources[4] (id=borrow): options.credentialRef darf nicht auf source:… zeigen; dafür "
                                        + "gibt es auth"));
    }

    /** Antwortet wie ein FTP-Server bis zur Anmeldung und lehnt sie ab; merkt sich USER und PASS. */
    private static final class FakeFtp implements AutoCloseable {

        final List<String> commands = new CopyOnWriteArrayList<>();
        private final ServerSocket server;
        private final Thread acceptor;

        FakeFtp() throws Exception {
            server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
            acceptor = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        serve(socket);
                    } catch (Exception closed) {
                        // nächste Verbindung oder Ende
                    }
                }
            }, "fake-ftp");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        Map<String, String> options() {
            return Map.of("host", "127.0.0.1", "port", String.valueOf(server.getLocalPort()), "startPoints", "HLQ.DATA");
        }

        private void serve(Socket socket) throws Exception {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                    StandardCharsets.US_ASCII));
            send(out, "220 fake");
            String line;
            while ((line = in.readLine()) != null) {
                commands.add(line);
                if (line.startsWith("USER")) {
                    send(out, "331 password");
                } else if (line.startsWith("PASS")) {
                    send(out, "530 nein");
                } else if (line.startsWith("QUIT")) {
                    send(out, "221 bye");
                    return;
                } else {
                    send(out, "502 nicht da");
                }
            }
        }

        private static void send(OutputStream out, String reply) throws Exception {
            out.write((reply + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        @Override
        public void close() throws Exception {
            server.close();
            acceptor.join(5_000);
        }
    }
}
