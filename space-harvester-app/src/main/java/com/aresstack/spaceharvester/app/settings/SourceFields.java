package com.aresstack.spaceharvester.app.settings;

import com.aresstack.enterpriseai.astu.source.KnowledgeSourceType;
import com.aresstack.enterpriseai.astu.source.SourceSettingField;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.betaview.BetaViewSourceProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.FtpSourceProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.JesSourceProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.git.GitSourceProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.localfiles.LocalFilesSourceProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki.MediaWikiSourceProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.NdvSourceProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.outlook.OutlookSourceProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint.SharePointSourceProvider;
import com.aresstack.spaceharvester.application.sources.SourceType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Felder, Anmeldearten und TLS je Quellentyp für das Quellenformular. Bis der Core sie am Typ liefert
 * ({@code SourceType.fields()}, {@code SourceType.authModes()}), kommen die Felder aus den Upstream-Typen der Kopie
 * {@code enterprise-ai-sources} (dieselben, die der Enterprise AI Client zeigt) und die Anmeldearten aus
 * docs/SOURCE_ADAPTERS.md. Confluence hat keine Felder (eigenes Formular).
 */
public final class SourceFields {

    /** Upstream-Typ je Typ des Core; dieselben IDs. */
    private static final Map<String, Supplier<KnowledgeSourceType>> UPSTREAM = Map.of(
            "files", LocalFilesSourceProvider::sourceType,
            "ftp", FtpSourceProvider::sourceType,
            "jes", JesSourceProvider::sourceType,
            "ndv", NdvSourceProvider::sourceType,
            "sharepoint", SharePointSourceProvider::sourceType,
            "betaview", BetaViewSourceProvider::sourceType,
            "mediawiki", MediaWikiSourceProvider::sourceType,
            "git", GitSourceProvider::sourceType,
            "outlook", OutlookSourceProvider::sourceType);

    private static final Set<String> WITH_LOGIN = Set.of("ftp", "jes", "ndv", "sharepoint", "betaview", "mediawiki");

    /**
     * Typen, die {@code client-certificate} und {@code trust-bundle} annehmen (docs/SOURCE_ADAPTERS.md); FTP, JES und
     * NDV kennen upstream kein TLS und weisen beides ab. Der Core sagt das noch nicht am Typ.
     */
    private static final Set<String> WITH_TLS = Set.of("confluence", "sharepoint", "betaview", "mediawiki");

    private SourceFields() {
    }

    /** Die Felder des Typs in seiner Reihenfolge; leer für Confluence und unbekannte Typen. */
    public static List<SourceFieldSpec> fields(SourceType type) {
        // Hier künftig: type.fields() des Core auf SourceFieldSpec abbilden (Kind und Schalter 1:1).
        Supplier<KnowledgeSourceType> upstream = type == null ? null : UPSTREAM.get(type.id());
        return upstream == null ? List.of() : upstream.get().fields().stream().map(SourceFields::spec).toList();
    }

    /** Die Anmeldearten des Typs; ohne Angabe nur „Ohne Anmeldung“. */
    public static List<AuthMode> authModes(SourceType type) {
        // Hier künftig: type.authModes() des Core.
        if (type == null) {
            return List.of(AuthMode.NONE);
        }
        if (SourceDraft.CONFLUENCE.equals(type.id()) || "git".equals(type.id())) {
            // Git: Token ohne Benutzer wie im Enterprise AI Client.
            return List.of(AuthMode.NONE, AuthMode.BASIC, AuthMode.PAT);
        }
        return WITH_LOGIN.contains(type.id()) ? List.of(AuthMode.NONE, AuthMode.BASIC) : List.of(AuthMode.NONE);
    }

    /** Ob der Typ Client-Zertifikat und eigenes Server-Vertrauen annimmt. */
    public static boolean tls(String typeId) {
        return typeId != null && WITH_TLS.contains(typeId);
    }

    /**
     * Ein Upstream-Feld als Feld des Formulars. Der Verweis {@code credentialRef} ist hier keine KeePass-Auswahl wie im
     * Enterprise AI Client, sondern ein freier Verweis an den Anbieter des Programms; er ist nie Pflicht, weil
     * „Benutzer und Passwort“ ihn ersetzt (ADR 0022), und steht deshalb unter „Erweitert“.
     */
    private static SourceFieldSpec spec(SourceSettingField field) {
        SourceFieldSpec.Kind kind = SourceFieldSpec.Kind.valueOf(field.kind().name());
        if (kind == SourceFieldSpec.Kind.SECRET_REF) {
            return new SourceFieldSpec(field.key(), "Zugangsverweis (optional)", "Verweis an den Anbieter des "
                    + "Programms, z. B. keepass:jes; nur ohne Anmeldung hier", kind, false, true, "");
        }
        return new SourceFieldSpec(field.key(), field.label(), field.hint(), kind, field.isRequired(),
                field.isAdvanced(), field.defaultValue());
    }
}
