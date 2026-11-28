package com.aresstack.spaceharvester.provingground.model;

/**
 * A user tests run as. Only a reference: name, password and one-time secret stay in the vault (KeePass) and are read
 * at run time; nothing confidential is stored in the test project.
 *
 * @param name        the name steps refer to (e.g. "sachbearbeiter")
 * @param vaultEntry  the vault entry with user name, password and, if the application asks for one, the OTP secret
 * @param description who this user is in the application; may be empty
 */
public record TestUser(String name, String vaultEntry, String description) {

    public TestUser {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Benutzer ohne Namen");
        }
        name = name.trim();
        vaultEntry = vaultEntry == null ? "" : vaultEntry.trim();
        description = description == null ? "" : description;
    }
}
