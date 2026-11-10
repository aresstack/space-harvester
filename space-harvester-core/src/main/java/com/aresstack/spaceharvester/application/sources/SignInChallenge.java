package com.aresstack.spaceharvester.application.sources;

/**
 * Eine zusätzliche Anmeldung, die eine Quelle vor Dateien verlangt, etwa der Einmalcode (OTP) eines
 * Zwei-Faktor-Plugins vor den Downloads einer Confluence (ADR 0018, #185). Spiegelt die Abfrage des Confluence-Adapters
 * aus dem Enterprise AI Client ({@code OneTimeCodeChallenge}) ohne dessen Typen; erkannt und gesendet wird im Adapter,
 * die Sitzung hält der Adapter für die ganze Quelle. Texte stammen von der Seite der Quelle.
 *
 * <p>Der Code wird nur gesendet, nie gespeichert, protokolliert oder an ein Modell gegeben; der Aufrufer überschreibt
 * sein {@code char[]} danach. Beide Aufrufe blockieren (Netz).
 */
public interface SignInChallenge {

    /** Wofür der Code gilt (etwa der Host der Quelle). */
    String service();

    /** Überschrift der Seite, darf leer sein. */
    String title();

    /** Hinweis der Quelle (etwa „Code per E-Mail gesendet“), darf leer sein. */
    String message();

    /** Fehlermeldung des letzten Versuchs (etwa „Code ungültig“), sonst leer. */
    String error();

    /** Ob die Quelle einen neuen Code senden kann. */
    boolean resendAvailable();

    /**
     * Sendet den Code. {@code null}: angenommen, Dateien laden jetzt für die ganze Quelle; sonst die nächste Abfrage
     * (etwa mit „Code ungültig“).
     *
     * @throws SourceAccessException wenn das Senden scheitert
     */
    SignInChallenge submit(char[] code);

    /**
     * Ob die Abfrage neben dem Code einmalig Benutzer und Passwort braucht (#571): Eine Quelle mit Personal Access
     * Token hat keine Web-Sitzung, an die die Zwei-Faktor-Anmeldung den Code binden kann. Die Oberfläche zeigt dann
     * zwei weitere Felder und sendet mit {@link #submit(char[], String, char[])}.
     */
    default boolean asksForLogin() {
        return false;
    }

    /**
     * Sendet den Code wie {@link #submit(char[])}, für genau dieses Absenden aber mit Benutzer und Passwort statt der
     * konfigurierten Anmeldung (#571). Danach gilt die freigeschaltete Web-Sitzung; alle anderen Abrufe laufen weiter
     * mit der konfigurierten Anmeldung. Benutzer und Passwort werden nie gespeichert oder protokolliert; der Aufrufer
     * überschreibt {@code password} danach. Ohne {@link #asksForLogin()} oder ohne Benutzer bzw. Passwort wie
     * {@link #submit(char[])}.
     *
     * @throws SourceAccessException wenn das Senden scheitert
     */
    default SignInChallenge submit(char[] code, String username, char[] password) {
        return submit(code);
    }

    /**
     * Fordert einen neuen Code an; liefert die Abfrage danach.
     *
     * @throws SourceAccessException wenn das Anfordern scheitert
     */
    SignInChallenge resend();
}
