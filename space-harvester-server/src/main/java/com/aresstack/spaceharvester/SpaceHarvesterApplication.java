package com.aresstack.spaceharvester;

import com.aresstack.spaceharvester.source.network.ProxyAuthenticator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Einstiegspunkt des Space Harvester: ein headless Spring-Boot-Dienst, der Wissensquellen
 * (zunächst Confluence Data Center) über eine REST-API unter {@code /api/v1} erschließt.
 */
@SpringBootApplication
public class SpaceHarvesterApplication {

    public static void main(String[] args) {
        // Vor der ersten HttpURLConnection: Das JDK liest die Freigabe von BASIC im HTTPS-Tunnel nur einmal, eine
        // Proxy-Anmeldung kann aber auch erst zur Laufzeit dazukommen (ADR 0015).
        ProxyAuthenticator.allowBasicTunneling();
        SpringApplication.run(SpaceHarvesterApplication.class, args);
    }
}
