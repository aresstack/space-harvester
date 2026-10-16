package com.aresstack.spaceharvester.security;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.access.CorpusReleasePolicy;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sichert die HTTP-API ab. Standard ist {@link SecurityProperties.Mode#CLIENTS}: alles außer Health, Info und
 * API-Dokumentation verlangt einen angemeldeten technischen Client, und was er lesen darf, entscheidet seine
 * Korpusfreigabe. Fehlerhafte Konfiguration verhindert den Start; es gibt keinen offenen Rückfall.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityConfiguration {

    static final String LOCAL_PROFILE = "local";
    static final AccessContext DEVELOPMENT_CONTEXT =
            new AccessContext("development", AccessContext.PrincipalKind.DEVELOPMENT);

    /** Ohne Anmeldung erreichbar: Probes, Build-Info und die API-Beschreibung, keine Daten. */
    private static final String[] PUBLIC_PATHS = {
            "/actuator/health", "/actuator/health/**", "/actuator/info",
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
            "/error"
    };

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfiguration.class);

    @Bean
    ClientTokenAuthenticationProvider clientTokenAuthenticationProvider(SecurityProperties properties) {
        return new ClientTokenAuthenticationProvider(ClientTokens.of(
                properties.mode() == SecurityProperties.Mode.CLIENTS ? properties.clients() : List.of()));
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SecurityProperties properties, Environment environment,
                                    ClientTokenAuthenticationProvider provider) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(ProblemResponses::unauthenticated)
                        .accessDeniedHandler(ProblemResponses::forbidden));
        if (properties.mode() == SecurityProperties.Mode.DEVELOPMENT) {
            if (!environment.acceptsProfiles(Profiles.of(LOCAL_PROFILE))) {
                throw new InvalidSecurityConfigurationException("space-harvester.security.mode=development ist nur "
                        + "mit aktivem Spring-Profil '" + LOCAL_PROFILE + "' erlaubt.");
            }
            LOG.warn("API-Zugriffsschutz AUS (mode=development, Profil local): jeder Aufrufer darf alles lesen. "
                    + "Nur für lokale Entwicklung.");
            http.anonymous(anonymous -> anonymous.principal(DEVELOPMENT_CONTEXT))
                    .authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        } else {
            http.addFilterBefore(new ClientTokenAuthenticationFilter(provider), AnonymousAuthenticationFilter.class)
                    .authorizeHttpRequests(requests -> requests
                            .requestMatchers(PUBLIC_PATHS).permitAll()
                            .anyRequest().authenticated());
        }
        return http.build();
    }

    @Bean
    AccessPolicy accessPolicy(SecurityProperties properties, SourceCatalog catalog) {
        if (properties.mode() == SecurityProperties.Mode.DEVELOPMENT) {
            return (context, scope, kind) -> context.kind() == AccessContext.PrincipalKind.DEVELOPMENT
                    ? AccessDecision.ALLOW : AccessDecision.DENY;
        }
        if (properties.clients().isEmpty()) {
            LOG.warn("Keine technischen Clients konfiguriert (space-harvester.security.clients): "
                    + "die API verweigert jeden Datenzugriff.");
        }
        return new CorpusReleasePolicy(grants(properties.clients(), catalog));
    }

    @Bean
    AccessGuard accessGuard(AccessPolicy policy) {
        return new AccessGuard(policy);
    }

    @Bean
    WebMvcConfigurer accessContextResolver() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new AccessContextArgumentResolver());
            }
        };
    }

    /**
     * @throws InvalidSecurityConfigurationException bei fehlender oder doppelter Client-ID, unbekannter Quelle, leerer
     *                                               Space- oder Artenliste oder {@code *} neben Space-Keys
     */
    static Map<String, List<CorpusReleasePolicy.Grant>> grants(List<SecurityProperties.Client> clients,
                                                                SourceCatalog catalog) {
        Map<String, List<CorpusReleasePolicy.Grant>> result = new LinkedHashMap<>();
        for (SecurityProperties.Client client : clients) {
            if (client.id() == null || client.id().isBlank()) {
                throw new InvalidSecurityConfigurationException("Jeder Client braucht eine id.");
            }
            if (result.containsKey(client.id())) {
                throw new InvalidSecurityConfigurationException("Client-ID " + client.id() + " ist doppelt.");
            }
            result.put(client.id(), client.grants().stream().map(grant -> grant(client.id(), grant, catalog)).toList());
        }
        return result;
    }

    private static CorpusReleasePolicy.Grant grant(String clientId, SecurityProperties.Grant grant,
                                                   SourceCatalog catalog) {
        String where = "Client " + clientId + ", Freigabe für Quelle " + grant.source() + ": ";
        if (grant.source() == null || catalog.find(grant.source()).isEmpty()) {
            throw new InvalidSecurityConfigurationException(where + "keine Quelle mit dieser ID konfiguriert.");
        }
        if (grant.access().isEmpty()) {
            throw new InvalidSecurityConfigurationException(where + "access nennt keine Art "
                    + "(metadata, content, export).");
        }
        if (grant.spaces().isEmpty()) {
            throw new InvalidSecurityConfigurationException(where + "spaces ist leer; Space-Keys oder \"*\" angeben.");
        }
        boolean allSpaces = grant.spaces().contains("*");
        if (allSpaces && grant.spaces().size() > 1) {
            throw new InvalidSecurityConfigurationException(where + "\"*\" steht allein, nicht neben Space-Keys.");
        }
        Set<String> spaceKeys = allSpaces ? Set.of() : new HashSet<>(grant.spaces());
        if (spaceKeys.stream().anyMatch(key -> key == null || key.isBlank())) {
            throw new InvalidSecurityConfigurationException(where + "leerer Space-Key.");
        }
        return new CorpusReleasePolicy.Grant(grant.source(), allSpaces, spaceKeys, grant.access());
    }
}
