package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.ServiceInfoQuery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verdrahtet die frameworkneutralen Anwendungsfälle aus {@code application} als Spring-Beans.
 */
@Configuration(proxyBeanMethods = false)
class ApplicationConfiguration {

    @Bean
    ServiceInfoQuery serviceInfoQuery(ObjectProvider<BuildProperties> buildProperties) {
        // Ohne Build-Informationen (z. B. Start aus der IDE ohne Gradle-Build) soll der Dienst trotzdem starten.
        BuildProperties build = buildProperties.getIfAvailable();
        return new ServiceInfoQuery(build != null ? build.getVersion() : "unknown");
    }
}
