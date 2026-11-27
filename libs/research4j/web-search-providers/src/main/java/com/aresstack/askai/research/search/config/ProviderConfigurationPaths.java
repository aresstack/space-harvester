package com.aresstack.askai.research.search.config;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class ProviderConfigurationPaths {

    private final Path providerDirectory;

    public ProviderConfigurationPaths(Path providerDirectory) {
        if (providerDirectory == null) {
            throw new IllegalArgumentException(
                    "providerDirectory must not be null");
        }
        this.providerDirectory =
                providerDirectory.toAbsolutePath().normalize();
    }

    public static ProviderConfigurationPaths userHome() {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.trim().isEmpty()) {
            throw new IllegalStateException(
                    "System property user.home is not configured");
        }

        return new ProviderConfigurationPaths(
                Paths.get(
                        userHome,
                        "agents",
                        "research",
                        "providers"));
    }

    public Path getProviderDirectory() {
        return providerDirectory;
    }

    public Path getBraveConfigurationFile() {
        return providerDirectory.resolve("brave.json");
    }

    public Path getBrightDataConfigurationFile() {
        return providerDirectory.resolve("brightdata.json");
    }

    public Path getDataForSeoConfigurationFile() {
        return providerDirectory.resolve("dataforseo.json");
    }

    public Path getSerpApiConfigurationFile() {
        return providerDirectory.resolve("serpapi.json");
    }

    public Path getSecretKeyFile() {
        return providerDirectory.resolve(
                ".provider-secrets.key");
    }
}
