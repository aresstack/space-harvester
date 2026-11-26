package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.source.SourceLocators;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.astu.source.KnowledgeSourceType;
import com.aresstack.enterpriseai.astu.source.SourceSettingField;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceProvider;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.SourceSettingsReader;

import java.util.List;

/**
 * Quelltyp „Natural (NDV)“ ({@code source.<n>.type=ndv}, Ressourcenschema {@code ndv}): Natural-Quellen aus
 * Bibliotheken eines Natural Development Servers. Schlüssel: {@code host}, {@code port} (Standard 8011 wie MainframeMate),
 * {@code credentialRef} (KeePass-Eintrag mit Benutzer und Passwort, Pflicht), {@code startPoints} (Bibliotheken,
 * optional mit Filter {@code LIB/PREFIX*}), {@code maxResources}.
 */
public final class NdvSourceProvider implements KnowledgeSourceProvider {

    public static final String TYPE_ID = "ndv";
    public static final String SOURCE_TEXT_FORMAT = "ndv-decoded-text/v1";
    public static final String SOURCE_HASH = "ndv.sourceSha256";

    private static final KnowledgeSourceType TYPE = KnowledgeSourceType.builder(TYPE_ID, NdvKnowledgeSource.SCHEME,
                    "Natural (NDV)")
            .description("Natural-Quellen (Programme, Subprogramme, Copycodes ...) aus Bibliotheken eines NDV-Servers")
            .idPrefix("natural")
            .fullIndexing(true)
            .summaryKey("host")
            .field(SourceSettingField.text("host", "Host", "Name oder Adresse des NDV-Servers").required()
                    .fromVault(SourceSettingField.VaultValue.HOST))
            .field(SourceSettingField.of("port", SourceSettingField.Kind.NUMBER, "Port", "Standard 8011")
                    .withDefault("8011").advanced())
            .field(SourceSettingField.of("credentialRef", SourceSettingField.Kind.SECRET_REF,
                    "KeePass-Eintrag", "Eintrag mit Benutzer, Passwort und Adresse des NDV-Servers").required())
            .field(SourceSettingField.text("startPoints", "Bibliotheken",
                    "Bibliotheken auswählen; bestehende Namensfilter und FDDM: bleiben unterstützt"))
            .field(SourceSettingField.of("allLibraries", SourceSettingField.Kind.FLAG,
                    "Alle Bibliotheken synchronisieren", "Ermittle die verfügbaren Bibliotheken bei jedem Abgleich")
                    .withDefault("false"))
            .field(SourceSettingField.of("navigationFill", SourceSettingField.Kind.FLAG,
                    "Beim Navigieren befüllen", "Besuchte Natural-Objekte in den gemeinsamen Wissensbestand aufnehmen")
                    .withDefault("false"))
            .field(SourceSettingField.of("maxResources", SourceSettingField.Kind.NUMBER,
                    "Höchstzahl Objekte (optional)", "Inventur bricht bei Überschreitung ab; leer = unbegrenzt").advanced())
            .build();

    private static final int DEFAULT_PORT = 8011;
    private static final int DEFAULT_MAX_RESOURCES = Integer.MAX_VALUE;

    private final SecretProvider secrets;

    /** @param secrets Security-Port für Benutzer und Passwort beim Verbindungsaufbau */
    public NdvSourceProvider(SecretProvider secrets) {
        if (secrets == null) {
            throw new IllegalArgumentException("secrets must not be null");
        }
        this.secrets = secrets;
    }

    /** Der Typ ohne Laufzeitumgebung (Beschreibung, Felder). */
    public static KnowledgeSourceType sourceType() {
        return TYPE;
    }

    @Override
    public KnowledgeSourceType type() {
        return TYPE;
    }

    @Override
    public List<String> validate(SourceSettings settings) {
        SourceSettingsReader reader = new SourceSettingsReader(TYPE, settings);
        host(reader);
        reader.integer("port", DEFAULT_PORT, 1, 65535);
        credentialRef(reader);
        scope(reader);
        return reader.problems();
    }

    @Override
    public SourceScope scope(SourceSettings settings) {
        return scope(new SourceSettingsReader(TYPE, settings));
    }

    @Override
    public KnowledgeSourcePort open(SourceAddress sourceId, SourceSettings settings) {
        SourceSettingsReader reader = new SourceSettingsReader(TYPE, settings);
        String host = host(reader);
        int port = reader.integer("port", DEFAULT_PORT, 1, 65535);
        SecretRef credentialRef = credentialRef(reader);
        SourceScope scope = scope(reader);
        boolean allLibraries = reader.bool("allLibraries", false);
        boolean navigationFill = reader.bool("navigationFill", false);
        if (reader.hasProblems() || host == null || credentialRef == null) {
            throw new IllegalArgumentException("NDV-Quelle " + sourceId + " ist fehlerhaft: " + reader.problems());
        }
        boolean ddmsEnabled = scope.startPoints().stream().anyMatch(NdvDdmScope::isStartPoint);
        return new NdvKnowledgeSource(sourceId, host, port, credentialRef, secrets, (h, p, user, password) -> {
            NdvClient client = new NdvClient();
            try {
                client.connect(h, p, user, password);
            } catch (NdvException e) {
                throw new NdvKnowledgeSource.NdvLoginException(e.getMessage(), e);
            }
            return client;
        }, ddmsEnabled, allLibraries, navigationFill, System::nanoTime);
    }

    /** {@code host:port} des NDV-Servers, klein ({@code ndv:host:8011/LIB/OBJ.NSP}). */
    @Override
    public String locator(SourceSettings settings) {
        SourceSettingsReader reader = new SourceSettingsReader(TYPE, settings);
        String host = host(reader);
        return host == null ? "" : SourceLocators.hostPort(host, reader.integer("port", DEFAULT_PORT, 1, 65535));
    }

    private static String host(SourceSettingsReader r) {
        String host = r.required("host");
        if (host != null && (host.contains("://") || host.contains("/") || host.contains("@") || host.contains(" "))) {
            r.problem("host", "nur Name oder Adresse, ohne Schema, Pfad und Zugangsdaten");
            return null;
        }
        return host;
    }

    private static SecretRef credentialRef(SourceSettingsReader r) {
        SecretRef ref = r.secretRef("credentialRef");
        if (ref == null && r.text("credentialRef", null) == null) {
            r.problem("credentialRef", "fehlt (Pflichtangabe: NDV braucht Benutzer und Passwort)");
        }
        return ref;
    }

    private static SourceScope scope(SourceSettingsReader r) {
        boolean allLibraries = r.bool("allLibraries", false);
        boolean navigationFill = r.bool("navigationFill", false);
        java.util.List<String> points = new java.util.ArrayList<String>();
        for (String entry : r.text("startPoints", "").split("[;,\\r\\n]+")) {
            if (!entry.trim().isEmpty()) {
                points.add(entry.trim());
            }
        }
        if (points.isEmpty() && !allLibraries && !navigationFill) {
            r.problem("startPoints", "Bibliotheken auswählen oder eine automatische Befüllung aktivieren");
        }
        return SourceScope.builder().automaticStartPoints().startPoints(points).maxDepth(0)
                .maxResources(r.integer("maxResources", DEFAULT_MAX_RESOURCES, 1, Integer.MAX_VALUE)).build();
    }
}
