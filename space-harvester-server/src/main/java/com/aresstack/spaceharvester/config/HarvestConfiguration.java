package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.harvest.SpaceKeyCheck;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Verdrahtet die Prüfung eines Space vor der Annahme einer Ernte (#10). */
@Configuration(proxyBeanMethods = false)
class HarvestConfiguration {

    @Bean
    SpaceKeyCheck spaceKeyCheck(SourceQueries queries) {
        return new SpaceKeyCheck(queries);
    }
}
