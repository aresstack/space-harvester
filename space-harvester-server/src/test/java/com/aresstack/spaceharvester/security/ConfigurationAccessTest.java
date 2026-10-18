package com.aresstack.spaceharvester.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.UserCredential;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

/** Wer die Laufzeitkonfiguration verwalten darf (ADR 0015): nur ein technischer Client mit manage-configuration. */
class ConfigurationAccessTest {

    private static final Set<String> ADMINS = SecurityConfiguration.configurationAdministrators(List.of(
            new SecurityProperties.Client("admin", null, null, null, true),
            new SecurityProperties.Client("reader", null, null, null, false)));

    @Test
    void onlyFlaggedClientsAreAdministrators() {
        assertThat(ADMINS).containsExactly("admin");
    }

    @Test
    void theClientItselfMayButNeitherOtherClientsNorEndUsersNorAnonymous() {
        assertThat(SecurityConfiguration.managesConfiguration(
                ClientTokenAuthentication.authenticated(AccessContext.technicalClient("admin")), ADMINS)).isTrue();
        assertThat(SecurityConfiguration.managesConfiguration(
                ClientTokenAuthentication.authenticated(AccessContext.technicalClient("reader")), ADMINS)).isFalse();
        assertThat(SecurityConfiguration.managesConfiguration(ClientTokenAuthentication.authenticated(
                AccessContext.endUser("admin", "admin", new UserCredential("wiki", "t"))), ADMINS)).isFalse();
        assertThat(SecurityConfiguration.managesConfiguration(new AnonymousAuthenticationToken("k", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")), ADMINS)).isFalse();
        assertThat(SecurityConfiguration.managesConfiguration(null, ADMINS)).isFalse();
    }
}
