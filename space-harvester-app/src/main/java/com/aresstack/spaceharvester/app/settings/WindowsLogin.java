package com.aresstack.spaceharvester.app.settings;

import com.aresstack.spaceharvester.source.SourceSettingsConfig;

import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Configure explicitly opted-in Windows authentication before the desktop opens network connections. */
public final class WindowsLogin {

    public static final String SHAREPOINT = "sharepoint";
    public static final String OPTION = "windowsLogin";

    private static final Map<String, String> REQUIRED = Map.of(
            "sun.security.jgss.native", "true",
            "javax.security.auth.useSubjectCredsOnly", "false",
            "jdk.http.ntlm.transparentAuth", "trustedHosts");

    private WindowsLogin() {
    }

    public static boolean selected(String type, SourceSettingsConfig.AuthType auth, Map<String, String> options) {
        return SHAREPOINT.equals(type) && auth == SourceSettingsConfig.AuthType.NONE
                && Boolean.parseBoolean(options.get(OPTION))
                && options.getOrDefault("credentialRef", "").isBlank();
    }

    public static void configureAtStartup(AppSettings settings) {
        boolean optedIn = settings.sources().stream().anyMatch(entry -> {
            SourceSettingsConfig config = entry.config();
            return selected(config.type(), config.auth().type(), config.options())
                    && settings.keePassEntry(config.id()) == null;
        });
        configure(System.getProperties(), optedIn);
    }

    static void configure(Properties properties, boolean optedIn) {
        if (!optedIn || !windows(properties)) {
            return;
        }
        // Respect administrator-supplied JVM options and the Windows trusted-host policy.
        REQUIRED.forEach(properties::putIfAbsent);
    }

    public static boolean available() {
        return windows(System.getProperties());
    }

    public static boolean ready() {
        return ready(System.getProperties());
    }

    static boolean ready(Properties properties) {
        return windows(properties) && REQUIRED.entrySet().stream()
                .allMatch(entry -> entry.getValue().equalsIgnoreCase(properties.getProperty(entry.getKey())));
    }

    public static boolean conflictingOptions() {
        return conflictingOptions(System.getProperties());
    }

    static boolean conflictingOptions(Properties properties) {
        return REQUIRED.entrySet().stream().anyMatch(entry -> {
            String supplied = properties.getProperty(entry.getKey());
            return supplied != null && !entry.getValue().equalsIgnoreCase(supplied);
        });
    }

    private static boolean windows(Properties properties) {
        return properties.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
