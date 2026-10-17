package com.aresstack.spaceharvester.security;

import com.aresstack.spaceharvester.application.access.AccessKind;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration unter {@code space-harvester.security}. Ohne Angabe gilt {@link Mode#CLIENTS} ohne Clients: Die API
 * verweigert dann jeden Zugriff.
 *
 * @param mode       Betriebsart
 * @param clients    technische Clients, nur bei {@link Mode#CLIENTS}
 * @param delegation Zwischenspeicher für durchgereichte Endnutzer-Anmeldungen (#24, Teil C)
 */
@ConfigurationProperties("space-harvester.security")
public record SecurityProperties(Mode mode, List<Client> clients, Delegation delegation) {

    public SecurityProperties {
        mode = mode == null ? Mode.CLIENTS : mode;
        clients = clients == null ? List.of() : List.copyOf(clients);
        delegation = delegation == null ? new Delegation(null, null, null) : delegation;
    }

    /** Betriebsart der API-Absicherung. */
    public enum Mode {
        /** Nur angemeldete technische Clients, jeweils beschränkt auf ihre Freigaben. Standard. */
        CLIENTS,
        /** Ohne Anmeldung, alles frei. Startet nur mit aktivem Spring-Profil {@code local}. */
        DEVELOPMENT
    }

    /**
     * Ein technischer Client.
     *
     * @param id          Kennung, erscheint in Logs
     * @param tokenSha256 SHA-256 des Bearer-Tokens als 64 Hex-Zeichen, optional mit Präfix {@code sha256:}; das Token
     *                    selbst steht nirgends in der Konfiguration
     * @param grants      Freigaben; ohne Freigabe darf der Client nichts lesen
     * @param delegation  Quellen-IDs, für die der Client die Anmeldung eines Endnutzers durchreichen darf (#24, Teil
     *                    C); leer: keine Durchreichung
     */
    public record Client(String id, String tokenSha256, List<Grant> grants, List<String> delegation) {

        public Client {
            grants = grants == null ? List.of() : List.copyOf(grants);
            delegation = delegation == null ? List.of() : List.copyOf(delegation);
        }
    }

    /**
     * Wie lange Antworten der Quelle zu durchgereichten Tokens gelten.
     *
     * @param identityTtl Benutzerkennung je Token, Standard 5 Minuten
     * @param decisionTtl Sichtbarkeit je Token und Space bzw. Seite, Standard 60 Sekunden; so lange wirken entzogene
     *                    Rechte nach, {@code 0s} fragt jedes Mal
     * @param maxEntries  Obergrenze je Zwischenspeicher, Standard 100000
     */
    public record Delegation(Duration identityTtl, Duration decisionTtl, Integer maxEntries) {

        public Delegation {
            identityTtl = identityTtl == null ? Duration.ofMinutes(5) : identityTtl;
            decisionTtl = decisionTtl == null ? Duration.ofSeconds(60) : decisionTtl;
            maxEntries = maxEntries == null ? 100_000 : maxEntries;
        }
    }

    /**
     * Freigabe eines Ausschnitts einer Quelle.
     *
     * @param source Quellen-ID aus {@code space-harvester.sources}
     * @param spaces Space-Keys oder genau {@code "*"} für alle Spaces der Quelle
     * @param access freigegebene Arten: {@code metadata}, {@code content}, {@code export}, {@code sync}
     */
    public record Grant(String source, List<String> spaces, Set<AccessKind> access) {

        public Grant {
            spaces = spaces == null ? List.of() : List.copyOf(spaces);
            access = access == null ? Set.of() : Set.copyOf(access);
        }
    }
}
