package com.aresstack.spaceharvester.application.sources;

/**
 * Ein Schritt der Verbindungsdiagnose einer Quelle (#189).
 *
 * @param message was geprüft wurde und mit welchem Ergebnis; ohne Geheimnisse
 * @param hint    was zu tun ist, nur bei {@link Status#FAILED} oder {@link Status#WARNING}; sonst {@code null}
 */
public record ConnectionStep(Stage stage, Status status, String message, String hint) {

    /** Die Stufen in ihrer Reihenfolge. */
    public enum Stage {
        PROXY_ROUTE("Proxy-Route"),
        DNS("Namensauflösung"),
        TCP("TCP-Verbindung"),
        TLS("TLS"),
        AUTHENTICATION("Anmeldung"),
        API("API");

        private final String label;

        Stage(String label) {
            this.label = label;
        }

        /** Name für Benutzer. */
        public String label() {
            return label;
        }
    }

    public enum Status { OK, INFO, WARNING, FAILED, SKIPPED }

    public static ConnectionStep ok(Stage stage, String message) {
        return new ConnectionStep(stage, Status.OK, message, null);
    }

    public static ConnectionStep info(Stage stage, String message) {
        return new ConnectionStep(stage, Status.INFO, message, null);
    }

    public static ConnectionStep skipped(Stage stage, String message) {
        return new ConnectionStep(stage, Status.SKIPPED, message, null);
    }

    public static ConnectionStep failed(Stage stage, String message, String hint) {
        return new ConnectionStep(stage, Status.FAILED, message, hint);
    }

    public boolean failed() {
        return status == Status.FAILED;
    }
}
