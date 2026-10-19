package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.source.SourceLocators;
import com.aresstack.enterpriseai.astu.source.KnowledgeSourceType;
import com.aresstack.enterpriseai.astu.source.SourceSettingField;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceProvider;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.SourceSettingsReader;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Quelltyp „JES-Jobs (Mainframe)“ ({@code source.<n>.type=jes}, Ressourcenschema {@code jes}): Ausgaben von
 * JCL-Jobs über die JES-Schnittstelle des z/OS-FTP-Servers. Schlüssel: {@code host}, {@code port} (Standard 21),
 * {@code credentialRef} (Pflicht), {@code startPoints} (Jobnamen-Filter, Standard {@code *}), {@code owner}
 * (leer = angemeldeter Benutzer), {@code status} ({@code OUTPUT}, {@code ALL}, {@code ACTIVE}, {@code INPUT};
 * Standard {@code OUTPUT}), {@code maxResources} (Standard 200), {@code encoding}; nur in der Datei:
 * {@code connectTimeoutMillis}, {@code readTimeoutMillis}.
 */
public final class JesSourceProvider implements KnowledgeSourceProvider {

    public static final String TYPE_ID = "jes";

    private static final List<String> STATUS = Arrays.asList("OUTPUT", "ALL", "ACTIVE", "INPUT");

    private static final KnowledgeSourceType TYPE = KnowledgeSourceType.builder(TYPE_ID, JesKnowledgeSource.SCHEME,
                    "JES-Jobs (Mainframe)")
            .description("Ausgaben von JCL-Jobs (JCL, Systemmeldungen, SYSPRINT) über die JES-Schnittstelle des "
                    + "z/OS-FTP-Servers")
            .fullIndexing(true)
            .idPrefix("jes")
            .summaryKey("host")
            .field(SourceSettingField.text("host", "Host", "Name oder Adresse des FTP-Servers, ohne ftp://").required()
                    .fromVault(SourceSettingField.VaultValue.HOST))
            .field(SourceSettingField.of("port", SourceSettingField.Kind.NUMBER, "Port", "Standard 21")
                    .withDefault("21").advanced())
            .field(SourceSettingField.of("credentialRef", SourceSettingField.Kind.SECRET_REF,
                    "KeePass-Eintrag", "Eintrag mit Benutzer, Passwort und Adresse des FTP-Servers").required())
            .field(SourceSettingField.text("startPoints", "Jobnamen",
                    "Kommagetrennte Filter, z. B. * oder PAY*").withDefault("*").advanced())
            .field(SourceSettingField.text("owner", "Besitzer (optional)",
                    "Jobs dieses Besitzers, * = alle; leer = eigene").advanced())
            .field(SourceSettingField.text("status", "Status", "OUTPUT (fertige Jobs), ALL, ACTIVE oder INPUT")
                    .withDefault("OUTPUT").advanced())
            .field(SourceSettingField.of("maxResources", SourceSettingField.Kind.NUMBER,
                    "Höchstzahl Jobs (optional)", "Obergrenze je Lauf; leer = 200").advanced())
            .field(SourceSettingField.text("encoding", "Zeichensatz", "Zeichensatz der Textübertragung")
                    .withDefault("ISO-8859-1").advanced())
            .build();

    private static final int DEFAULT_MAX_RESOURCES = 200;

    private final SecretProvider secrets;

    /** @param secrets Security-Port für Benutzer und Passwort beim Verbindungsaufbau */
    public JesSourceProvider(SecretProvider secrets) {
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
        connection(reader);
        status(reader);
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
        FtpConnectionSettings connection = connection(reader);
        String status = status(reader);
        String owner = reader.text("owner", null);
        if (reader.hasProblems() || connection == null || status == null) {
            throw new IllegalArgumentException("JES-Quelle " + sourceId + " ist fehlerhaft: " + reader.problems());
        }
        return new JesKnowledgeSource(sourceId, SourceLocators.hostPort(connection.host, connection.port),
                owner == null ? null : owner.toUpperCase(Locale.ROOT), status,
                new FtpSessionPool<JesFtpSession>(sourceId, connection, secrets, JesFtpSession::open));
    }

    /** {@code host:port} des FTP-Servers, klein ({@code jes:host:21/JOB01234}). */
    @Override
    public String locator(SourceSettings settings) {
        FtpConnectionSettings connection = FtpSourceProvider.connection(new SourceSettingsReader(TYPE, settings));
        return connection == null ? "" : SourceLocators.hostPort(connection.host, connection.port);
    }

    private static FtpConnectionSettings connection(SourceSettingsReader r) {
        FtpConnectionSettings connection = FtpSourceProvider.connection(r);
        if (r.text("credentialRef", null) == null) {
            r.problem("credentialRef", "fehlt (Pflichtangabe: JES braucht Benutzer und Passwort)");
        }
        return connection;
    }

    private static String status(SourceSettingsReader r) {
        String status = r.text("status", "OUTPUT").toUpperCase(Locale.ROOT);
        if (!STATUS.contains(status)) {
            r.problem("status", "muss OUTPUT, ALL, ACTIVE oder INPUT sein");
            return null;
        }
        return status;
    }

    private static SourceScope scope(SourceSettingsReader r) {
        return r.scope("*", 0, DEFAULT_MAX_RESOURCES);
    }
}
