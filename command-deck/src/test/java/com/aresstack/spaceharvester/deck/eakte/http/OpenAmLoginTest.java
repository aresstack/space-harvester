package com.aresstack.spaceharvester.deck.eakte.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

/** Die Rückkehr vom Identitätsdienst wie im Testprogramm v2.0; Adressen und Werte erfunden. */
class OpenAmLoginTest {

    private static final String LANDING = "<html><head><title>Please Wait While Redirecting to Login page</title>"
            + "<script>function redirectToAuth(){ var params=getQueryParameters(); "
            + "var url = 'UI/Login'; if(params != ''){url += params;} "
            + "top.location.replace(url); } "
            + "function getQueryParameters() {return location.search;}</script></head>"
            + "<body onLoad='redirectToAuth();'></body></html>";
    private static final URI API = URI.create("https://idp.flotte.example/openam/json/authenticate?realm=%2Fflotte");
    private static final URI RETURN = URI.create(
            "https://idp.flotte.example/openam/?realm=%2Fflotte&service=Login&state=opaqueState&code=opaqueCode&iss=x");

    private static Document page(String html) {
        return Jsoup.parse(html);
    }

    /** Die Zwischenseite nennt loginUrl mit :443, der Identitätsdienst leitet ohne Port zurück (Protokoll v2.0). */
    @Test
    void standardPortIsTheSameServer() {
        assertThat(OpenAmLogin.origin(URI.create("https://idp.flotte.example:443/openam/UI/Login?realm=x")))
                .isEqualTo(OpenAmLogin.origin(RETURN)).isEqualTo("https://idp.flotte.example");
        assertThat(OpenAmLogin.origin(URI.create("http://127.0.0.1:8080/openam/"))).isEqualTo("http://127.0.0.1:8080");
    }

    @Test
    void landingPageLeadsToUiLoginWithTheSameQuery() {
        URI next = OpenAmLogin.landingRedirect(API, RETURN, page(LANDING));
        assertThat(next).isNotNull();
        assertThat(next.getPath()).isEqualTo("/openam/UI/Login");
        assertThat(next.getRawQuery()).isEqualTo(RETURN.getRawQuery());
    }

    @Test
    void landingPageIsOnlyReadFromOpenAmWithCodeAndStateAndTheKnownTemplate() {
        assertThat(OpenAmLogin.landingRedirect(API, URI.create("https://evil.example/openam/?state=x&code=y"),
                page(LANDING))).isNull();
        assertThat(OpenAmLogin.landingRedirect(API, URI.create("https://idp.flotte.example/openam/?code=y"),
                page(LANDING))).isNull();
        assertThat(OpenAmLogin.landingRedirect(API, RETURN, page(LANDING.replace("UI/Login", "https://evil.example"))))
                .isNull();
        assertThat(OpenAmLogin.landingRedirect(API, URI.create("https://idp.flotte.example:443/openam/?state=a&code=b"),
                page(LANDING))).as("mit Standardport derselbe Server").isNotNull();
    }
}
