/**
 * Adapter: FTP-Server, vor allem MVS/z/OS, als Wissensquelle (COBOL-Quellen in PDS-Membern) und JES-Jobausgaben
 * über dieselbe FTP-Verbindungsschicht ({@code SITE FILETYPE=JES}).
 *
 * <p>Öffentlich sind nur die Quelltypen ({@link com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.FtpSourceProvider},
 * {@link com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.JesSourceProvider}); die Connector-Registry (holkas)
 * bedient die Schemata {@code ftp} und {@code jes} über den Quellen-Port, die Oberfläche sieht nur
 * „+ Quelle“. Apache Commons Net bleibt paketintern, Zugangsdaten kommen nur beim Verbindungsaufbau über den
 * Security-Port.
 *
 * <p>Herkunft: MVS-Pfade aus corenth ({@code holkas.mvs}), Sitzungsschnitt nach corenth ({@code holkas.ftp}),
 * Verbindung, MVS-Auflistung und Satzstruktur aus MainframeMate ({@code CommonsNetFtpFileService},
 * {@code RecordStructureCodec}).
 */
package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;
