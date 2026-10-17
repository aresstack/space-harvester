package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.compat.ContentListing;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.query.PageSearch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verdrahtet die Seitenlisten der Confluence-kompatiblen Fassade (#31) auf Suche und Einzelseite der Query-API.
 */
@Configuration(proxyBeanMethods = false)
class ConfluenceCompatConfiguration {

    @Bean
    ContentListing contentListing(PageSearch search, PageQueries pages, AccessGuard access,
            @Value("${space-harvester.compat.max-response-bytes:" + ContentListing.DEFAULT_MAX_RESPONSE_BYTES + "}")
            long maxResponseBytes) {
        return new ContentListing(search, pages, access, maxResponseBytes);
    }
}
