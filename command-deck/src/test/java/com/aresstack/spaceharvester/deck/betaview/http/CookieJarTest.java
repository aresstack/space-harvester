package com.aresstack.spaceharvester.deck.betaview.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Cookie-Regeln wie im Browser (RFC 6265); alle Hosts erfunden. */
class CookieJarTest {

    private static final URI LOGIN = URI.create("https://beta.example.com/betaview/login.action");

    @Test
    void parentDomainWithOrWithoutDotIsAcceptedButNeverSentElsewhere() {
        CookieJar jar = new CookieJar();
        jar.store(LOGIN, List.of("SID=geheim; Domain=example.com; Path=/", "ALT=1; Domain=.example.com; Path=/"));
        assertThat(jar.header(URI.create("https://beta.example.com/betaview/x"))).contains("SID=geheim")
                .contains("ALT=1");
        assertThat(jar.header(URI.create("https://andere.example.com/"))).contains("SID=geheim");
        assertThat(jar.header(URI.create("https://example.org/"))).isEmpty();
        assertThat(jar.header(URI.create("https://boese-example.com/"))).isEmpty();
    }

    @Test
    void foreignDomainsAndBareSuffixesAreRejected() {
        CookieJar jar = new CookieJar();
        jar.store(LOGIN, List.of("A=1; Domain=fremd.example.org", "B=2; Domain=com"));
        assertThat(jar.header(URI.create("https://fremd.example.org/"))).isEmpty();
        assertThat(jar.header(LOGIN)).isEmpty();
    }

    @Test
    void hostOnlyCookiesStayOnTheirHost() {
        CookieJar jar = new CookieJar();
        jar.store(LOGIN, List.of("SID=geheim"));
        assertThat(jar.header(URI.create("https://beta.example.com/betaview/seite.action"))).isEqualTo("SID=geheim");
        assertThat(jar.header(URI.create("https://andere.example.com/betaview/"))).isEmpty();
        assertThat(jar.header(URI.create("https://beta.example.com/admin/"))).as("Vorgabepfad /betaview").isEmpty();
    }

    @Test
    void moreSpecificPathsComeFirstAndMaxAgeZeroDeletes() {
        CookieJar jar = new CookieJar();
        jar.store(LOGIN, List.of("JSESSIONID=alt; Path=/"));
        jar.store(LOGIN, List.of("JSESSIONID=neu; Path=/betaview"));
        assertThat(jar.header(URI.create("https://beta.example.com/betaview/x")))
                .isEqualTo("JSESSIONID=neu; JSESSIONID=alt");
        jar.store(LOGIN, List.of("JSESSIONID=weg; Path=/betaview; Max-Age=0"));
        assertThat(jar.header(URI.create("https://beta.example.com/betaview/x"))).isEqualTo("JSESSIONID=alt");
    }

    @Test
    void secureOnlyOverHttps() {
        CookieJar jar = new CookieJar();
        jar.store(LOGIN, List.of("T=1; Path=/; Secure"));
        assertThat(jar.header(URI.create("http://beta.example.com/"))).isEmpty();
        assertThat(jar.header(URI.create("https://beta.example.com/"))).isEqualTo("T=1");
    }
}
