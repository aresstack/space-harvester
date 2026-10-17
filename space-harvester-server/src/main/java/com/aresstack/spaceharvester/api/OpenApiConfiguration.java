package com.aresstack.spaceharvester.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    private static final String BEARER_AUTH = "bearerAuth";

    @Bean
    OpenAPI spaceHarvesterOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        return new OpenAPI().info(new Info()
                .title("Space Harvester API")
                .version(build != null ? build.getVersion() : "unknown")
                .description("Erfasst Wissensquellen (zunächst Confluence Data Center): Spaces auflisten, "
                        + "Seitenmetadaten mit Hashwerten abfragen und Spaces asynchron als JSON/NDJSON exportieren.")
                .license(new License().name("MIT")))
                .components(new Components().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer")
                        .description("Token eines technischen Clients aus space-harvester.security.clients")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
