package com.aresstack.spaceharvester.application.sources;

import java.util.List;
import java.util.Objects;

/**
 * Ein Quellentyp, den der Katalog kennt (#187), für Auswahllisten in Oberflächen.
 *
 * @param id               Wert von {@code type} in der Quellenkonfiguration, z. B. {@code git}
 * @param displayName      Anzeigename, z. B. {@code Git-Repository}
 * @param needsCredentials der Typ verlangt einen Eintrag {@code options.credentialRef}, den nur der
 *                         {@code SecretProvider} des einbettenden Programms auflöst (#188); Confluence mit eigenen
 *                         Feldern für die Anmeldung zählt nicht dazu
 * @param addressOption    Schlüssel unter {@code options}, unter dem der Typ seine Adresse liest, z. B. {@code host}
 *                         oder {@code url}; {@code null}, wenn die Adresse nicht unter {@code options} steht (Confluence
 *                         liest {@code base-url})
 * @param fields           Eingabefelder unter {@code options} in Anzeigereihenfolge; {@code title}, {@code base-url},
 *                         {@code auth} und {@code client-certificate} stehen außerhalb von {@code options} und fehlen
 * @param authModes        erlaubte Werte von {@code auth.type}, zuerst {@link AuthMode#NONE}
 */
public record SourceType(String id, String displayName, boolean needsCredentials, String addressOption,
                         List<SourceField> fields, List<AuthMode> authModes) {

    /** Werte von {@code auth.type} in der Quellenkonfiguration. */
    public enum AuthMode {
        /** Ohne Anmeldung, oder mit einem eigenen Verweis unter {@code options}. */
        NONE,
        /** Benutzername und Passwort. */
        BASIC,
        /** Persönliches Zugriffstoken. */
        PAT
    }

    public SourceType {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        fields = fields == null ? List.of() : List.copyOf(fields);
        authModes = authModes == null || authModes.isEmpty() ? List.of(AuthMode.NONE) : List.copyOf(authModes);
    }
}
