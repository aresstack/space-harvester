package com.aresstack.enterpriseai.adyton;

/**
 * Was ein Tresor über einen Eintrag ohne Secret verrät: Titel und die dort hinterlegte Adresse (URL). Damit kann
 * die Oberfläche Einträge zur Auswahl stellen und eine Quelle ihre Adresse aus dem Eintrag nehmen, ohne dass
 * Benutzername oder Passwort den Security-Adapter verlassen. Wie in MainframeMate trägt der KeePass-Eintrag die
 * URL des Dienstes; die Konfiguration nennt dann nur noch den Titel.
 */
public final class SecretEntry {

    private final String title;
    private final String location;

    /**
     * @param title    Titel des Eintrags, nicht leer
     * @param location hinterlegte Adresse oder {@code null}, wenn keine
     */
    public SecretEntry(String title, String location) {
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("title must not be empty");
        }
        this.title = title.trim();
        this.location = location == null || location.trim().isEmpty() ? null : location.trim();
    }

    public String title() {
        return title;
    }

    /** Die im Eintrag hinterlegte Adresse oder {@code null}. */
    public String location() {
        return location;
    }

    @Override
    public String toString() {
        return "SecretEntry[" + title + (location == null ? "" : ", " + location) + "]";
    }
}
