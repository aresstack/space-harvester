package com.aresstack.spaceharvester.app.settings;

import com.aresstack.spaceharvester.source.SourceSettingsConfig.AuthType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class WindowsLoginTest {

    @Test
    void requiresAnExplicitSharePointSelectionWithoutOtherCredentials() {
        assertThat(WindowsLogin.selected("sharepoint", AuthType.NONE, Map.of())).isFalse();
        assertThat(WindowsLogin.selected("sharepoint", AuthType.NONE, Map.of("windowsLogin", "true"))).isTrue();
        assertThat(WindowsLogin.selected("sharepoint", AuthType.BASIC, Map.of("windowsLogin", "true"))).isFalse();
        assertThat(WindowsLogin.selected("sharepoint", AuthType.NONE,
                Map.of("windowsLogin", "true", "credentialRef", "keepass:other"))).isFalse();
        assertThat(WindowsLogin.selected("git", AuthType.NONE, Map.of("windowsLogin", "true"))).isFalse();
    }

    @Test
    void onlyConfiguresAnOptedInWindowsProcessAndKeepsProxySettings() {
        Properties windows = new Properties();
        windows.setProperty("os.name", "Windows 11");
        WindowsLogin.configure(windows, false);
        assertThat(windows).hasSize(1);
        assertThat(WindowsLogin.conflictingOptions(windows)).isFalse();
        WindowsLogin.configure(windows, true);
        assertThat(WindowsLogin.ready(windows)).isTrue();
        assertThat(windows).containsEntry("jdk.http.ntlm.transparentAuth", "trustedHosts")
                .doesNotContainKey("java.net.useSystemProxies");

        Properties linux = new Properties();
        linux.setProperty("os.name", "Linux");
        WindowsLogin.configure(linux, true);
        assertThat(linux).hasSize(1);
        assertThat(WindowsLogin.ready(linux)).isFalse();
    }

    @Test
    void administratorOverridesAreNotSilentlyChanged() {
        Properties windows = new Properties();
        windows.setProperty("os.name", "Windows 11");
        windows.setProperty("sun.security.jgss.native", "false");
        windows.setProperty("jdk.http.ntlm.transparentAuth", "disabled");
        WindowsLogin.configure(windows, true);
        assertThat(windows).containsEntry("sun.security.jgss.native", "false")
                .containsEntry("jdk.http.ntlm.transparentAuth", "disabled");
        assertThat(WindowsLogin.ready(windows)).isFalse();
        assertThat(WindowsLogin.conflictingOptions(windows)).isTrue();
    }
}
