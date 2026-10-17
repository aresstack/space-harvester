package com.aresstack.spaceharvester.app.settings;

import com.aresstack.spaceharvester.source.SourceSettingsConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Eine neue Quelle aus dem Quellen-Assistenten, so wie sie in die Konfigurationsdatei kommt. Geheimnisse (Passwort,
 * Token, Zertifikatspasswort) stehen hier nie als Wert, sondern nur als Name der Umgebungsvariable; die Datei
 * enthält dafür einen Platzhalter {@code ${NAME}}.
 *
 * @param type                        {@code confluence} oder {@code files}
 * @param baseUrl                     Adresse der Confluence-Instanz (ohne {@code /rest}); nur bei {@code confluence}
 * @param directory                   Ordner; nur bei {@code files}
 * @param include                     Dateimuster, z. B. {@code *.md, *.pdf}, oder leer für den Standard
 * @param secretVariable              Umgebungsvariable mit Passwort bzw. Token; nur bei {@code basic} und {@code pat}
 * @param certificateType             Client-Zertifikat oder {@code null} ohne mTLS
 * @param certificatePasswordVariable Umgebungsvariable mit dem Passwort der PKCS#12-Datei
 * @param thumbprint                  Fingerabdruck des Zertifikats (kein Geheimnis) oder leer
 * @param trustBundle                 Name unter {@code spring.ssl.bundle} oder leer für die Java-Laufzeit
 */
public record SourceDraft(String id, String type, String title, String baseUrl, String directory, String include,
                          SourceSettingsConfig.AuthType authType, String username, String secretVariable,
                          SourceSettingsConfig.CertificateStoreType certificateType, String certificatePath,
                          String certificatePasswordVariable, String certificateAlias, String thumbprint,
                          String trustBundle) {

    public static final String CONFLUENCE = "confluence";
    public static final String FILES = "files";

    /** Wie {@code ConfiguredSourceCatalog}; andere IDs weist der Core beim Start ab. */
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    private static final Pattern VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    public SourceDraft {
        id = trim(id);
        type = trim(type);
        title = trim(title);
        baseUrl = trim(baseUrl);
        directory = trim(directory);
        include = trim(include);
        authType = authType == null ? SourceSettingsConfig.AuthType.NONE : authType;
        username = trim(username);
        secretVariable = trim(secretVariable);
        certificatePath = trim(certificatePath);
        certificatePasswordVariable = trim(certificatePasswordVariable);
        certificateAlias = trim(certificateAlias);
        thumbprint = trim(thumbprint);
        trustBundle = trim(trustBundle);
    }

    public boolean confluence() {
        return CONFLUENCE.equals(type);
    }

    /** Vorschlag für die Umgebungsvariable des Geheimnisses, z. B. {@code WIKI_TOKEN} für die Quelle {@code wiki}. */
    public static String suggestedVariable(String id, SourceSettingsConfig.AuthType authType) {
        String base = trim(id) == null ? "CONFLUENCE" : id.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        if (!base.isEmpty() && Character.isDigit(base.charAt(0))) {
            base = "S_" + base;
        }
        return base + (authType == SourceSettingsConfig.AuthType.BASIC ? "_PASSWORD" : "_TOKEN");
    }

    /** Was an den Eingaben fehlt oder nicht passt; leer, wenn die Quelle so in die Datei kann. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (id == null) {
            problems.add("Die ID fehlt.");
        } else if (!ID.matcher(id).matches()) {
            problems.add("Die ID darf nur Kleinbuchstaben, Ziffern und Bindestrich enthalten (höchstens 63 Zeichen).");
        }
        if (confluence()) {
            if (baseUrl == null) {
                problems.add("Die Adresse fehlt.");
            } else if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
                problems.add("Die Adresse muss mit https:// beginnen.");
            } else if (baseUrl.contains("${")) {
                problems.add("Die Adresse darf keinen Platzhalter enthalten.");
            }
            if (authType == SourceSettingsConfig.AuthType.BASIC && username == null) {
                problems.add("Der Benutzername fehlt.");
            }
            if (authType != SourceSettingsConfig.AuthType.NONE && !variable(secretVariable)) {
                problems.add("Die Umgebungsvariable für " + secretName() + " fehlt oder ist kein gültiger Name "
                        + "(Buchstaben, Ziffern, Unterstrich).");
            }
            if (certificateType == SourceSettingsConfig.CertificateStoreType.PKCS12) {
                if (certificatePath == null) {
                    problems.add("Die PKCS#12-Datei des Client-Zertifikats fehlt.");
                }
                if (!variable(certificatePasswordVariable)) {
                    problems.add("Die Umgebungsvariable für das Zertifikatspasswort fehlt oder ist kein gültiger Name.");
                }
            }
            if (certificateType != null && certificateAlias == null) {
                // Der Core verlangt den Alias immer; der Fingerabdruck prüft nur zusätzlich.
                problems.add("Der Alias des Client-Zertifikats fehlt.");
            }
        } else if (FILES.equals(type)) {
            if (directory == null) {
                problems.add("Der Ordner fehlt.");
            }
        } else {
            problems.add("Die Art der Quelle fehlt.");
        }
        return problems;
    }

    /** „Token“ oder „Passwort“. */
    public String secretName() {
        return authType == SourceSettingsConfig.AuthType.PAT ? "das Token" : "das Passwort";
    }

    /**
     * Der Listeneintrag für {@code space-harvester.sources} als YAML.
     *
     * @param indent Einrückung des Spiegelstrichs
     */
    public String yaml(String indent) {
        String in = indent + "  ";
        StringBuilder yaml = new StringBuilder();
        yaml.append(indent).append("- id: ").append(scalar(id)).append('\n');
        yaml.append(in).append("type: ").append(type).append('\n');
        if (title != null) {
            yaml.append(in).append("title: ").append(scalar(title)).append('\n');
        }
        if (confluence()) {
            yaml.append(in).append("base-url: ").append(scalar(baseUrl)).append('\n');
            yaml.append(in).append("auth:\n");
            yaml.append(in).append("  type: ").append(authType.name().toLowerCase(Locale.ROOT)).append('\n');
            if (authType == SourceSettingsConfig.AuthType.BASIC) {
                yaml.append(in).append("  username: ").append(scalar(username)).append('\n');
                yaml.append(in).append("  password: ${").append(secretVariable).append("}\n");
            } else if (authType == SourceSettingsConfig.AuthType.PAT) {
                yaml.append(in).append("  token: ${").append(secretVariable).append("}\n");
            }
            if (certificateType != null) {
                yaml.append(in).append("client-certificate:\n");
                yaml.append(in).append("  enabled: true\n");
                yaml.append(in).append("  type: ")
                        .append(certificateType == SourceSettingsConfig.CertificateStoreType.PKCS12
                                ? "pkcs12" : "windows-my").append('\n');
                if (certificateType == SourceSettingsConfig.CertificateStoreType.PKCS12) {
                    yaml.append(in).append("  path: ").append(scalar(certificatePath)).append('\n');
                    yaml.append(in).append("  password: ${").append(certificatePasswordVariable).append("}\n");
                }
                if (certificateAlias != null) {
                    yaml.append(in).append("  alias: ").append(scalar(certificateAlias)).append('\n');
                }
                if (thumbprint != null) {
                    yaml.append(in).append("  thumbprint: ").append(scalar(thumbprint)).append('\n');
                }
            }
            if (trustBundle != null) {
                yaml.append(in).append("trust-bundle: ").append(scalar(trustBundle)).append('\n');
            }
        } else {
            yaml.append(in).append("options:\n");
            yaml.append(in).append("  directory: ").append(scalar(directory)).append('\n');
            if (include != null) {
                yaml.append(in).append("  include: ").append(scalar(include)).append('\n');
            }
        }
        return yaml.toString();
    }

    private static boolean variable(String name) {
        return name != null && VARIABLE.matcher(name).matches();
    }

    /**
     * Ein Wert in doppelten Anführungszeichen; so bleiben Doppelpunkte, Rauten und Windows-Pfade unverändert. Ein
     * {@code $} wird nicht verdoppelt, weil die App selbst nur {@code ${…}} als Platzhalter liest.
     */
    static String scalar(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> quoted.append("\\\\");
                case '"' -> quoted.append("\\\"");
                case '\t' -> quoted.append("\\t");
                case '\n', '\r' -> quoted.append(' ');
                default -> quoted.append(c);
            }
        }
        return quoted.append('"').toString();
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
