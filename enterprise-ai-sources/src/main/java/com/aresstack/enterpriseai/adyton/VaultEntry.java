package com.aresstack.enterpriseai.adyton;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ein Eintrag des Tresors, wie die Passwortverwaltung ihn auflistet: Titel (eindeutige ID), Benutzer, Adresse und
 * die Zusatzfelder (bei KeePass die von MainframeMate gepflegten {@code MM_*}-Felder), nie das Passwort. Das
 * Passwort holt erst {@link SecretVault#read} für einen einzelnen Eintrag.
 */
public final class VaultEntry {

    private final String title;
    private final String userName;
    private final String location;
    private final Map<String, String> fields;

    public VaultEntry(String title, String userName, String location, Map<String, String> fields) {
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("title must not be empty");
        }
        this.title = title.trim();
        this.userName = userName == null ? "" : userName;
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

    /** Die hinterlegte Adresse oder leer. */
    public String location() {
        return location;
    }

    /** Zusatzfelder (Name → Wert), ohne Benutzer und Passwort. */
    public Map<String, String> fields() {
        return fields;
    }

    /** Wert eines Zusatzfelds oder leer. */
    public String field(String name) {
        String value = fields.get(name);
        return value == null ? "" : value;
    }

    @Override
    public String toString() {
        return "VaultEntry[" + title + "]";
    }
}
