package com.aresstack.enterpriseai.adyton;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ein Eintrag mit Passwort, kurzlebig: von {@link SecretVault#read} geliefert oder von der Passwortverwaltung zum
 * {@link SecretVault#save Speichern} gebaut. Der Besitzer schließt ihn; {@link #close()} überschreibt das Passwort.
 * {@link #toString()} nennt nur den Titel.
 */
public final class VaultEntryDraft implements AutoCloseable {

    private final String title;
    private final String userName;
    private final char[] password;
    private final String location;
    private final Map<String, String> fields;

    /** Kopiert {@code password}; der Aufrufer überschreibt sein Array selbst. */
    public VaultEntryDraft(String title, String userName, char[] password, String location,
                           Map<String, String> fields) {
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("title must not be empty");
        }
        this.title = title.trim();
        this.userName = userName == null ? "" : userName;
        this.password = password == null ? new char[0] : Arrays.copyOf(password, password.length);
        this.location = location == null ? "" : location.trim();
        this.fields = fields == null ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(fields));
    }

    public String title() {
        return title;
    }

    public String userName() {
        return userName;
    }

    /** Eine Kopie des Passworts; der Aufrufer überschreibt sie nach Gebrauch. */
    public char[] copyPassword() {
        return Arrays.copyOf(password, password.length);
    }

    public String location() {
        return location;
    }

    public Map<String, String> fields() {
        return fields;
    }

    @Override
    public void close() {
        Arrays.fill(password, '\0');
    }

    @Override
    public String toString() {
        return "VaultEntryDraft[" + title + ", ***]";
    }
}
