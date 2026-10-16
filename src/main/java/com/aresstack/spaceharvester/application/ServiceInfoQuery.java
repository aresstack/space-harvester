package com.aresstack.spaceharvester.application;

import java.util.Objects;

/**
 * Liefert die Selbstauskunft des Dienstes.
 */
public class ServiceInfoQuery {

    public static final String SERVICE_NAME = "space-harvester";
    public static final String API_VERSION = "v1";

    private final String version;

    /**
     * @param version Build-Version des Dienstes, {@code unknown} wenn keine Build-Informationen vorliegen
     */
    public ServiceInfoQuery(String version) {
        this.version = Objects.requireNonNull(version, "version");
    }

    public ServiceInfo get() {
        return new ServiceInfo(SERVICE_NAME, version, API_VERSION);
    }
}
