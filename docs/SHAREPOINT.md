# Native SharePoint-Wissensquelle

Die Quelle mit Typ sharepoint greift direkt über die SharePoint-REST-API auf eine Site zu. Die Option siteUrl bezeichnet die Site (nicht die Dokumentbibliothek oder eine Ansichtsseite). Optional begrenzen startPoints, maxDepth und maxResources den Inventur-Umfang. Ohne explizite Tiefen- und Anzahlgrenze wird der konfigurierte Bestand vollständig inventarisiert. maxFileMegabytes begrenzt den Originalabruf; Standard sind 50 MiB.

## Integrierte Windows-Anmeldung

Bei einer Site mit integrierter Windows-Anmeldung keine Zugangsdaten für die Quelle hinterlegen. Die bestehende HttpURLConnection-Strategie nutzt die JDK-Authentifizierung. Ein separater Kerberos- oder NTLM-Modus ist nicht erforderlich.

Die folgenden JVM-Optionen wurden mit Red Hat OpenJDK 21 unter Windows erfolgreich an der REST-API getestet:

    -Dsun.security.jgss.native=true
    -Djavax.security.auth.useSubjectCredsOnly=false
    -Djdk.http.ntlm.transparentAuth=trustedHosts
    -Djava.net.useSystemProxies=true

Diese Optionen beim Java-Start vor -jar angeben. Sie gelten für die gesamte JVM und werden deshalb nicht automatisch durch den Adapter gesetzt. Die Windows-Vertrauenszonen und die bestehenden Netzwerk- und Sicherheitsrichtlinien bleiben maßgeblich. Verwende keinen allHosts-Modus. Die JDK-Dateien sspi_bridge.dll und j2gss.dll sind bei der getesteten Installation bereits enthalten. JNA ist nicht erforderlich.

## Umfang der ersten Implementierung

* Verbindungsprüfung, Bibliotheken, Ordnernavigation und native Suche über REST.
* OData-Folgeseiten unter derselben Site und demselben Endpunkt.
* Originaldateien mit Prüfung der Revision und Dateigröße; gemeinsamer Cache und Textprojektion.
* Vorhandene Seiten-IDs bleiben erhalten.

Die Navigation lädt die direkten Kinder eines Ordners, einschließlich aller zugehörigen REST-Folgeseiten. Die erste Implementierung hat noch keine eigene UI-Paginierung für sehr große einzelne Ordner. Die inkrementelle Aktualisierung verwendet SharePoint GetChanges mit listenspezifischen ChangeTokens, abgeleitet vom gespeicherten Synchronisationszeitpunkt. Bei nicht unterstützten Endpunkten, ungültigen Tokens oder strukturellen Änderungen erfolgt eine vollständige Inventur. Löschungen werden konservativ über den Inventarabgleich festgestellt; ein exakter serverseitiger ChangeToken wird nicht dauerhaft gespeichert. Die vollständige Abnahme gegen den realen SharePoint steht noch aus. Die explizite Auswahl zwischen Kerberos und NTLM bleibt eine optionale spätere Erweiterung.
