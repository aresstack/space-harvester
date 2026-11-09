package com.aresstack.spaceharvester.app.credentials;

import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.adyton.SecretVault;
import com.aresstack.enterpriseai.adyton.VaultEntry;
import com.aresstack.enterpriseai.adyton.VaultEntryDraft;
import com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.KeePassPairingCallback;
import com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.KeePassPairingKeyStore;
import com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.KeePassRpcConfig;
import com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.KeePassRpcSecretProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Die KeePass-Anbindung der App über KeePassRPC aus dem Enterprise AI Client: koppeln (SRP-Pairing, KeePass zeigt ein
 * Einmal-Passwort), Einträge auflisten, lesen und neu anlegen. Der Pairing-Schlüssel liegt wie im EAC in einer Datei
 * nur für den Besitzer ({@link FilePairingKeyStore}), nicht im DPAPI-Tresor. Alles hier blockiert, also nur im
 * Hintergrund aufrufen.
 */
public final class KeePassLink {

    /** Dateiname des Pairing-Schlüssels neben der Konfigurationsdatei. */
    public static final String PAIRING_FILE = "keepass-pairing.key";
    /** Wie der Client in KeePass heißt; KeePassRPC verlangt einen {@code chrome-extension://}-Origin. */
    static final KeePassRpcConfig CONFIG = KeePassRpcConfig.builder()
            .origin("chrome-extension://spaceharvester")
            .clientId("SpaceHarvester")
            .clientDisplayName("Space Harvester")
            .build();

    private final SecretVault interactive;
    private final SecretVault silent;
    private final Supplier<Boolean> pairedCheck;

    /**
     * @param pairingFile  Datei für den Pairing-Schlüssel
     * @param prompt       fragt beim Koppeln nach dem Einmal-Passwort, das KeePass anzeigt ({@code null}: abgebrochen)
     */
    public KeePassLink(java.nio.file.Path pairingFile, KeePassPairingCallback prompt) {
        SessionKeyStore keys = new SessionKeyStore(new FilePairingKeyStore(pairingFile));
        this.pairedCheck = () -> keys.held() || java.nio.file.Files.isRegularFile(pairingFile);
        this.interactive = new KeePassRpcSecretProvider(CONFIG, keys, prompt);
        // Beim Start und beim Lesen des Keys nie ungefragt einen Kopplungsdialog öffnen.
        this.silent = new KeePassRpcSecretProvider(CONFIG, keys, KeePassPairingCallback.unavailable());
    }

    /** Für Tests: ein beliebiger Passwortspeicher statt KeePassRPC. */
    public KeePassLink(SecretVault vault, Supplier<Boolean> paired) {
        this.interactive = vault;
        this.silent = vault;
        this.pairedCheck = paired;
    }

    /**
     * Ob ein Pairing-Schlüssel gespeichert ist (ohne ihn zu entschlüsseln, also auch auf dem EDT); ob KeePass gerade
     * läuft, sagt erst {@link #titles()}.
     */
    public boolean paired() {
        return Boolean.TRUE.equals(pairedCheck.get());
    }

    /** Koppelt bei Bedarf (mit Dialog) und liefert die Titel aller Einträge, sortiert wie KeePass sie liefert. */
    public List<String> titles() throws SecretUnavailableException {
        List<String> titles = new ArrayList<>();
        for (VaultEntry entry : interactive.list()) {
            if (entry.title() != null && !entry.title().isBlank() && !titles.contains(entry.title())) {
                titles.add(entry.title());
            }
        }
        return titles;
    }

    /** Liest einen Eintrag (Adresse und Passwort); der Aufrufer schließt ihn. */
    public VaultEntryDraft read(String title) throws SecretUnavailableException {
        return interactive.read(title);
    }

    /** Legt einen neuen Eintrag an; einen vorhandenen mit diesem Titel lässt KeePass unverändert (ALREADY_EXISTS). */
    public void create(String title, String baseUrl, char[] apiKey) throws SecretUnavailableException {
        try (VaultEntryDraft draft = new VaultEntryDraft(title, "", apiKey, baseUrl, Map.of())) {
            interactive.create(draft);
        }
    }

    /** Die Zugangsdatenquelle zu einem Eintrag, ohne Kopplungsdialog. */
    public AiCredentials credentials(String entry) {
        return new KeePassAiCredentials(silent, entry);
    }

    /**
     * Hält den Pairing-Schlüssel zusätzlich für die laufende Sitzung im Speicher, gemeinsam für beide Provider: Scheitert
     * das Schreiben der Datei, gilt die Kopplung trotzdem bis zum Beenden der App (so wie der EAC es verspricht). Der
     * Provider überschreibt geladene Schlüssel nach Gebrauch, darum gehen nur Kopien hinaus und hinein.
     */
    static final class SessionKeyStore implements KeePassPairingKeyStore {

        private final KeePassPairingKeyStore file;
        private char[] memory;

        SessionKeyStore(KeePassPairingKeyStore file) {
            this.file = file;
        }

        synchronized boolean held() {
            return memory != null;
        }

        @Override
        public synchronized char[] load() {
            if (memory == null) {
                char[] stored = file.load();
                if (stored == null || stored.length == 0) {
                    return null;
                }
                memory = stored;
            }
            return Arrays.copyOf(memory, memory.length);
        }

        @Override
        public synchronized void save(char[] sessionKey) {
            wipe();
            if (sessionKey == null || sessionKey.length == 0) {
                file.clear();
                return;
            }
            memory = Arrays.copyOf(sessionKey, sessionKey.length);
            file.save(Arrays.copyOf(sessionKey, sessionKey.length));
        }

        @Override
        public synchronized void clear() {
            wipe();
            file.clear();
        }

        private void wipe() {
            if (memory != null) {
                Arrays.fill(memory, '\0');
                memory = null;
            }
        }
    }
}
