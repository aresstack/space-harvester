/**
 * Konfiguration zur Laufzeit (ADR 0015): ein typisierter, versionierter Schnappschuss ({@link
 * com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration}) und ein Dienst, der eine neue
 * Konfiguration prüft, von den Beobachtern vorbereiten lässt und atomar aktiviert ({@link
 * com.aresstack.spaceharvester.application.configuration.ConfigurationManager}). Frameworkneutral: Server
 * (Spring-Properties, Config Server) und Desktop-App (YAML-Datei, Tresor) sind Adapter, die eine Konfiguration
 * übergeben; der Core liest weder Dateien noch Umgebungsvariablen.
 */
package com.aresstack.spaceharvester.application.configuration;
