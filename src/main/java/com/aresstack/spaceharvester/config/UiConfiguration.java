package com.aresstack.spaceharvester.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Liefert die optionale Weboberfläche (#17) als statische Dateien aus dem JAR unter {@code /ui/} aus. Sie spricht nur
 * die REST-API mit dem Bearer-Token an, das der Benutzer zur Laufzeit eingibt, und enthält selbst keine Daten oder
 * Zugangsdaten. Mit {@code space-harvester.ui.enabled=false} gibt es sie nicht.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "space-harvester.ui.enabled", havingValue = "true", matchIfMissing = true)
class UiConfiguration implements WebMvcConfigurer {

    /** Nur Dateien aus diesem JAR, keine fremden Skripte, Styles, Ziele oder Einbettung. */
    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data: blob:; connect-src 'self'; object-src 'none'; base-uri 'self'; "
            + "form-action 'self'; frame-ancestors 'none'";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/ui/**").addResourceLocations("classpath:/ui/");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/ui", "/ui/");
        registry.addViewController("/ui/").setViewName("forward:/ui/index.html");
    }

    /**
     * Eigene Filterkette nur für {@code /ui/**}: die statischen Dateien sind ohne Token ladbar und tragen eine strenge
     * CSP. Die Kette der API bleibt unverändert, jeder Datenzugriff braucht weiter das Bearer-Token.
     */
    @Bean
    @Order(0)
    SecurityFilterChain uiSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher("/ui", "/ui/**")
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                        .ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }
}
