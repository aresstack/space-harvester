package com.aresstack.spaceharvester.application.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RedactionTest {

    @Test
    void authorizationValuesAreMasked() {
        assertThat(Redaction.mask("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.e30.abc123"))
                .doesNotContain("eyJ").contains("***");
        assertThat(Redaction.mask("Basic dXNlcjpwYXNzd29yZA==")).isEqualTo("Basic ***");
        // Auch reine Buchstabenwerte: Base64 ohne Ziffern, Tokens aus Buchstaben.
        assertThat(Redaction.mask("Basic YWJjZGVm")).isEqualTo("Basic ***");
        assertThat(Redaction.mask("Bearer abcdefghijkl")).isEqualTo("Bearer ***");
        assertThat(Redaction.mask("Authorization: Bearer abcdefghijkl")).isEqualTo("Authorization: ***");
        assertThat(Redaction.mask("Proxy-Authorization: Basic YWJj")).isEqualTo("Proxy-Authorization: ***");
        assertThat(Redaction.mask("{\"Authorization\": \"Bearer abc\"}")).doesNotContain("abc");
    }

    @Test
    void namedSecretsAreMasked() {
        assertThat(Redaction.mask("password=geheim123&user=bob")).isEqualTo("password=***&user=bob");
        assertThat(Redaction.mask("{\"token\": \"t0k3n\", \"x\": 1}")).doesNotContain("t0k3n");
        assertThat(Redaction.mask("client_secret: s3cr3t")).doesNotContain("s3cr3t");
    }

    @Test
    void urlsKeepOnlySchemeHostAndPort() {
        // Benutzerangaben, Pfade mit Seiten-IDs und Abfrageparameter fallen weg; Host und Port bleiben für die Route.
        assertThat(Redaction.mask("GET https://bob:pw@wiki.intern:8443/rest/api/content/4711?os_authType=basic&t=x "
                + "scheiterte"))
                .isEqualTo("GET https://wiki.intern:8443/… scheiterte");
        assertThat(Redaction.mask("Ziel http://proxy.intern:3128")).isEqualTo("Ziel http://proxy.intern:3128");
    }

    @Test
    void longTokensAreMaskedButJobIdsAndWordsStay() {
        assertThat(Redaction.mask("Schlüssel a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8 ungültig"))
                .isEqualTo("Schlüssel *** ungültig");
        assertThat(Redaction.mask("Auftrag 0f8fad5b-d9cb-469f-a165-70867728950e"))
                .isEqualTo("Auftrag 0f8fad5b-d9cb-469f-a165-70867728950e");
        assertThat(Redaction.mask("Token abgelaufen, Basic-Anmeldung abgelehnt"))
                .isEqualTo("Token abgelaufen, Basic-Anmeldung abgelehnt");
    }

    @Test
    void nullAndEmptyStayAsTheyAre() {
        assertThat(Redaction.mask(null)).isNull();
        assertThat(Redaction.mask("")).isEmpty();
    }
}
