package com.aresstack.spaceharvester.deck.files.sharepoint;

/**
 * Port für die Anmeldung an einer WebDAV-Freigabe (SharePoint), nachgebaut nach {@code SharePointAuthenticator} von
 * MainframeMate: erst prüfen, ob das Betriebssystem schon verbunden ist, dann mit der Windows-Anmeldung (Kerberos/NTLM,
 * {@code net use} ohne Kennwort), zuletzt mit Benutzer und Kennwort. Aufrufe blockieren und laufen nie auf dem EDT.
 */
public interface WebDavLogin {

    /** Ergebnis einer Anmeldung. */
    record Outcome(boolean connected, boolean needsCredentials, String reason) {

        public static Outcome ok() {
            return new Outcome(true, false, null);
        }

        public static Outcome credentialsNeeded(String reason) {
            return new Outcome(false, true, reason);
        }

        public static Outcome failed(String reason) {
            return new Outcome(false, false, reason);
        }
    }

    /** Verbindet ohne Kennwort (bestehende Sitzung oder Windows-Anmeldung). */
    Outcome connect(String uncPath);

    /** Verbindet mit Benutzer und Kennwort; das Kennwort wird nicht aufbewahrt. */
    Outcome connect(String uncPath, String user, char[] password);
}
