package com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc;

/**
 * Ablage für den Sitzungsschlüssel, den KeePassRPC nach erfolgreichem Pairing für diesen Client speichert
 * (SHA-256 des SRP-Werts {@code S}, 64 Hex-Zeichen). Mit ihm authentifiziert sich der Adapter bei jeder weiteren
 * Verbindung per Key-Challenge-Response, ohne erneutes Pairing.
 *
 * <p>Der Schlüssel ist selbst ein Secret: Die Implementierung (Composition Root, AP23) entscheidet, wie er
 * geschützt abgelegt wird (MainframeMate: verschlüsselt in den Settings). Er erscheint nie in Logs.
 */
public interface KeePassPairingKeyStore {

    /** @return eine Kopie des gespeicherten Schlüssels oder {@code null}, wenn noch kein Pairing besteht */
    char[] load();

    /** Speichert den Schlüssel; der Adapter überschreibt sein Array danach. */
    void save(char[] sessionKey);

    /** Verwirft den Schlüssel, z. B. wenn KeePass das Pairing widerrufen hat. */
    void clear();
}
