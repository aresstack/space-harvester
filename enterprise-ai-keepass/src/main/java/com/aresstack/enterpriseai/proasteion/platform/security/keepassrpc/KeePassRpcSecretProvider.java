package com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc;

import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.adyton.SecretEntry;
import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException.Reason;
import com.aresstack.enterpriseai.adyton.SecretVault;
import com.aresstack.enterpriseai.adyton.VaultEntry;
import com.aresstack.enterpriseai.adyton.VaultEntryDraft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * {@link SecretProvider} auf Basis von KeePassRPC (KeePass 2.x mit KeePassRPC-Plugin).
 *
 * <p>Ein {@link SecretRef} identifiziert einen KeePass-Eintrag über seinen Titel: {@code keepass:<Titel>} oder
 * einfach {@code <Titel>}. Geliefert werden Benutzername (Principal) und Passwort.
 *
 * <p>Ablauf je Auflösung: gespeicherten Pairing-Schlüssel laden; fehlt er, Pairing über den
 * {@link KeePassPairingCallback}; verbinden und per Key-Challenge-Response anmelden; lehnt KeePass den Schlüssel
 * ab (Pairing in KeePass widerrufen), Schlüssel verwerfen und genau einmal neu pairen; Eintrag lesen; Verbindung
 * schließen. Jede Auflösung nutzt eine eigene, kurze Verbindung (wie MainframeMate {@code KeePassProvider}).
 *
 * <p>Fehlerabbildung: KeePass nicht erreichbar → {@link Reason#NOT_AVAILABLE}; kein Eintrag mit dem Titel →
 * {@link Reason#NOT_FOUND}; Pairing-Passwort falsch oder Schlüssel nach neuem Pairing weiter abgelehnt →
 * {@link Reason#ACCESS_DENIED}; Pairing abgebrochen → {@link Reason#CANCELLED}.
 *
 * <p>Als {@link SecretVault} legt er Einträge wie MainframeMate {@code KeePassRpcClient} an, ändert und löscht sie
 * ({@code AddLogin}, {@code UpdateLogin}, {@code RemoveEntry}); Zusatzfelder ({@code MM_*}) stehen als
 * {@code FFTtext} in {@code formFieldList}. Lehnt KeePassRPC einen Schreibzugriff ab, meldet er
 * {@link Reason#ACCESS_DENIED}.
 *
 * <p>Herkunft: Architektur nach corenth {@code KeePassRpcSecretMaterialProvider} (schmale Lookup-Naht hinter dem
 * Provider), Funktion nach MainframeMate {@code KeePassProvider}/{@code KeePassRpcClient}; die unvollständige
 * Reflection-Bindung aus corenth wurde nicht übernommen.
 */
public final class KeePassRpcSecretProvider implements SecretProvider, SecretVault {

    private static final Logger LOG = Logger.getLogger(KeePassRpcSecretProvider.class.getName());
    private static final String SCHEME = "keepass:";

    private final KeePassRpcTransport transport;
    private final KeePassPairingKeyStore keyStore;
    private final KeePassPairingCallback pairingCallback;

    /**
     * Produktiver Konstruktor für die Composition Root.
     *
     * @param config          Verbindung zu KeePassRPC
     * @param keyStore        Ablage für den Pairing-Schlüssel
     * @param pairingCallback Benutzerinteraktion für das Pairing ({@link KeePassPairingCallback#unavailable()},
     *                        wenn keine UI vorhanden ist)
     */
    public KeePassRpcSecretProvider(KeePassRpcConfig config, KeePassPairingKeyStore keyStore,
                                    KeePassPairingCallback pairingCallback) {
        this(new WebSocketKeePassRpcTransport(requireNonNull(config, "config")), keyStore, pairingCallback);
    }

    KeePassRpcSecretProvider(KeePassRpcTransport transport, KeePassPairingKeyStore keyStore,
                             KeePassPairingCallback pairingCallback) {
        this.transport = requireNonNull(transport, "transport");
        this.keyStore = requireNonNull(keyStore, "keyStore");
        this.pairingCallback = requireNonNull(pairingCallback, "pairingCallback");
    }

    @Override
    public synchronized SecretMaterial resolve(final SecretRef ref) throws SecretUnavailableException {
        if (ref == null) {
            throw new IllegalArgumentException("SecretRef darf nicht null sein");
        }
        final String title = entryTitleOf(ref);
        return inSession(ref, new SessionCall<SecretMaterial>() {
            @Override
            public SecretMaterial run(KeePassRpcTransport.Session session)
                    throws KeePassRpcException, SecretUnavailableException {
                KeePassEntry entry = session.findEntryByTitle(title);
                if (entry == null) {
                    throw new SecretUnavailableException(Reason.NOT_FOUND, ref,
                            "kein KeePass-Eintrag mit diesem Titel");
                }
                try {
                    return new SecretMaterial(ref, entry.userName(), entry.password());
                } finally {
                    entry.close();
                }
            }
        });
    }

    /** Titel und Adresse aller Einträge, wie KeePassRPC sie liefert; nie Benutzer oder Passwort. */
    @Override
    public synchronized List<SecretEntry> entries() throws SecretUnavailableException {
        return inSession(SecretRef.of("keepass:*"), new SessionCall<List<SecretEntry>>() {
            @Override
            public List<SecretEntry> run(KeePassRpcTransport.Session session) throws KeePassRpcException {
                List<SecretEntry> entries = new ArrayList<SecretEntry>();
                Set<String> seen = new HashSet<String>();
                for (KeePassEntry entry : session.allEntries()) {
                    try {
                        if (!entry.title().trim().isEmpty() && seen.add(entry.title().trim())) {
                            entries.add(new SecretEntry(entry.title(), entry.location()));
                        }
                    } finally {
                        entry.close();
                    }
                }
                return entries;
            }
        });
    }

    /** Die URL im Eintrag zur Referenz (erstes http(s)-Feld der URL-Liste) oder {@code null}. */
    @Override
    public synchronized String locationOf(final SecretRef ref) throws SecretUnavailableException {
        if (ref == null) {
            throw new IllegalArgumentException("SecretRef darf nicht null sein");
        }
        final String title = entryTitleOf(ref);
        return inSession(ref, new SessionCall<String>() {
            @Override
            public String run(KeePassRpcTransport.Session session)
                    throws KeePassRpcException, SecretUnavailableException {
                KeePassEntry entry = session.findEntryByTitle(title);
                if (entry == null) {
                    throw new SecretUnavailableException(Reason.NOT_FOUND, ref,
                            "kein KeePass-Eintrag mit diesem Titel");
                }
                try {
                    return entry.location();
                } finally {
                    entry.close();
                }
            }
        });
    }

    /** Die benannten Textfelder des Eintrags zur Referenz (z. B. {@code MM_CertAlias}), nie Benutzer oder Passwort. */
    @Override
    public synchronized java.util.Map<String, String> attributesOf(final SecretRef ref)
            throws SecretUnavailableException {
        if (ref == null) {
            throw new IllegalArgumentException("SecretRef darf nicht null sein");
        }
        final String title = entryTitleOf(ref);
        return inSession(ref, new SessionCall<java.util.Map<String, String>>() {
            @Override
            public java.util.Map<String, String> run(KeePassRpcTransport.Session session)
                    throws KeePassRpcException, SecretUnavailableException {
                KeePassEntry entry = session.findEntryByTitle(title);
                if (entry == null) {
                    throw new SecretUnavailableException(Reason.NOT_FOUND, ref,
                            "kein KeePass-Eintrag mit diesem Titel");
                }
                try {
                    return entry.attributes();
                } finally {
                    entry.close();
                }
            }
        });
    }

    @Override
    public synchronized List<VaultEntry> list() throws SecretUnavailableException {
        return inSession(SecretRef.of("keepass:*"), new SessionCall<List<VaultEntry>>() {
            @Override
            public List<VaultEntry> run(KeePassRpcTransport.Session session) throws KeePassRpcException {
                List<VaultEntry> entries = new ArrayList<VaultEntry>();
                Set<String> seen = new HashSet<String>();
                for (KeePassEntry entry : session.allEntries()) {
                    try {
                        if (!entry.title().trim().isEmpty() && seen.add(entry.title().trim())) {
                            entries.add(new VaultEntry(entry.title(), entry.userName(),
                                    entry.url(),
                                    entry.attributes()));
                        }
                    } finally {
                        entry.close();
                    }
                }
                return entries;
            }
        });
    }

    @Override
    public synchronized VaultEntryDraft read(String title) throws SecretUnavailableException {
        final SecretRef ref = refOf(title);
        return inSession(ref, new SessionCall<VaultEntryDraft>() {
            @Override
            public VaultEntryDraft run(KeePassRpcTransport.Session session)
                    throws KeePassRpcException, SecretUnavailableException {
                KeePassEntry entry = existing(session, ref);
                try {
                    return new VaultEntryDraft(entry.title(), entry.userName(), entry.password(),
                            entry.url(), entry.attributes());
                } finally {
                    entry.close();
                }
            }
        });
    }

    @Override
    public synchronized void save(final VaultEntryDraft draft) throws SecretUnavailableException {
        if (draft == null) {
            throw new IllegalArgumentException("Eintrag darf nicht null sein");
        }
        final SecretRef ref = refOf(draft.title());
        inSession(ref, new SessionCall<Void>() {
            @Override
            public Void run(KeePassRpcTransport.Session session)
                    throws KeePassRpcException, SecretUnavailableException {
                char[] password = draft.copyPassword();
                KeePassEntry existing = session.findEntryByTitle(draft.title());
                try (KeePassEntry entry = new KeePassEntry(draft.title(), draft.userName(), password,
                        draft.location(), draft.fields(), "", "", draft.location())) {
                    if (existing == null) {
                        session.addEntry(entry);
                    } else {
                        session.updateEntry(entry, existing);
                    }
                    LOG.info("KeePass-Eintrag " + (existing == null ? "angelegt" : "geändert") + ": "
                            + draft.title());
                    return null;
                } catch (KeePassRpcException e) {
                    throw rejected(ref, e);
                } finally {
                    Arrays.fill(password, '\0');
                    if (existing != null) {
                        existing.close();
                    }
                }
            }
        });
    }

    /**
     * Create-only: within one session the adapter checks the current KeePass state (title lookup and the full entry
     * list, trimmed and case-insensitive) and on a match calls neither {@code UpdateLogin} nor {@code AddLogin}.
     * KeePassRPC has no atomic create-if-absent; a short window remains between the check and {@code AddLogin} for
     * an entry created at the same moment directly in KeePass. This adapter serializes its own writes
     * ({@code synchronized}).
     */
    @Override
    public synchronized void create(final VaultEntryDraft draft) throws SecretUnavailableException {
        if (draft == null) {
            throw new IllegalArgumentException("Eintrag darf nicht null sein");
        }
        final SecretRef ref = refOf(draft.title());
        inSession(ref, new SessionCall<Void>() {
            @Override
            public Void run(KeePassRpcTransport.Session session)
                    throws KeePassRpcException, SecretUnavailableException {
                KeePassEntry existing = session.findEntryByTitle(draft.title());
                if (existing != null) {
                    existing.close();
                    throw alreadyExists(ref);
                }
                for (KeePassEntry entry : session.allEntries()) {
                    try {
                        if (entry.title() != null && entry.title().trim().equalsIgnoreCase(draft.title().trim())) {
                            throw alreadyExists(ref);
                        }
                    } finally {
                        entry.close();
                    }
                }
                char[] password = draft.copyPassword();
                try (KeePassEntry entry = new KeePassEntry(draft.title(), draft.userName(), password,
                        draft.location(), draft.fields(), "", "", draft.location())) {
                    session.addEntry(entry);
                    LOG.info("KeePass-Eintrag angelegt: " + draft.title());
                    return null;
                } catch (KeePassRpcException e) {
                    throw rejected(ref, e);
                } finally {
                    Arrays.fill(password, '\0');
                }
            }
        });
    }

    private static SecretUnavailableException alreadyExists(SecretRef ref) {
        return new SecretUnavailableException(Reason.ALREADY_EXISTS, ref,
                "ein KeePass-Eintrag mit diesem Titel existiert bereits");
    }

    @Override
    public synchronized void remove(String title) throws SecretUnavailableException {
        final SecretRef ref = refOf(title);
        inSession(ref, new SessionCall<Void>() {
            @Override
            public Void run(KeePassRpcTransport.Session session)
                    throws KeePassRpcException, SecretUnavailableException {
                KeePassEntry existing = existing(session, ref);
                try {
                    session.removeEntry(existing);
                    LOG.info("KeePass-Eintrag gelöscht: " + existing.title());
                    return null;
                } catch (KeePassRpcException e) {
                    throw rejected(ref, e);
                } finally {
                    existing.close();
                }
            }
        });
    }

    private static KeePassEntry existing(KeePassRpcTransport.Session session, SecretRef ref)
            throws KeePassRpcException, SecretUnavailableException {
        KeePassEntry entry = session.findEntryByTitle(entryTitleOf(ref));
        if (entry == null) {
            throw new SecretUnavailableException(Reason.NOT_FOUND, ref, "kein KeePass-Eintrag mit diesem Titel");
        }
        return entry;
    }

    private static SecretRef refOf(String title) {
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("Titel darf nicht leer sein");
        }
        return SecretRef.of(title.trim());
    }

    /** Verbindungsfehler bleiben Verbindungsfehler; eine Fehlerantwort von KeePassRPC ist eine Ablehnung. */
    private static KeePassRpcException rejected(SecretRef ref, KeePassRpcException e)
            throws SecretUnavailableException {
        if (e.kind() == KeePassRpcException.Kind.PROTOCOL) {
            throw new SecretUnavailableException(Reason.ACCESS_DENIED, ref,
                    "KeePassRPC lehnt ab: " + e.getMessage(), e);
        }
        return e;
    }

    /**
     * Setzt ein Zusatzfeld des bestehenden Eintrags als Formularfeld {@code FFTtext} im {@code formFieldList}, wie
     * MainframeMate {@code MM_CertAlias} ablegt; so lesen beide Anwendungen denselben Eintrag.
     */
    @Override
    public synchronized void setAttribute(final SecretRef ref, final String name, final String value)
            throws SecretUnavailableException {
        if (ref == null || name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("SecretRef und Feldname müssen gesetzt sein");
        }
        final String title = entryTitleOf(ref);
        inSession(ref, new SessionCall<Boolean>() {
            @Override
            public Boolean run(KeePassRpcTransport.Session session)
                    throws KeePassRpcException, SecretUnavailableException {
                if (!session.setFormField(title, name.trim(), value == null ? "" : value)) {
                    throw new SecretUnavailableException(Reason.NOT_FOUND, ref,
                            "kein KeePass-Eintrag mit diesem Titel");
                }
                return Boolean.TRUE;
            }
        });
    }

    /** Eine Arbeit in einer authentifizierten Sitzung. */
    private interface SessionCall<T> {
        T run(KeePassRpcTransport.Session session) throws KeePassRpcException, SecretUnavailableException;
    }

    /**
     * Gespeicherten Pairing-Schlüssel laden (fehlt er: pairen), Sitzung öffnen und {@code call} ausführen; lehnt
     * KeePass den Schlüssel ab, genau einmal neu pairen.
     */
    private <T> T inSession(SecretRef ref, SessionCall<T> call) throws SecretUnavailableException {
        char[] key = keyStore.load();
        try {
            if (key == null || key.length == 0) {
                key = pair(ref);
            }
            try {
                return run(key, call);
            } catch (KeePassRpcException e) {
                if (e.kind() != KeePassRpcException.Kind.AUTH_FAILED) {
                    throw unavailable(ref, e);
                }
                LOG.info("KeePassRPC hat das gespeicherte Pairing abgelehnt; neues Pairing für " + ref);
                keyStore.clear();
                wipe(key);
                key = pair(ref);
                try {
                    return run(key, call);
                } catch (KeePassRpcException retry) {
                    if (retry.kind() != KeePassRpcException.Kind.AUTH_FAILED) {
                        throw unavailable(ref, retry);
                    }
                    keyStore.clear(); // nachweislich ungültig: beim nächsten Mal gleich neu pairen
                    throw new SecretUnavailableException(Reason.ACCESS_DENIED, ref,
                            "KeePassRPC lehnt auch das neue Pairing ab", retry);
                }
            }
        } finally {
            wipe(key);
        }
    }

    private <T> T run(char[] key, SessionCall<T> call) throws KeePassRpcException, SecretUnavailableException {
        try (KeePassRpcTransport.Session session = transport.open(key)) {
            return call.run(session);
        }
    }

    /** Titel des KeePass-Eintrags zu einer Referenz ({@code keepass:}-Präfix optional). */
    static String entryTitleOf(SecretRef ref) {
        String id = ref.id();
        if (id.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            id = id.substring(SCHEME.length()).trim();
        }
        if (id.isEmpty()) {
            throw new IllegalArgumentException(ref + " nennt keinen KeePass-Eintrag");
        }
        return id;
    }

    private char[] pair(SecretRef ref) throws SecretUnavailableException {
        char[] key;
        try {
            key = transport.pair(pairingCallback);
        } catch (KeePassRpcException e) {
            if (e.kind() == KeePassRpcException.Kind.AUTH_FAILED) {
                throw new SecretUnavailableException(Reason.ACCESS_DENIED, ref,
                        "KeePassRPC-Pairing abgelehnt (Einmal-Passwort falsch?)", e);
            }
            throw unavailable(ref, e);
        }
        if (key == null || key.length == 0) {
            throw new SecretUnavailableException(Reason.CANCELLED, ref, "KeePassRPC-Pairing abgebrochen");
        }
        try {
            keyStore.save(key);
        } catch (RuntimeException e) {
            wipe(key);
            throw e;
        }
        LOG.info("KeePassRPC-Pairing erfolgreich");
        return key;
    }

    private static SecretUnavailableException unavailable(SecretRef ref, KeePassRpcException e) {
        return new SecretUnavailableException(Reason.NOT_AVAILABLE, ref,
                "KeePassRPC nicht verfügbar: " + e.getMessage(), e);
    }

    private static void wipe(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " darf nicht null sein");
        }
        return value;
    }

    @Override
    public String toString() {
        return "KeePassRpcSecretProvider[" + transport + "]";
    }
}
