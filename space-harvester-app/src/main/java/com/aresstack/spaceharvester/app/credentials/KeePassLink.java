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
 * ({@link FilePairingKeyStore}), nicht im DPAPI-Tresor, und zwar in einem Ordner mit einer ACL nur für das eigene Konto
 * ({@link OwnerOnlyDirectory}); gelingt die nicht, nur im Speicher. Alles hier blockiert, also nur im
 * Hintergrund aufrufen.
 */
public final class KeePassLink {

    /** Ordner (neben der Konfigurationsdatei, nur für das eigene Konto) und Datei des Pairing-Schlüssels. */
    public static final String PAIRING_DIRECTORY = "keepass";
    static final String PAIRING_FILE = "pairing.key";
    /** Wie der Client in KeePass heißt; KeePassRPC verlangt einen {@code chrome-extension://}-Origin. */
    static final KeePassRpcConfig CONFIG = KeePassRpcConfig.builder()
            .origin("chrome-extension://spaceharvester")
            .clientId("SpaceHarvester")
            .clientDisplayName("Space Harvester")
            .build();

    private final SecretVault interactive;
    private final SecretVault silent;
    private final Supplier<Boolean> pairedCheck;
    private final Runnable unpair;
    /** Wer erfahren will, dass KeePass gerade geantwortet hat ({@link #onReachable}). */
    private final List<Runnable> reachableListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * @param pairingDirectory Ordner für den Pairing-Schlüssel; lässt er sich nicht auf das eigene Konto beschränken,
     *                         gilt die Kopplung nur bis zum Beenden der App
     * @param prompt       fragt beim Koppeln nach dem Einmal-Passwort, das KeePass anzeigt ({@code null}: abgebrochen)
     */
    public KeePassLink(java.nio.file.Path pairingDirectory, KeePassPairingCallback prompt) {
        java.nio.file.Path pairingFile = pairingDirectory.resolve(PAIRING_FILE);
        SessionKeyStore keys = new SessionKeyStore(new FilePairingKeyStore(pairingFile),
                () -> OwnerOnlyDirectory.secure(pairingDirectory));
        this.pairedCheck = () -> keys.held() || java.nio.file.Files.isRegularFile(pairingFile);
        this.unpair = keys::clear;
        // Antwortet KeePassRPC kurz nicht (etwa beim Start der App), wiederholt die App das Lesen wie der EAC, statt
        // die Quelle sofort ohne Zugangsdaten zu starten.
        this.interactive = new RetryingVault(new KeePassRpcSecretProvider(CONFIG, keys, prompt),
                RetryingVault.DELAYS_MILLIS, Thread::sleep, this::reachable);
        // Beim Start und beim Lesen des Keys nie ungefragt einen Kopplungsdialog öffnen.
        this.silent = new RetryingVault(new KeePassRpcSecretProvider(CONFIG, keys,
                KeePassPairingCallback.unavailable()), RetryingVault.DELAYS_MILLIS, Thread::sleep, this::reachable);
    }

    /** Für Tests: ein beliebiger Passwortspeicher statt KeePassRPC. */
    public KeePassLink(SecretVault vault, Supplier<Boolean> paired) {
        this(vault, paired, () -> { });
    }

    /** Für Tests: wie {@link #KeePassLink(SecretVault, Supplier)}, mit dem, was „Entkoppeln“ auslöst. */
    public KeePassLink(SecretVault vault, Supplier<Boolean> paired, Runnable unpair) {
        this(vault, paired, unpair, new long[0], millis -> { });
    }

    /** Für Tests: mit Wiederholungen nach den Pausen {@code delays}, gewartet wird über {@code sleeper}. */
    KeePassLink(SecretVault vault, Supplier<Boolean> paired, Runnable unpair, long[] delays,
                RetryingVault.Sleeper sleeper) {
        RetryingVault retrying = new RetryingVault(vault, delays, sleeper, this::reachable);
        this.interactive = retrying;
        this.silent = retrying;
        this.pairedCheck = paired;
        this.unpair = unpair;
    }

    /**
     * Ob ein Pairing-Schlüssel gespeichert ist (ohne ihn zu entschlüsseln, also auch auf dem EDT); ob KeePass gerade
     * läuft, sagt erst {@link #titles()}.
     */
    public boolean paired() {
        return Boolean.TRUE.equals(pairedCheck.get());
    }

    /**
     * Meldet {@code listener} nach jedem gelungenen Zugriff auf KeePass, im Thread des Zugriffs (also nie auf dem EDT
     * blockieren und selbst schnell zurückkehren). So holt die App beim Start ausgebliebene Zugangsdaten nach, sobald
     * KeePass wieder antwortet, ohne Neustart und ohne regelmäßig nachzufragen.
     */
    public void onReachable(Runnable listener) {
        reachableListeners.add(listener);
    }

    private void reachable() {
        for (Runnable listener : reachableListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                // Ein Beobachter darf den Zugriff nicht scheitern lassen.
                java.util.logging.Logger.getLogger(KeePassLink.class.getName()).warning(
                        "Beobachter von KeePass gescheitert: " + e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Hebt die Kopplung auf: überschreibt und löscht die Datei {@value #PAIRING_FILE} und vergisst den Schlüssel im
     * Speicher. Der Eintrag in KeePass selbst bleibt; die nächste Anfrage koppelt neu (mit Dialog). Darf auf dem EDT laufen.
     */
    public void unpair() {
        unpair.run();
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

    /** Koppelt bei Bedarf (mit Dialog) und liefert alle Einträge ohne Geheimnisse (Titel, Benutzer, Adresse). */
    public List<VaultEntry> entries() throws SecretUnavailableException {
        return List.copyOf(interactive.list());
    }

    /** Wie {@link #entries()}, aber nie mit Kopplungsdialog (Command Deck); ungültige Kopplung endet als Fehler. */
    public List<VaultEntry> entriesSilently() throws SecretUnavailableException {
        return List.copyOf(silent.list());
    }

    /** Liest einen Eintrag (Adresse und Passwort); der Aufrufer schließt ihn. */
    public VaultEntryDraft read(String title) throws SecretUnavailableException {
        return interactive.read(title);
    }

    /** Liest einen Eintrag ohne Kopplungsdialog (beim Start); der Aufrufer schließt ihn. */
    public VaultEntryDraft readSilently(String title) throws SecretUnavailableException {
        return silent.read(title);
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
        private final java.util.function.BooleanSupplier persistable;
        private char[] memory;

        /** @param persistable vor jedem Schreiben: ob die Datei nur für das eigene Konto lesbar sein wird */
        SessionKeyStore(KeePassPairingKeyStore file, java.util.function.BooleanSupplier persistable) {
            this.file = file;
            this.persistable = persistable;
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
            if (persistable.getAsBoolean()) {
                file.save(Arrays.copyOf(sessionKey, sessionKey.length));
            }
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
