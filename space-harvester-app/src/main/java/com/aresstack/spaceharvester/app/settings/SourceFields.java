package com.aresstack.spaceharvester.app.settings;

import com.aresstack.spaceharvester.application.sources.SourceField;
import com.aresstack.spaceharvester.application.sources.SourceType;
import java.util.List;

/**
 * Felder, Anmeldearten und TLS je Quellentyp für das Quellenformular, aus dem Typ des Core
 * ({@link SourceType#fields()}, {@link SourceType#authModes()}, {@link SourceType#tls()}). Confluence hat keine
 * Felder (eigenes Formular).
 */
public final class SourceFields {

    private SourceFields() {
    }

    /** Die Felder des Typs in seiner Reihenfolge; leer für Confluence und unbekannte Typen. */
    public static List<SourceFieldSpec> fields(SourceType type) {
        return type == null ? List.of() : type.fields().stream().map(SourceFields::spec).toList();
    }

    /** Die Anmeldearten des Typs; ohne Angabe nur „Ohne Anmeldung“. */
    public static List<AuthMode> authModes(SourceType type) {
        return type == null ? List.of(AuthMode.NONE)
                : type.authModes().stream().map(mode -> AuthMode.valueOf(mode.name())).toList();
    }

    /** Ob der Typ Client-Zertifikat und eigenes Server-Vertrauen annimmt ({@link SourceType#tls()}, #431). */
    public static boolean tls(SourceType type) {
        return type != null && type.tls();
    }

    /**
     * Ein Feld des Core als Feld des Formulars. Der Verweis {@code credentialRef} ist hier keine KeePass-Auswahl wie im
     * Enterprise AI Client, sondern ein freier Verweis an den Anbieter des Programms; er ist nie Pflicht, weil
     * „Benutzer und Passwort“ ihn ersetzt (ADR 0022), und steht deshalb unter „Erweitert“.
     */
    private static SourceFieldSpec spec(SourceField field) {
        SourceFieldSpec.Kind kind = SourceFieldSpec.Kind.valueOf(field.kind().name());
        if (kind == SourceFieldSpec.Kind.SECRET_REF) {
            return new SourceFieldSpec(field.key(), "Zugangsverweis (optional)", "Verweis an den Anbieter des "
                    + "Programms, z. B. keepass:jes; nur ohne Anmeldung hier", kind, false, true, "");
        }
        return new SourceFieldSpec(field.key(), field.label(), field.hint(), kind, field.required(),
                field.advanced(), field.defaultValue());
    }
}
