package com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc;

/**
 * Adapterinterne Naht zwischen {@link KeePassRpcSecretProvider} und dem Wire-Protokoll. Produktiv
 * {@link WebSocketKeePassRpcTransport}; in Tests ein Fake, damit die Fehlerabbildung ohne KeePass prüfbar ist.
 */
interface KeePassRpcTransport {

    /**
     * Führt das SRP-Pairing durch: Verbindung öffnen, anmelden (KeePass zeigt dann ein Einmal-Passwort),
     * Passwort über den Callback abfragen, Beweis senden.
     *
     * @return den neuen Sitzungsschlüssel (64 Hex-Zeichen) oder {@code null}, wenn der Benutzer abbricht
     */
    char[] pair(KeePassPairingCallback callback) throws KeePassRpcException;

    /** Öffnet eine per Key-Challenge-Response authentifizierte Sitzung mit dem gespeicherten Schlüssel. */
    Session open(char[] sessionKey) throws KeePassRpcException;

    /** Eine authentifizierte Verbindung; nach {@link #close()} unbrauchbar. */
    interface Session extends AutoCloseable {

        /**
         * Sucht einen Eintrag mit exakt diesem Titel (Groß-/Kleinschreibung egal).
         *
         * @return den Eintrag oder {@code null}, wenn keiner existiert
         */
        KeePassEntry findEntryByTitle(String title) throws KeePassRpcException;

        /**
         * Setzt im Eintrag mit exakt diesem Titel das Formularfeld {@code name} (Typ {@code FFTtext}, wie MainframeMate
         * {@code KeePassRpcClient.addCustomFormField}) und lässt alles andere unverändert.
         *
         * @return {@code false}, wenn kein Eintrag mit dem Titel existiert
         */
        default boolean setFormField(String title, String name, String value) throws KeePassRpcException {
            throw new KeePassRpcException(KeePassRpcException.Kind.PROTOCOL, "Schreiben nicht unterstützt");
        }

        /** Alle Einträge mit Titel und Adresse, ohne Benutzer und Passwort. */
        default java.util.List<KeePassEntry> allEntries() throws KeePassRpcException {
            return java.util.Collections.emptyList();
        }

        /** Legt einen Eintrag in der Wurzelgruppe an ({@code AddLogin}). */
        default void addEntry(KeePassEntry entry) throws KeePassRpcException {
            throw new KeePassRpcException(KeePassRpcException.Kind.PROTOCOL, "Anlegen nicht unterstützt");
        }

        /** Ersetzt den Eintrag {@code existing} durch {@code entry} ({@code UpdateLogin}). */
        default void updateEntry(KeePassEntry entry, KeePassEntry existing) throws KeePassRpcException {
            throw new KeePassRpcException(KeePassRpcException.Kind.PROTOCOL, "Ändern nicht unterstützt");
        }

        /** Löscht den Eintrag ({@code RemoveEntry}). */
        default void removeEntry(KeePassEntry existing) throws KeePassRpcException {
            throw new KeePassRpcException(KeePassRpcException.Kind.PROTOCOL, "Löschen nicht unterstützt");
        }

        @Override
        void close();
    }
}
