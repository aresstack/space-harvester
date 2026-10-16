package com.aresstack.enterpriseai.source.api;

/**
 * Eine Einmalcode-Abfrage (OTP), die eine Quelle vor Dateien verlangt, etwa ein Zwei-Faktor-Plugin vor den Downloads
 * einer Confluence. Kommt mit {@link OneTimeCodeRequiredException}; die Oberfläche zeigt sie im Anzeigebereich, und der
 * Aufrufer lädt nach der Bestätigung erneut. Texte stammen von der Seite der Quelle.
 *
 * <p>Der Code wird nur gesendet, nie gespeichert oder protokolliert; der Aufrufer überschreibt sein {@code char[]}
 * danach. Beide Aufrufe blockieren (Netz) und gehören nicht auf den EDT.
 */
public interface OneTimeCodeChallenge {

    /** Wofür der Code gilt (z. B. der Host der Quelle). */
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
     */
    OneTimeCodeChallenge submit(char[] code) throws KnowledgeSourceException;

    /** Fordert einen neuen Code an; liefert die Abfrage danach. */
    OneTimeCodeChallenge resend() throws KnowledgeSourceException;
}
