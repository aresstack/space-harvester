package com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc;

import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.adyton.SecretMaterial;
import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Optionaler Integrationstest gegen ein echtes KeePass 2.x mit KeePassRPC-Plugin. Standardmäßig übersprungen.
 *
 * <pre>
 * ./gradlew :security-keepassrpc:test --tests '*KeePassRpcRealServerIT' \
 *     -Dkeepassrpc.it=true -Dkeepassrpc.it.entry="Titel eines Testeintrags" \
 *     [-Dkeepassrpc.it.keyFile=/pfad/zum/pairing-key] [-Dkeepassrpc.it.port=12546] [-Dkeepassrpc.it.host=127.0.0.1]
 * </pre>
 *
 * Ohne {@code keyFile} fragt der Test das Einmal-Passwort, das KeePass beim Pairing anzeigt, auf der Konsole ab
 * (Gradle mit {@code --console=plain} und ohne Daemon starten); mit {@code keyFile} wird der dort gespeicherte
 * Schlüssel verwendet bzw. nach einem neuen Pairing dorthin geschrieben. Ausgegeben werden nur Titel und ob
 * Benutzername und Passwort nicht leer sind, nie die Werte.
 */
public class KeePassRpcRealServerIT {

    @Test
    public void readsAnEntryFromARealKeePass() throws Exception {
        Assume.assumeTrue("KeePassRPC-Integrationstest nicht aktiviert (-Dkeepassrpc.it=true)",
                Boolean.getBoolean("keepassrpc.it"));
        String entry = System.getProperty("keepassrpc.it.entry");
        Assume.assumeTrue("-Dkeepassrpc.it.entry fehlt", entry != null && !entry.trim().isEmpty());

        KeePassRpcConfig.Builder config = KeePassRpcConfig.builder();
        if (System.getProperty("keepassrpc.it.host") != null) {
            config.host(System.getProperty("keepassrpc.it.host"));
        }
        if (System.getProperty("keepassrpc.it.port") != null) {
            config.port(Integer.parseInt(System.getProperty("keepassrpc.it.port")));
        }
        if (System.getProperty("keepassrpc.it.origin") != null) {
            config.origin(System.getProperty("keepassrpc.it.origin"));
        }
        KeePassPairingKeyStore store = System.getProperty("keepassrpc.it.keyFile") == null
                ? new InMemoryPairingKeyStore()
                : new FileKeyStore(Paths.get(System.getProperty("keepassrpc.it.keyFile")));
        KeePassRpcSecretProvider provider = new KeePassRpcSecretProvider(config.build(), store, name -> {
            System.out.println("KeePass zeigt jetzt ein Pairing-Passwort für '" + name + "'. Bitte eingeben:");
            Scanner scanner = new Scanner(System.in, "UTF-8");
            return scanner.hasNextLine() ? scanner.nextLine().trim().toCharArray() : null;
        });

        try (SecretMaterial material = provider.resolve(SecretRef.of("keepass:" + entry))) {
            char[] secret = material.copySecret();
            System.out.println("Eintrag '" + entry + "': Benutzername vorhanden=" + material.hasPrincipal()
                    + ", Passwort vorhanden=" + (secret.length > 0));
            assertTrue(material.hasPrincipal() || secret.length > 0);
            java.util.Arrays.fill(secret, '\0');
            assertFalse(material.toString().contains(entry + "="));
        }
    }

    /** Nur für den manuellen Integrationstest: Schlüssel im Klartext in einer lokalen Datei. */
    private static final class FileKeyStore implements KeePassPairingKeyStore {

        private final Path file;

        FileKeyStore(Path file) {
            this.file = file;
        }

        @Override
        public char[] load() {
            try {
                return Files.exists(file)
                        ? new String(Files.readAllBytes(file), StandardCharsets.US_ASCII).trim().toCharArray()
                        : null;
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public void save(char[] sessionKey) {
            try {
                Files.write(file, new String(sessionKey).getBytes(StandardCharsets.US_ASCII));
            } catch (IOException e) {
                throw new IllegalStateException("Schlüsseldatei nicht schreibbar: " + file, e);
            }
        }

        @Override
        public void clear() {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // Testhilfe
            }
        }
    }
}
