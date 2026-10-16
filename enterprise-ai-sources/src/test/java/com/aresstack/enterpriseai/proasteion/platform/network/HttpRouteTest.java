package com.aresstack.enterpriseai.proasteion.platform.network;

import org.junit.Test;

import java.net.URI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HttpRouteTest {

    @Test
    public void proxyRouteCarriesHostPortAndReason() {
        HttpRoute route = HttpRoute.proxy(" proxy.intern.example ", 8080, "pac:resolved");
        assertTrue(route.isProxy());
        assertEquals(HttpRoute.Kind.PROXY, route.kind());
        assertEquals("proxy.intern.example", route.proxyHost());
        assertEquals(8080, route.proxyPort());
        assertEquals("PROXY proxy.intern.example:8080 (pac:resolved)", route.describe());
    }

    @Test
    public void directRouteHasNoProxy() {
        HttpRoute route = HttpRoute.direct("loopback");
        assertTrue(route.isDirect());
        assertNull(route.proxyHost());
        assertEquals(-1, route.proxyPort());
        assertEquals("DIRECT (loopback)", route.describe());
        assertEquals("DIRECT", HttpRoute.direct(null).describe());
    }

    @Test
    public void unavailableRouteNeedsAReasonAndKeepsTheDetail() {
        HttpRoute route = HttpRoute.unavailable("pac-download-failed", "HTTP 404 beim Laden des PAC-Skripts");
        assertTrue(route.isUnavailable());
        assertFalse(route.isDirect());
        assertEquals("pac-download-failed", route.reason());
        assertEquals("UNAVAILABLE (pac-download-failed): HTTP 404 beim Laden des PAC-Skripts", route.describe());
        assertEquals("", HttpRoute.unavailable("x", null).detail());
        try {
            HttpRoute.unavailable(" ", "detail");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ohne Grund keine Begründung
        }
    }

    @Test
    public void proxyRouteValidatesHostAndPort() {
        try {
            HttpRoute.proxy("", 8080, "manual");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // leerer Host
        }
        try {
            HttpRoute.proxy("proxy", 70000, "manual");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Port außerhalb 1..65535
        }
    }

    @Test
    public void routesAreValues() {
        assertEquals(HttpRoute.proxy("p", 1, "r"), HttpRoute.proxy("p", 1, "r"));
        assertEquals(HttpRoute.proxy("p", 1, "r").hashCode(), HttpRoute.proxy("p", 1, "r").hashCode());
        assertNotEquals(HttpRoute.proxy("p", 1, "r"), HttpRoute.proxy("p", 2, "r"));
        assertNotEquals(HttpRoute.direct("a"), HttpRoute.direct("b"));
        assertEquals("HttpRoute[DIRECT (a)]", HttpRoute.direct("a").toString());
    }

    @Test
    public void directPortAlwaysAnswersDirect() {
        HttpRoutePort port = HttpRoutePort.direct();
        assertTrue(port.routeFor(URI.create("https://ki.intern.example/v1/models")).isDirect());
        assertEquals("HttpRoutePort[direct]", port.toString());
    }
}
