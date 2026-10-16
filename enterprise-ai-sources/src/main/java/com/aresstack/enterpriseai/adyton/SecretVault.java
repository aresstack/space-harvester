package com.aresstack.enterpriseai.adyton;

import java.util.List;

/**
 * Verwaltung der Tresor-Einträge wie die Passwortverwaltung in MainframeMate ({@code ShowPasswordsMenuCommand}):
 * auflisten, einzeln mit Passwort lesen, anlegen oder ändern, löschen. Der Titel ist die eindeutige ID eines
 * Eintrags. Implementiert vom Security-Adapter neben dem {@link SecretProvider}; Aufrufe dürfen blockieren
 * (Pairing) und laufen nie auf dem Swing-EDT.
 */
public interface SecretVault {

    /** Alle Einträge ohne Passwort. */
    List<VaultEntry> list() throws SecretUnavailableException;

    /** Der Eintrag mit Passwort; der Aufrufer schließt ihn. */
    VaultEntryDraft read(String title) throws SecretUnavailableException;

    /** Legt den Eintrag an oder ändert den mit gleichem Titel; alle Felder werden ersetzt. */
    void save(VaultEntryDraft entry) throws SecretUnavailableException;

    /**
     * Create-only: adds the entry only if no entry with the same title exists (trimmed, case-insensitive) and never
     * changes an existing one; a collision fails with {@link SecretUnavailableException.Reason#ALREADY_EXISTS}.
     * Each adapter implements this against its own backend; there is deliberately no default built on the upserting
     * {@link #save(VaultEntryDraft)}.
     */
    void create(VaultEntryDraft entry) throws SecretUnavailableException;

    /** Löscht den Eintrag mit diesem Titel. */
    void remove(String title) throws SecretUnavailableException;
}
