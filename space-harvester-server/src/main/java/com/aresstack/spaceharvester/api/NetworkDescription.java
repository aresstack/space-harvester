package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.configuration.NetworkSettings;

/**
 * Beschreibt Proxy und TLS für {@code GET /api/v1/configuration}, ohne Proxy-Passwort. Die Typen dahinter gehören
 * zum Quellenadapter, den die API nicht kennt; die Konfiguration stellt die Umsetzung bereit.
 */
@FunctionalInterface
public interface NetworkDescription {

    ConfigurationResponse.Network describe(NetworkSettings network);
}
