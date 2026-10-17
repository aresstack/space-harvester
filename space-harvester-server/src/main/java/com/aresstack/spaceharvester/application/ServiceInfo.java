package com.aresstack.spaceharvester.application;

/**
 * Selbstauskunft des Dienstes.
 *
 * @param name       Dienstname
 * @param version    Build-Version des Dienstes
 * @param apiVersion Version der REST-API
 */
public record ServiceInfo(String name, String version, String apiVersion) {
}
