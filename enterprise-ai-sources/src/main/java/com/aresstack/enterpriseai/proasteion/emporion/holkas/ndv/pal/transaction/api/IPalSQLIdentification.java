package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.transaction.api;

/**
 * Schnittstelle zur SQL-Identifikation bei der Verbindung mit dem NDV-Server.
 */
public interface IPalSQLIdentification {

    IPalTypeSQLAuthentification handleLogin(IPalTypeSQLAuthentification auth);
}
