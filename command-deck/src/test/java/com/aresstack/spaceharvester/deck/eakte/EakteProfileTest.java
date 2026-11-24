package com.aresstack.spaceharvester.deck.eakte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Basisadresse aus KeePass plus WebDAV-Pfad; Adressen erfunden. */
class EakteProfileTest {

    @Test
    void baseAddressGetsTheDefaultPathAndIsEntryAndWebDav() {
        EakteProfile profile = EakteProfile.fromAddress("https://akte.flotte.example", "", "pilot");
        assertThat(profile.webdav().toString()).isEqualTo("https://akte.flotte.example/fsc/webdav");
        assertThat(profile.start()).isEqualTo(profile.webdav());
    }

    @Test
    void aCompleteWebDavAddressDoesNotGetThePathTwice() {
        assertThat(EakteProfile.fromAddress("https://akte.flotte.example/fsc/webdav", null, "pilot").webdav())
                .hasToString("https://akte.flotte.example/fsc/webdav");
        assertThat(EakteProfile.fromAddress("https://akte.flotte.example/fsc/webdav/", "/fsc/webdav/", "pilot")
                .webdav()).hasToString("https://akte.flotte.example/fsc/webdav");
        assertThat(EakteProfile.fromAddress("https://akte.flotte.example/", "", "pilot").webdav())
                .hasToString("https://akte.flotte.example/fsc/webdav");
    }

    @Test
    void anotherPathCanBeSetAndPortStays() {
        assertThat(EakteProfile.fromAddress("http://127.0.0.1:8123", "dav", "pilot").webdav())
                .hasToString("http://127.0.0.1:8123/dav");
    }

    @Test
    void withoutSchemeHttpsIsAssumedAndBadInputIsRefused() {
        assertThat(EakteProfile.fromAddress("akte.flotte.example", "", "pilot").webdav())
                .hasToString("https://akte.flotte.example/fsc/webdav");
        assertThatThrownBy(() -> EakteProfile.fromAddress(" ", "", "pilot")).hasMessageContaining("E-Akte-Adresse fehlt");
        assertThatThrownBy(() -> EakteProfile.webdavPath("https://x/y")).hasMessageContaining("WebDAV-Pfad");
    }
}
