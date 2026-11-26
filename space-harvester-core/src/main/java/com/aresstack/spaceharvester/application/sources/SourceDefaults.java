package com.aresstack.spaceharvester.application.sources;

/**
 * Vorbelegung einer neuen Quelle, die ihr Quellentyp festlegt (Angelo 26.11.2026, F37-11 und SharePoint). Sie gilt nur
 * beim Anlegen; bestehende Quellen behalten ihre Einstellungen.
 *
 * @param integratedLogin       Zugangsdaten kommen standardmäßig aus der Windows-Anmeldung statt aus KeePass
 * @param includeInGlobalSearch die Quelle gehört standardmäßig zur globalen Suche; sonst nur gezielte Suche
 */
public record SourceDefaults(boolean integratedLogin, boolean includeInGlobalSearch) {

    /** KeePass bzw. die Vorgabe des Programms, globale Suche an. */
    public static final SourceDefaults STANDARD = new SourceDefaults(false, true);

    /** Wie {@link #STANDARD}, aber nur gezielte Suche (Mainframe-Quellen wie FTP, JES, NDV, BetaView). */
    public static final SourceDefaults TARGETED_SEARCH = new SourceDefaults(false, false);

    /** Wie {@link #STANDARD}, aber mit der Windows-Anmeldung (SharePoint). */
    public static final SourceDefaults INTEGRATED_LOGIN = new SourceDefaults(true, true);
}
