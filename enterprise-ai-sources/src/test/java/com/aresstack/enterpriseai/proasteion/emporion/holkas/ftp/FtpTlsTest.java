package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.source.api.SourceSettingsReader;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** FTPS (explizit und implizit) für FTP- und JES-Sitzungen gegen {@link FakeFtpsServer}, dazu die Einstellungen. */
public class FtpTlsTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private FakeFtpsServer server;

    @After
    public void stop() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void explicitFtpsNegotiatesAuthTlsThenProtectsTheDataConnection() throws Exception {
        server = new FakeFtpsServer(true, false);
        server.files.put("/doku/a.txt", "Hallo FTPS\n");

        CommonsNetFtpSession session = CommonsNetFtpSession.open(settings("explicit", trusted()), "indexer",
                "secret".toCharArray());
        try {
            assertEquals("Hallo FTPS", session.readText("/doku/a.txt").trim());
        } finally {
            session.close();
        }

        List<String> commands = server.commands;
        assertEquals("AUTH TLS", commands.get(0));
        assertTrue(commands.toString(), commands.indexOf("PBSZ 0") > commands.indexOf("PASS"));
        assertEquals(commands.indexOf("PBSZ 0") + 1, commands.indexOf("PROT P"));
        assertEquals(Arrays.asList(true), server.protectedData);
    }

    @Test
    public void implicitFtpsSpeaksTlsFromTheFirstByte() throws Exception {
        server = new FakeFtpsServer(true, true);
        server.files.put("/doku/a.txt", "implizit");

        CommonsNetFtpSession session = CommonsNetFtpSession.open(settings("implicit", trusted()), "indexer",
                "secret".toCharArray());
        try {
            assertEquals("implizit", session.readText("/doku/a.txt").trim());
        } finally {
            session.close();
        }

        assertFalse("kein AUTH bei implizitem FTPS", server.commands.contains("AUTH TLS"));
        assertTrue(server.commands.contains("PROT P"));
        assertEquals(Arrays.asList(true), server.protectedData);
    }

    @Test
    public void jesSessionsUseTheSameProtection() throws Exception {
        server = new FakeFtpsServer(true, false);

        JesFtpSession.open(settings("explicit", trusted()), "indexer", "secret".toCharArray()).close();

        List<String> commands = server.commands;
        assertEquals("AUTH TLS", commands.get(0));
        assertTrue(commands.toString(), commands.indexOf("PROT P") < commands.indexOf("SITE FILETYPE=JES"));
    }

    @Test
    public void withoutTheTlsOptionNothingChanges() throws Exception {
        server = new FakeFtpsServer(true, false);
        server.files.put("/doku/a.txt", "klar");

        CommonsNetFtpSession session = CommonsNetFtpSession.open(settings(null, null), "indexer",
                "secret".toCharArray());
        try {
            assertEquals("klar", session.readText("/doku/a.txt").trim());
        } finally {
            session.close();
        }

        for (String command : server.commands) {
            assertFalse(command, command.startsWith("AUTH") || command.startsWith("PBSZ")
                    || command.startsWith("PROT"));
        }
        assertEquals(Arrays.asList(false), server.protectedData);
    }

    @Test
    public void anUntrustedCertificateFailsWithoutHostOrCredentialsInTheMessage() throws Exception {
        server = new FakeFtpsServer(true, false);

        try {
            CommonsNetFtpSession.open(settings("explicit", resource("other-ca.pem")), "indexer",
                    "secret".toCharArray());
            fail("fremde CA");
        } catch (IOException e) {
            String message = String.valueOf(e.getMessage());
            assertTrue(message, message.startsWith("TLS-Verbindung fehlgeschlagen"));
            assertFalse(message, message.contains("localhost") || message.contains("127.0.0.1")
                    || message.contains("indexer") || message.contains("secret"));
        }
        assertFalse("keine Anmeldung über eine nicht vertraute Verbindung", server.commands.contains("PASS"));
    }

    @Test
    public void aServerWithoutFtpsIsRefusedInsteadOfFallingBackToPlainText() throws Exception {
        server = new FakeFtpsServer(false, false);

        try {
            CommonsNetFtpSession.open(settings("explicit", trusted()), "indexer", "secret".toCharArray());
            fail("kein FTPS");
        } catch (IOException expected) {
            // erwartet
        }
        assertFalse(server.commands.contains("USER indexer"));
        assertFalse(server.commands.contains("PASS"));
    }

    @Test
    public void settingsReadTlsPortAndTrustBundle() throws Exception {
        FtpConnectionSettings plain = FtpSourceProvider.connection(reader(map("host", "mvs.example")));
        assertEquals(FtpConnectionSettings.Tls.NONE, plain.tls);
        assertEquals(21, plain.port);
        assertNull(plain.trustBundle);

        Path bundle = trusted();
        FtpConnectionSettings implicit = FtpSourceProvider.connection(reader(map("host", "mvs.example",
                "tls", "implicit", "trustBundle", bundle.toString())));
        assertEquals(FtpConnectionSettings.Tls.IMPLICIT, implicit.tls);
        assertEquals("Standardport für implizites FTPS", 990, implicit.port);
        assertEquals(bundle, implicit.trustBundle);

        assertEquals(FtpConnectionSettings.Tls.EXPLICIT, FtpSourceProvider.connection(reader(map("host",
                "mvs.example", "tls", "explicit", "port", "2121"))).tls);
    }

    @Test
    public void invalidTlsSettingsAreReported() throws Exception {
        FtpSourceProvider provider = new FtpSourceProvider(ref -> {
            throw new AssertionError("keine Zugangsdaten beim Prüfen");
        });
        Path empty = temp.newFile("leer.pem").toPath();

        assertProblem(provider, "tls", map("host", "h", "startPoints", "A.B", "tls", "starttls"));
        assertProblem(provider, "trustBundle", map("host", "h", "startPoints", "A.B",
                "trustBundle", trusted().toString()));
        assertProblem(provider, "trustBundle", map("host", "h", "startPoints", "A.B", "tls", "explicit",
                "trustBundle", empty.toString()));
        assertProblem(provider, "trustBundle", map("host", "h", "startPoints", "A.B", "tls", "explicit",
                "trustBundle", temp.getRoot().toPath().resolve("fehlt.pem").toString()));
        assertTrue(provider.validate(SourceSettings.of(map("host", "h", "startPoints", "A.B", "tls", "explicit",
                "trustBundle", trusted().toString()))).isEmpty());
        assertEquals(Arrays.asList(), new JesSourceProvider(ref -> null).validate(SourceSettings.of(map("host", "h",
                "credentialRef", "keepass:jes", "tls", "implicit"))));
    }

    private FtpConnectionSettings settings(String tls, Path bundle) {
        FtpConnectionSettings.Tls mode = tls == null ? FtpConnectionSettings.Tls.NONE
                : FtpConnectionSettings.Tls.valueOf(tls.toUpperCase());
        return new FtpConnectionSettings("localhost", server.port(), null, StandardCharsets.ISO_8859_1, 5000, 5000,
                FtpConnectionSettings.RecordStructure.AUTO, mode, bundle);
    }

    private Path trusted() throws IOException {
        return resource("localhost-cert.pem");
    }

    private Path resource(String name) throws IOException {
        Path file = temp.getRoot().toPath().resolve(name);
        try (InputStream in = FtpTlsTest.class.getResourceAsStream(name)) {
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }

    private static void assertProblem(FtpSourceProvider provider, String key, Map<String, String> values) {
        List<String> problems = provider.validate(SourceSettings.of(values));
        assertEquals(problems.toString(), 1, problems.size());
        assertTrue(problems.toString(), problems.get(0).startsWith(key));
    }

    private static SourceSettingsReader reader(Map<String, String> values) {
        return new SourceSettingsReader(FtpSourceProvider.sourceType(), SourceSettings.of(values));
    }

    private static Map<String, String> map(String... keyValues) {
        Map<String, String> values = new HashMap<String, String>();
        for (int i = 0; i < keyValues.length; i += 2) {
            values.put(keyValues[i], keyValues[i + 1]);
        }
        return values;
    }
}
