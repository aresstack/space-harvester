package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

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

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Quelltyp „FTP (Mainframe)“ ({@code source.<n>.type=ftp}, Ressourcenschema {@code ftp}): COBOL- und andere
 * Textquellen aus MVS-Datasets bzw. Dateien eines FTP-Servers. Schlüssel: {@code host}, {@code port} (Standard 21),
 * {@code credentialRef} (KeePass-Eintrag mit Benutzer und Passwort; leer = anonym), {@code startPoints},
 * {@code maxDepth} (standardmäßig unbegrenzt), {@code maxResources}, {@code encoding} (Standard ISO-8859-1 wie MainframeMate);
 * nur in der Datei: {@code recordStructure} ({@code auto}, {@code on}, {@code off}), {@code connectTimeoutMillis},
 * {@code readTimeoutMillis}, {@code tls} ({@code none} wie bisher, {@code explicit} = {@code AUTH TLS},
 * {@code implicit} = TLS ab Verbindungsbeginn, Standardport dann 990) und {@code trustBundle} (PEM- oder DER-Datei mit
 * den vertrauten Zertifikaten; leer = Vertrauensquellen der JVM; nur zusammen mit {@code tls}).
 */
public final class FtpSourceProvider implements KnowledgeSourceProvider {

    public static final String TYPE_ID = "ftp";

    private static final KnowledgeSourceType TYPE = KnowledgeSourceType.builder(TYPE_ID, FtpKnowledgeSource.SCHEME,
                    "FTP (Mainframe)")
            .description("COBOL- und andere Textquellen aus MVS-Datasets (PDS-Member) oder Dateien eines FTP-Servers")
            .idPrefix("ftp")
            .fullIndexing(true)
            .summaryKey("host")
            .field(SourceSettingField.text("host", "Host", "Name oder Adresse des FTP-Servers, ohne ftp://").required()
                    .fromVault(SourceSettingField.VaultValue.HOST))
            .field(SourceSettingField.of("port", SourceSettingField.Kind.NUMBER, "Port", "Standard 21")
                    .withDefault("21").advanced())
            .field(SourceSettingField.of("credentialRef", SourceSettingField.Kind.SECRET_REF,
                    "KeePass-Eintrag", "Eintrag mit Benutzer, Passwort und Adresse des FTP-Servers; leer = anonym"))
            .field(SourceSettingField.text("startPoints", "HLQs / Startpunkte",
                    "Manuell, durch Semikolon oder Komma getrennt: HLQ, Dataset/PDS oder Member; leer nur bei nutzungsabhängiger Befüllung"))
            .field(SourceSettingField.of("maxDepth", SourceSettingField.Kind.NUMBER, "Tiefe",
                    "Optional: Tiefe begrenzen (0 = nur direkte Einträge; leer = vollständig)")
                    .advanced())
            .field(SourceSettingField.of("maxResources", SourceSettingField.Kind.NUMBER,
                    "Höchstzahl Member (optional)", "Optionaler Umfang; leer = keine Begrenzung").advanced())
            .field(SourceSettingField.of("navigationFill", SourceSettingField.Kind.FLAG,
                    "Beim Navigieren befüllen", "Direkt besuchte Dateien zusätzlich in den Suchindex aufnehmen")
                    .withDefault("false"))
            .field(SourceSettingField.of("programSourcesOnly", SourceSettingField.Kind.FLAG,
                    "Nur Programmquellen", "MVS: COBOL, Copybooks und JCL anhand einer Inhaltsprobe erkennen")
                    .withDefault("false"))
            .field(SourceSettingField.text("encoding", "Zeichensatz", "Zeichensatz der Textübertragung")
                    .withDefault("ISO-8859-1").advanced())
            .build();

    private static final int DEFAULT_PORT = 21;
    private static final int DEFAULT_IMPLICIT_TLS_PORT = 990;
    private static final int DEFAULT_MAX_RESOURCES = Integer.MAX_VALUE;
    private static final int MAX_TIMEOUT = 600000;

    private final SecretProvider secrets;

    /** @param secrets Security-Port für Benutzer und Passwort beim Verbindungsaufbau */
    public FtpSourceProvider(SecretProvider secrets) {
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
        scope(reader);
        reader.bool("programSourcesOnly", false);
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
        boolean programSourcesOnly = reader.bool("programSourcesOnly", false);
        if (reader.hasProblems() || connection == null) {
            throw new IllegalArgumentException("FTP-Quelle " + sourceId + " ist fehlerhaft: " + reader.problems());
        }
        return new FtpKnowledgeSource(sourceId, connection,
                new FtpSessionPool<FtpClientSession>(sourceId, connection, secrets, CommonsNetFtpSession::open), programSourcesOnly);
    }

    /** {@code host:port} des FTP-Servers, klein ({@code ftp:host:21/HLQ.DATASET(MEMBER)}). */
    @Override
    public String locator(SourceSettings settings) {
        FtpConnectionSettings connection = connection(new SourceSettingsReader(TYPE, settings));
        return connection == null ? "" : SourceLocators.hostPort(connection.host, connection.port);
    }

    /** Verbindungsteil; auch für weitere Quelltypen über FTP (JES). */
    static FtpConnectionSettings connection(SourceSettingsReader r) {
        String host = r.required("host");
        if (host != null && (host.contains("://") || host.contains("/") || host.contains("@")
                || host.trim().contains(" "))) {
            r.problem("host", "nur Name oder Adresse, ohne Schema, Pfad und Zugangsdaten");
            host = null;
        }
        FtpConnectionSettings.Tls tls = tls(r);
        int port = r.integer("port", tls == FtpConnectionSettings.Tls.IMPLICIT ? DEFAULT_IMPLICIT_TLS_PORT
                : DEFAULT_PORT, 1, 65535);
        Path trustBundle = trustBundle(r, tls);
        SecretRef credentialRef = r.secretRef("credentialRef");
        Charset encoding = charset(r);
        int connectTimeout = r.integer("connectTimeoutMillis", 15000, 1, MAX_TIMEOUT);
        int readTimeout = r.integer("readTimeoutMillis", 60000, 1, MAX_TIMEOUT);
        FtpConnectionSettings.RecordStructure structure = recordStructure(r);
        if (host == null || encoding == null || structure == null || tls == null) {
            return null;
        }
        return new FtpConnectionSettings(host, port, credentialRef, encoding, connectTimeout, readTimeout, structure,
                tls, trustBundle);
    }

    private static FtpConnectionSettings.Tls tls(SourceSettingsReader r) {
        String value = r.text("tls", "none").toLowerCase(Locale.ROOT);
        if ("none".equals(value)) {
            return FtpConnectionSettings.Tls.NONE;
        }
        if ("explicit".equals(value)) {
            return FtpConnectionSettings.Tls.EXPLICIT;
        }
        if ("implicit".equals(value)) {
            return FtpConnectionSettings.Tls.IMPLICIT;
        }
        r.problem("tls", "muss none, explicit oder implicit sein");
        return null;
    }

    /** Das Trust-Bundle, geprüft beim Lesen der Einstellungen; {@code null} ohne Angabe oder bei Fehler. */
    private static Path trustBundle(SourceSettingsReader r, FtpConnectionSettings.Tls tls) {
        Path bundle = r.path("trustBundle", null);
        if (bundle == null) {
            return null;
        }
        if (tls == FtpConnectionSettings.Tls.NONE) {
            r.problem("trustBundle", "nur zusammen mit tls=explicit oder tls=implicit");
            return null;
        }
        try {
            FtpTls.trustManager(bundle);
            return bundle;
        } catch (IOException e) {
            r.problem("trustBundle", "keine lesbare PEM- oder DER-Datei mit Zertifikaten");
            return null;
        }
    }

    private static Charset charset(SourceSettingsReader r) {
        String name = r.text("encoding", "ISO-8859-1");
        try {
            return Charset.forName(name);
        } catch (RuntimeException e) {
            r.problem("encoding", "unbekannter Zeichensatz");
            return null;
        }
    }

    private static FtpConnectionSettings.RecordStructure recordStructure(SourceSettingsReader r) {
        String value = r.text("recordStructure", "auto").toLowerCase(Locale.ROOT);
        if ("auto".equals(value)) {
            return FtpConnectionSettings.RecordStructure.AUTO;
        }
        if ("on".equals(value) || "true".equals(value)) {
            return FtpConnectionSettings.RecordStructure.ON;
        }
        if ("off".equals(value) || "false".equals(value)) {
            return FtpConnectionSettings.RecordStructure.OFF;
        }
        r.problem("recordStructure", "muss auto, on oder off sein");
        return null;
    }

    private static SourceScope scope(SourceSettingsReader r) {
        List<String> startPoints = new ArrayList<String>();
        for (String value : r.text("startPoints", "").split("[;,\\r\\n]+")) {
            String point = value.trim();
            if (!point.isEmpty()) {
                startPoints.add(point);
            }
        }
        boolean navigationFill = r.bool("navigationFill", false);
        if (startPoints.isEmpty() && !navigationFill) {
            r.problem("startPoints", "mindestens ein HLQ, Dataset oder Pfad erforderlich");
        }
        int depth = r.integer("maxDepth", Integer.MAX_VALUE, 0, Integer.MAX_VALUE);
        int resources = r.integer("maxResources", DEFAULT_MAX_RESOURCES, 1, Integer.MAX_VALUE);
        return SourceScope.builder().automaticStartPoints().startPoints(startPoints)
                .maxDepth(depth).maxResources(resources).build();
    }
}
