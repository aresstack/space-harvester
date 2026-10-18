package com.aresstack.spaceharvester.app.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.source.network.ProxySettings;
import org.junit.jupiter.api.Test;

/** Die Datei nennt nur den Proxy-Benutzer; das Passwort kommt aus Tresor oder Sitzung erst beim Start dazu (#189). */
class AppRuntimeProxyTest {

    private static final ProxySettings PAC = new ProxySettings(ProxySettings.Mode.PAC_URL_POWERSHELL, null, null, null,
            null, null, null, null);

    @Test
    void theSessionPasswordJoinsTheProxyUserFromTheFile() {
        AppRuntime.SessionSecrets secrets = new AppRuntime.SessionSecrets();
        char[] typed = "geheim".toCharArray();
        secrets.proxyPassword(typed);
        assertThat(typed).as("Eingabe geleert").containsOnly('\0');

        ProxySettings proxy = AppRuntime.proxy(PAC.withAuthentication(ProxySettings.Authentication.basic("nutzer")),
                secrets);

        assertThat(proxy.authentication().username()).isEqualTo("nutzer");
        assertThat(proxy.authentication().password()).containsExactly("geheim".toCharArray());
        assertThat(proxy.toString()).doesNotContain("geheim");
        assertThat(secrets.toString()).doesNotContain("geheim");
    }

    @Test
    void withoutProxyUserOrPasswordNothingIsAdded() {
        AppRuntime.SessionSecrets secrets = new AppRuntime.SessionSecrets();
        secrets.proxyPassword("geheim".toCharArray());
        assertThat(AppRuntime.proxy(PAC, secrets)).as("ohne Benutzer keine Anmeldung").isEqualTo(PAC);

        ProxySettings onlyUser = PAC.withAuthentication(ProxySettings.Authentication.basic("nutzer"));
        assertThat(AppRuntime.proxy(onlyUser, new AppRuntime.SessionSecrets())).isEqualTo(onlyUser);
    }
}
