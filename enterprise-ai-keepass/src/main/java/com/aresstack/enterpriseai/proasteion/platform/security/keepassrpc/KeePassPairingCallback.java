package com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc;

/**
 * Benutzerinteraktion beim erstmaligen Pairing mit KeePassRPC.
 *
 * <p>Ablauf (wie MainframeMate {@code KeePassRpcPairingDialog}): Der Adapter öffnet die Verbindung und meldet
 * sich an; KeePass zeigt daraufhin ein Einmal-Passwort an. Der Adapter ruft dann
 * {@link #requestPairingPassword(String)} auf, der Benutzer tippt das Passwort ab, und der Adapter schließt
 * das Pairing auf derselben Verbindung ab. Die Implementierung lebt in {@code exedra} (Dialog auf dem EDT,
 * Aufrufer wartet); der Adapter selbst kennt kein Swing.
 */
public interface KeePassPairingCallback {

    /**
     * Fragt das von KeePass angezeigte Einmal-Passwort ab. Wird nicht auf dem Swing-EDT aufgerufen und darf
     * blockieren, bis der Benutzer antwortet.
     *
     * @param clientDisplayName Name dieses Clients, wie KeePass ihn im Pairing-Dialog anzeigt
     * @return das eingegebene Passwort (der Adapter überschreibt das Array nach Gebrauch) oder {@code null},
     *         wenn der Benutzer abbricht
     */
    char[] requestPairingPassword(String clientDisplayName);

    /** Callback für nicht interaktive Umgebungen: Pairing ist nicht möglich und gilt als abgebrochen. */
    static KeePassPairingCallback unavailable() {
        return clientDisplayName -> null;
    }
}
