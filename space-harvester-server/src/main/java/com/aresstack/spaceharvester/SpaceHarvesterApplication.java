package com.aresstack.spaceharvester;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Einstiegspunkt des Space Harvester: ein headless Spring-Boot-Dienst, der Wissensquellen
 * (zunächst Confluence Data Center) über eine REST-API unter {@code /api/v1} erschließt.
 */
@SpringBootApplication
public class SpaceHarvesterApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpaceHarvesterApplication.class, args);
    }
}
