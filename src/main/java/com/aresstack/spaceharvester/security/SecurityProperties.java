package com.aresstack.spaceharvester.security;

import com.aresstack.spaceharvester.application.access.AccessKind;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration unter {@code space-harvester.security}. Ohne Angabe gilt {@link Mode#CLIENTS} ohne Clients: Die API
 * verweigert dann jeden Zugriff.
 *
 * @param mode    Betriebsart
 * @param clients technische Clients, nur bei {@link Mode#CLIENTS}
 */
@ConfigurationProperties("space-harvester.security")
public record SecurityProperties(Mode mode, List<Client> clients) {

    public SecurityProperties {
        mode = mode == null ? Mode.CLIENTS : mode;
        clients = clients == null ? List.of() : List.copyOf(clients);
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
     */
    public record Client(String id, String tokenSha256, List<Grant> grants) {

        public Client {
            grants = grants == null ? List.of() : List.copyOf(grants);
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
