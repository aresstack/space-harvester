package com.aresstack.spaceharvester.app.credentials;

import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.adyton.SecretVault;
import com.aresstack.enterpriseai.adyton.VaultEntry;
import com.aresstack.enterpriseai.adyton.VaultEntryDraft;
import com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.KeePassPairingCallback;
import com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.KeePassPairingKeyStore;
import com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.KeePassRpcConfig;
import com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.KeePassRpcSecretProvider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Die KeePass-Anbindung der App über KeePassRPC aus dem Enterprise AI Client: koppeln (SRP-Pairing, KeePass zeigt ein
 * Einmal-Passwort), Einträge auflisten, lesen und neu anlegen. Der Pairing-Schlüssel liegt im Tresor dieses
 * Windows-Kontos ({@link #PAIRING_VAULT}). Alles hier blockiert, also nur im Hintergrund aufrufen.
 */
public final class KeePassLink {

    /** Tresorbereich und Eintrag des Pairing-Schlüssels. */
    public static final String PAIRING_VAULT = "keepass";
    static final String PAIRING_ID = "pairing";
    static final String PAIRING_LOGIN = "keepassrpc";
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
     * @param pairingStore Tresorbereich für den Pairing-Schlüssel
     * @param prompt       fragt beim Koppeln nach dem Einmal-Passwort, das KeePass anzeigt ({@code null}: abgebrochen)
     */
    public KeePassLink(com.aresstack.spaceharvester.app.settings.SecretVault pairingStore,
                       KeePassPairingCallback prompt) {
        KeePassPairingKeyStore keys = new VaultKeyStore(pairingStore);
        this.pairedCheck = () -> pairingStore.has(PAIRING_ID, PAIRING_LOGIN);
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

    /** Der Pairing-Schlüssel im Tresor dieses Windows-Kontos; er erscheint nie in Logs. */
    private static final class VaultKeyStore implements KeePassPairingKeyStore {

        private final com.aresstack.spaceharvester.app.settings.SecretVault vault;

        VaultKeyStore(com.aresstack.spaceharvester.app.settings.SecretVault vault) {
            this.vault = vault;
        }

        @Override
        public char[] load() {
            try {
                return vault.has(PAIRING_ID, PAIRING_LOGIN) ? vault.load(PAIRING_ID, PAIRING_LOGIN) : null;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        @Override
        public void save(char[] sessionKey) {
            try {
                vault.store(PAIRING_ID, PAIRING_LOGIN, sessionKey);
            } catch (IOException e) {
                throw new IllegalStateException("Die KeePass-Kopplung ließ sich nicht im Tresor ablegen", e);
            }
        }

        @Override
        public void clear() {
            try {
                vault.remove(PAIRING_ID);
            } catch (IOException e) {
                throw new IllegalStateException("Die KeePass-Kopplung ließ sich nicht entfernen", e);
            }
        }
    }
}
