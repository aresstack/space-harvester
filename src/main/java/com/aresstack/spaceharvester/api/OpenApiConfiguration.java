package com.aresstack.spaceharvester.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    OpenAPI spaceHarvesterOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        return new OpenAPI().info(new Info()
                .title("Space Harvester API")
                .version(build != null ? build.getVersion() : "unknown")
                .description("Erfasst Wissensquellen (zunächst Confluence Data Center): Spaces auflisten, "
                        + "Seitenmetadaten mit Hashwerten abfragen und Spaces asynchron als JSON/NDJSON exportieren.")
                .license(new License().name("MIT")));
    }
}
