package com.aresstack.spaceharvester.deck.eakte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Eine E-Akte-Adresse genügt; Adressen erfunden. */
class EakteProfileTest {

    @Test
    void theOneAddressIsTheEntryAndWebDavIsFixed() {
        EakteProfile profile = EakteProfile.fromAddress(
                "https://akte.flotte.example/fsc/fscasp/content/bin/fscvext.dll?bx=COO.1.2.3", "pilot");
        assertThat(profile.start().toString())
                .isEqualTo("https://akte.flotte.example/fsc/fscasp/content/bin/fscvext.dll?bx=COO.1.2.3");
        assertThat(profile.webdav().toString()).isEqualTo("https://akte.flotte.example/fsc/webdav");
    }

    @Test
    void withoutSchemeHttpsIsAssumedAndEmptyIsRefused() {
        assertThat(EakteProfile.fromAddress("akte.flotte.example", "pilot").webdav().toString())
                .isEqualTo("https://akte.flotte.example/fsc/webdav");
        assertThatThrownBy(() -> EakteProfile.fromAddress(" ", "pilot")).hasMessageContaining("E-Akte-Adresse fehlt");
    }
}
