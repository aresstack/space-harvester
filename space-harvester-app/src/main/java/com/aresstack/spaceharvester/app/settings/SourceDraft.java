package com.aresstack.spaceharvester.app.settings;

import com.aresstack.spaceharvester.source.SourceSettingsConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
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
 * @param trustBundle                 Name unter {@code spring.ssl.bundle}, {@link TrustStores#WINDOWS} (Windows-Speicher
 *                                    und Java-Laufzeit) oder leer für die Java-Laufzeit
 * @param extraOptions                weitere {@code options} der Quelle, die der Assistent nicht selbst abfragt
 *                                    (z. B. Zeitgrenzen); beim Bearbeiten bleiben sie so erhalten
 */
public record SourceDraft(String id, String type, String title, String baseUrl, String directory, String include,
                          SourceSettingsConfig.AuthType authType, String username, String secretVariable,
                          SourceSettingsConfig.CertificateStoreType certificateType, String certificatePath,
                          String certificatePasswordVariable, String certificateAlias, String thumbprint,
                          String trustBundle, Map<String, String> extraOptions) {

    public static final String CONFLUENCE = "confluence";
    public static final String FILES = "files";

    /** Wie {@code ConfiguredSourceCatalog}; andere IDs weist der Core beim Start ab. */
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    private static final Pattern VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(:[^}]*)?}");
    private static final Set<String> KNOWN = Set.of("id", "type", "title", "baseurl", "auth", "clientcertificate",
            "trustbundle", "options");
    private static final Set<String> KNOWN_AUTH = Set.of("type", "username", "password", "token");
    private static final Set<String> KNOWN_CERTIFICATE = Set.of("enabled", "type", "path", "alias", "password",
            "thumbprint");

    /**
     * Ein bestehender Eintrag zum Bearbeiten.
     *
     * @param draft           die Angaben; Geheimnisse nur als Variablenname
     * @param plaintextSecrets Felder, in denen die Datei ein Geheimnis im Klartext enthält; beim Speichern steht dort
     *                        nur noch der Platzhalter
     */
    public record Editable(SourceDraft draft, List<String> plaintextSecrets) {

        public Editable {
            plaintextSecrets = List.copyOf(plaintextSecrets);
        }
    }

    public SourceDraft(String id, String type, String title, String baseUrl, String directory, String include,
                       SourceSettingsConfig.AuthType authType, String username, String secretVariable,
                       SourceSettingsConfig.CertificateStoreType certificateType, String certificatePath,
                       String certificatePasswordVariable, String certificateAlias, String thumbprint,
                       String trustBundle) {
        this(id, type, title, baseUrl, directory, include, authType, username, secretVariable, certificateType,
                certificatePath, certificatePasswordVariable, certificateAlias, thumbprint, trustBundle, Map.of());
    }

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
        extraOptions = extraOptions == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(extraOptions));
    }

    /**
     * Liest einen Eintrag aus {@code space-harvester.sources} so, wie er in der Datei steht (Platzhalter
     * unaufgelöst).
     *
     * @throws IllegalArgumentException wenn der Eintrag Schlüssel enthält, die der Assistent nicht kennt
     */
    public static Editable from(Map<?, ?> raw) {
        List<String> unknown = new ArrayList<>(unknownKeys(raw, KNOWN, ""));
        Map<?, ?> auth = AppSettingsLoader.map(AppSettingsLoader.get(raw, "auth"));
        Map<?, ?> certificate = AppSettingsLoader.map(AppSettingsLoader.get(raw, "client-certificate"));
        unknown.addAll(unknownKeys(auth, KNOWN_AUTH, "auth."));
        unknown.addAll(unknownKeys(certificate, KNOWN_CERTIFICATE, "client-certificate."));
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Der Eintrag enthält Angaben, die der Assistent nicht kennt ("
                    + String.join(", ", unknown) + "); bitte in der Datei bearbeiten (Einstellungen › Datei öffnen).");
        }
        String type = text(raw, "type");
        boolean confluence = CONFLUENCE.equals(type);
        Map<String, String> options = new TreeMap<>();
        AppSettingsLoader.map(AppSettingsLoader.get(raw, "options")).forEach((key, value) -> {
            if (value != null) {
                options.put(String.valueOf(key), String.valueOf(value));
            }
        });
        String directory = confluence ? null : options.remove("directory");
        String include = confluence ? null : options.remove("include");

        List<String> plaintext = new ArrayList<>();
        SourceSettingsConfig.AuthType authType = SourceSettingsConfig.AuthType.NONE;
        String authText = text(auth, "type");
        if (authText != null) {
            authType = SourceSettingsConfig.AuthType.valueOf(authText.toUpperCase(Locale.ROOT));
        }
        String id = text(raw, "id");
        String secretVariable = null;
        if (authType != SourceSettingsConfig.AuthType.NONE) {
            String key = authType == SourceSettingsConfig.AuthType.PAT ? "token" : "password";
            secretVariable = placeholderName(text(auth, key));
            if (secretVariable == null) {
                if (text(auth, key) != null) {
                    plaintext.add("auth." + key);
                }
                secretVariable = suggestedVariable(id, authType);
            }
        }
        SourceSettingsConfig.CertificateStoreType certificateType = null;
        String certificatePasswordVariable = null;
        boolean certificateEnabled = Boolean.parseBoolean(String.valueOf(AppSettingsLoader.get(certificate, "enabled")));
        if (!certificate.isEmpty() && !certificateEnabled) {
            // Der Assistent kennt kein abgeschaltetes Zertifikat; die ruhenden Angaben gingen beim Speichern verloren.
            throw new IllegalArgumentException("Der Eintrag enthält ein abgeschaltetes Client-Zertifikat "
                    + "(client-certificate.enabled: false); bitte in der Datei bearbeiten (Einstellungen › Datei öffnen).");
        }
        if (certificateEnabled) {
            String storeType = text(certificate, "type");
            certificateType = storeType == null ? null : SourceSettingsConfig.CertificateStoreType.valueOf(
                    storeType.toUpperCase(Locale.ROOT).replace('-', '_'));
            if (certificateType == SourceSettingsConfig.CertificateStoreType.PKCS12) {
                certificatePasswordVariable = placeholderName(text(certificate, "password"));
                if (certificatePasswordVariable == null) {
                    if (text(certificate, "password") != null) {
                        plaintext.add("client-certificate.password");
                    }
                    certificatePasswordVariable = suggestedVariable(id, SourceSettingsConfig.AuthType.PAT)
                            .replaceAll("_TOKEN$", "") + "_CLIENT_PASSWORD";
                }
            }
        }
        SourceDraft draft = new SourceDraft(id, type, text(raw, "title"), text(raw, "base-url"), directory, include,
                authType, text(auth, "username"), secretVariable, certificateType, text(certificate, "path"),
                certificatePasswordVariable, text(certificate, "alias"), text(certificate, "thumbprint"),
                text(raw, "trust-bundle"), options);
        return new Editable(draft, plaintext);
    }

    private static List<String> unknownKeys(Map<?, ?> map, Set<String> known, String prefix) {
        List<String> unknown = new ArrayList<>();
        for (Object key : map.keySet()) {
            if (!known.contains(AppSettingsLoader.normalize(String.valueOf(key)))) {
                unknown.add(prefix + key);
            }
        }
        return unknown;
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = AppSettingsLoader.get(map, key);
        return value == null ? null : trim(String.valueOf(value));
    }

    /**
     * Name der Variable, wenn der Wert genau ein Platzhalter ohne Standardwert ist, sonst {@code null}. Ein
     * Standardwert ({@code ${TOKEN:geheim}}) gilt als Klartext.
     */
    private static String placeholderName(String value) {
        if (!isBarePlaceholder(value)) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(value.strip());
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** {@code ${NAME}} oder {@code ${NAME:}}, also ein Verweis ohne mitgeschriebenen Wert. */
    static boolean isBarePlaceholder(String value) {
        if (value == null) {
            return false;
        }
        Matcher matcher = PLACEHOLDER.matcher(value.strip());
        return matcher.matches() && (matcher.group(2) == null || matcher.group(2).length() == 1);
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
        }
        if (!confluence() || !extraOptions.isEmpty()) {
            yaml.append(in).append("options:\n");
            if (!confluence()) {
                yaml.append(in).append("  directory: ").append(scalar(directory)).append('\n');
                if (include != null) {
                    yaml.append(in).append("  include: ").append(scalar(include)).append('\n');
                }
            }
            extraOptions.forEach((key, value) -> yaml.append(in).append("  ").append(key).append(": ")
                    .append(scalar(value)).append('\n'));
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
