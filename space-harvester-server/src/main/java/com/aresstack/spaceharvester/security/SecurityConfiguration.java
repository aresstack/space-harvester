package com.aresstack.spaceharvester.security;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.access.CorpusReleasePolicy;
import com.aresstack.spaceharvester.application.access.DelegatedAccessPolicy;
import com.aresstack.spaceharvester.application.access.DelegatedSourceAccess;
import com.aresstack.spaceharvester.application.access.DelegatedSourceAccessFactory;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
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

    /**
     * Laufzeitkonfiguration (ADR 0015) und Stand der Beschaffung (ADR 0016), nur für Clients mit
     * {@code manage-configuration}.
     */
    private static final String[] CONFIGURATION_PATHS = {"/api/v1/configuration", "/api/v1/configuration/**",
            "/api/v1/acquisition"};

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfiguration.class);

    @Bean
    ClientTokenAuthenticationProvider clientTokenAuthenticationProvider(SecurityProperties properties) {
        return new ClientTokenAuthenticationProvider(ClientTokens.of(
                properties.mode() == SecurityProperties.Mode.CLIENTS ? properties.clients() : List.of()));
    }

    @Bean
    DelegatedUsers delegatedUsers(SecurityProperties properties, ObjectProvider<DelegatedSourceAccess> sources,
                                  ObjectProvider<DelegatedSourceAccessFactory> factories) {
        if (properties.mode() != SecurityProperties.Mode.CLIENTS) {
            return DelegatedUsers.none();
        }
        Map<String, DelegatedSourceAccess> bySource = delegatedSources(properties.clients(), sources, factories);
        return new DelegatedUsers(DelegatedUsers.sourcesByClient(properties.clients()), bySource,
                properties.delegation().identityTtl(), properties.delegation().maxEntries(), Clock.systemUTC());
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SecurityProperties properties, Environment environment,
                                    ClientTokenAuthenticationProvider provider, DelegatedUsers delegatedUsers)
            throws Exception {
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
            Set<String> administrators = configurationAdministrators(properties.clients());
            http.addFilterBefore(new ClientTokenAuthenticationFilter(provider, delegatedUsers), AnonymousAuthenticationFilter.class)
                    .authorizeHttpRequests(requests -> requests
                            .requestMatchers(PUBLIC_PATHS).permitAll()
                            // Laufzeitkonfiguration (ADR 0015): nur Clients mit manage-configuration, nie Endnutzer.
                            .requestMatchers(CONFIGURATION_PATHS).access((authentication, context) ->
                                    new AuthorizationDecision(managesConfiguration(authentication.get(),
                                            administrators)))
                            .anyRequest().authenticated());
        }
        return http.build();
    }

    /** IDs der Clients mit {@code manage-configuration: true}. */
    static Set<String> configurationAdministrators(List<SecurityProperties.Client> clients) {
        Set<String> ids = new HashSet<>();
        for (SecurityProperties.Client client : clients) {
            if (client.manageConfiguration() && client.id() != null) {
                ids.add(client.id());
            }
        }
        return Set.copyOf(ids);
    }

    /**
     * Ob die Anmeldung die Laufzeitkonfiguration verwalten darf: nur ein technischer Client selbst, dessen ID unter
     * den Administratoren steht. Ein durchgereichter Endnutzer erbt das Recht seines Clients nicht.
     */
    static boolean managesConfiguration(Authentication authentication, Set<String> administrators) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AccessContext context
                && context.kind() == AccessContext.PrincipalKind.TECHNICAL_CLIENT
                && administrators.contains(context.principal());
    }

    @Bean
    AccessPolicy accessPolicy(SecurityProperties properties, SourceCatalog catalog,
                              ObjectProvider<DelegatedSourceAccess> sources,
                              ObjectProvider<DelegatedSourceAccessFactory> factories) {
        if (properties.mode() == SecurityProperties.Mode.DEVELOPMENT) {
            return (context, scope, kind) -> context.kind() == AccessContext.PrincipalKind.DEVELOPMENT
                    ? AccessDecision.ALLOW : AccessDecision.DENY;
        }
        if (properties.clients().isEmpty()) {
            LOG.warn("Keine technischen Clients konfiguriert (space-harvester.security.clients): "
                    + "die API verweigert jeden Datenzugriff.");
        }
        CorpusReleasePolicy corpus = new CorpusReleasePolicy(grants(properties.clients(), catalog));
        Map<String, DelegatedSourceAccess> delegated = delegatedSources(properties.clients(), sources, factories);
        if (delegated.isEmpty()) {
            return corpus;
        }
        return new DelegatedAccessPolicy(corpus, delegated, properties.delegation().decisionTtl(),
                properties.delegation().maxEntries(), Clock.systemUTC());
    }

    /**
     * Die Prüfungen für alle Quellen, für die ein Client durchreichen darf: eigens registrierte
     * {@link DelegatedSourceAccess}-Beans, sonst die einer {@link DelegatedSourceAccessFactory} (Confluence).
     *
     * @throws InvalidSecurityConfigurationException wenn eine solche Quelle keine oder mehrere Prüfungen hat
     */
    static Map<String, DelegatedSourceAccess> delegatedSources(List<SecurityProperties.Client> clients,
                                                               ObjectProvider<DelegatedSourceAccess> sources,
                                                               ObjectProvider<DelegatedSourceAccessFactory> factories) {
        Map<String, DelegatedSourceAccess> result = new LinkedHashMap<>();
        for (SecurityProperties.Client client : clients) {
            for (String sourceId : client.delegation()) {
                if (result.containsKey(sourceId)) {
                    continue;
                }
                // Eine eigens registrierte Prüfung geht der aus der Quellenart abgeleiteten vor.
                List<DelegatedSourceAccess> matching = sources.orderedStream()
                        .filter(access -> access.sourceId().equals(sourceId)).toList();
                if (matching.isEmpty()) {
                    matching = factories.orderedStream()
                            .flatMap(factory -> factory.forSource(sourceId).stream()).toList();
                }
                if (matching.size() != 1) {
                    throw new InvalidSecurityConfigurationException("Client " + client.id() + ": delegation nennt "
                            + "Quelle " + sourceId + ", für die " + (matching.isEmpty() ? "keine" : "mehr als eine")
                            + " delegierte Prüfung verfügbar ist.");
                }
                result.put(sourceId, matching.getFirst());
            }
        }
        return result;
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
