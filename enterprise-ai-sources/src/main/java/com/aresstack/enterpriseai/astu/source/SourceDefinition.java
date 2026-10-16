package com.aresstack.enterpriseai.astu.source;

/**
 * Eine konfigurierte (oder im Dialog entworfene) Wissensquelle: kanonische Adresse, Quelltyp, Häkchen und die
 * Einstellungen des Adapters. Die Adresse ({@code confluence://cf.intern/confluence}) ist die einzige Identität und
 * kein Benutzerfeld: beim Speichern leitet der Use Case sie aus Connector-Typ und Endpoint ab; ein Entwurf hat noch
 * keine.
 */
public final class SourceDefinition {

    private final String address;
    private final String typeId;
    private final boolean enabled;
    private final SourceSettings settings;

    public SourceDefinition(String address, String typeId, boolean enabled, SourceSettings settings) {
        this.address = address == null ? "" : address.trim();
        this.typeId = typeId == null ? "" : typeId.trim();
        this.enabled = enabled;
        this.settings = settings == null ? SourceSettings.empty() : settings;
    }

    /** Kanonische Adresse der Quelle ({@code <scheme>://<locator>}); im Entwurf leer. */
    public String address() {
        return address;
    }

    /** {@link KnowledgeSourceType#id()} des Adapters, z. B. {@code mediawiki}. */
    public String typeId() {
        return typeId;
    }

    /** Angehakt: wird indexiert und im Chat durchsucht. */
    public boolean enabled() {
        return enabled;
    }

    public SourceSettings settings() {
        return settings;
    }

    public SourceDefinition withAddress(String value) {
        return new SourceDefinition(value, typeId, enabled, settings);
    }

    public SourceDefinition withEnabled(boolean value) {
        return new SourceDefinition(address, typeId, value, settings);
    }

    public SourceDefinition withSettings(SourceSettings value) {
        return new SourceDefinition(address, typeId, enabled, value);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SourceDefinition)) {
            return false;
        }
        SourceDefinition that = (SourceDefinition) other;
        return address.equals(that.address) && typeId.equals(that.typeId) && enabled == that.enabled
                && settings.equals(that.settings);
    }

    @Override
    public int hashCode() {
        return (address.hashCode() * 31 + typeId.hashCode()) * 31 + settings.hashCode() + (enabled ? 1 : 0);
    }

    @Override
    public String toString() {
        return "SourceDefinition[" + address + ", " + typeId + (enabled ? "" : ", abgewählt") + ", " + settings + "]";
    }
}
