package com.aresstack.spaceharvester.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** #571: Webaufrufe des Confluence-Transports im Protokoll, ohne Query-Werte. */
class WebCallLogTest {

    @Test
    void theAddressKeepsPathAndParameterNamesButNoValues() {
        String described = WebCallLog.describe(URI.create(
                "https://wiki.example.org:8443/plugins/servlet/mo2fa?atl_token=s3cr3t&os_destination=%2Fdownload%2Fx"
                        + "&flag"));

        assertThat(described).isEqualTo(
                "https://wiki.example.org:8443/plugins/servlet/mo2fa parameter=[atl_token, os_destination, flag]");
        assertThat(described).doesNotContain("s3cr3t").doesNotContain("download");
    }

    @Test
    void everyAddressStillGetsItsRouteFromTheWrappedPort() {
        List<URI> asked = new ArrayList<>();
        HttpRoutePort routes = target -> {
            asked.add(target);
            return HttpRoute.direct("direct");
        };
        WebCallLog log = new WebCallLog(routes, "wiki, PAT");
        URI rest = URI.create("https://wiki.example.org/rest/api/content/1");
        URI file = URI.create("https://wiki.example.org/download/attachments/1/a.png");

        log.routeFor(rest);
        log.routeFor(file);

        assertThat(asked).containsExactly(rest, file);
    }
}
