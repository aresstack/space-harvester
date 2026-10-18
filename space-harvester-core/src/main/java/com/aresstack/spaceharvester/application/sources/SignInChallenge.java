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
     * Fordert einen neuen Code an; liefert die Abfrage danach.
     *
     * @throws SourceAccessException wenn das Anfordern scheitert
     */
    SignInChallenge resend();
}
