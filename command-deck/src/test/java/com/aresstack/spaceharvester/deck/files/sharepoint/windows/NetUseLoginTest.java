package com.aresstack.spaceharvester.deck.files.sharepoint.windows;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Nur echte Ablehnungen zählen als falsche Zugangsdaten (Schutz vor der Kontosperre). */
class NetUseLoginTest {

    @Test
    void separatesTransportFailuresFromRejectedCredentials() {
        assertThat(NetUseLogin.transportFailure(-1, "keine Antwort von „net use“")).isTrue();
        assertThat(NetUseLogin.transportFailure(2, "Systemfehler 53 aufgetreten.")).isTrue();
        assertThat(NetUseLogin.transportFailure(2, "System error 67 has occurred.")).isTrue();
        assertThat(NetUseLogin.transportFailure(2, "Systemfehler 1326 aufgetreten.")).isFalse();
        assertThat(NetUseLogin.transportFailure(2, "Systemfehler 86 aufgetreten.")).isFalse();
        assertThat(NetUseLogin.transportFailure(2, "")).isFalse();
    }
}
