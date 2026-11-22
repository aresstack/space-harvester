package com.aresstack.spaceharvester.deck.access;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Die Adresse aus dem URL-Feld eines KeePass-Eintrags (erfundene Werte). */
class ServerAccountTest {

    @Test
    void hostAndPortComeFromTheLocation() {
        assertThat(new ServerAccount("Host A", "10.0.0.7", "u").host()).isEqualTo("10.0.0.7");
        assertThat(new ServerAccount("Host A", "10.0.0.7", "u").port()).isEmpty();
        assertThat(new ServerAccount("Host B", "tn3270://host.example:992", "").host()).isEqualTo("host.example");
        assertThat(new ServerAccount("Host B", "tn3270://host.example:992", "").port()).hasValue(992);
        assertThat(new ServerAccount("Host C", "https://host.example/login", "").host()).isEqualTo("host.example");
        assertThat(new ServerAccount("Host D", "host.example:23", "").port()).hasValue(23);
        assertThat(new ServerAccount("Host E", "", "").reachable()).isFalse();
        assertThat(new ServerAccount("Host F", "10.0.0.7", "").toString()).isEqualTo("Host F (10.0.0.7)");
    }
}
