package com.aresstack.spaceharvester.application.sources;

import java.util.Objects;

/**
 * Ein Quellentyp, den der Katalog kennt (#187), für Auswahllisten in Oberflächen.
 *
 * @param id               Wert von {@code type} in der Quellenkonfiguration, z. B. {@code git}
 * @param displayName      Anzeigename, z. B. {@code Git-Repository}
 * @param needsCredentials der Typ verlangt einen Eintrag {@code options.credentialRef}, den nur der
 *                         {@code SecretProvider} des einbettenden Programms auflöst (#188); Confluence mit eigenen
 *                         Feldern für die Anmeldung zählt nicht dazu
 */
public record SourceType(String id, String displayName, boolean needsCredentials) {

    public SourceType {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
    }
}
