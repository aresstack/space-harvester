package com.aresstack.spaceharvester.application;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

/**
 * Liefert die Selbstauskunft des Dienstes. Ohne Build-Informationen (z. B. Start aus der IDE ohne Gradle-Build)
 * wird die Version als {@code unknown} gemeldet, statt den Start zu verhindern.
 */
@Service
public class ServiceInfoQuery {

    public static final String SERVICE_NAME = "space-harvester";
    public static final String API_VERSION = "v1";

    private final String version;

    public ServiceInfoQuery(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        this.version = build != null ? build.getVersion() : "unknown";
    }

    public ServiceInfo get() {
        return new ServiceInfo(SERVICE_NAME, version, API_VERSION);
    }
}
