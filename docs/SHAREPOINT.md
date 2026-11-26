# Native SharePoint-Wissensquelle

Die Quelle mit Typ sharepoint greift direkt über die SharePoint-REST-API auf eine Site zu. Die Option siteUrl bezeichnet die Site (nicht die Dokumentbibliothek oder eine Ansichtsseite). Optional begrenzen startPoints, maxDepth und maxResources den Inventur-Umfang. Ohne explizite Tiefen- und Anzahlgrenze wird der konfigurierte Bestand vollständig inventarisiert. maxFileMegabytes begrenzt den Originalabruf; Standard sind 50 MiB.

## Integrierte Windows-Anmeldung

Im Desktop-Quellenformular bei SharePoint unter „Zugangsdaten beziehen über“ die **Windows-Anmeldung** auswählen, die Site-URL eintragen und die Quelle speichern. Kein Benutzername, Passwort oder KeePass-Verweis ist erforderlich. Die Auswahl speichert `options.windowsLogin: "true"` ohne `auth`-Zugangsdaten oder `credentialRef`. Danach die Anwendung beim ersten Einrichten vollständig neu starten und „Verbindung testen“ verwenden. Ein bloßes Neuladen der Konfiguration genügt nicht. Die bestehende HttpURLConnection-Strategie nutzt die JDK-Authentifizierung. Ein separater Kerberos- oder NTLM-Modus ist nicht erforderlich.

Die folgenden JVM-Optionen wurden mit Red Hat OpenJDK 21 unter Windows erfolgreich an der REST-API getestet:

    -Dsun.security.jgss.native=true
    -Djavax.security.auth.useSubjectCredsOnly=false
    -Djdk.http.ntlm.transparentAuth=trustedHosts
    -Djava.net.useSystemProxies=true

Die Desktop-App setzt nach expliziter Auswahl der Windows-Anmeldung beim nächsten vollständigen Start unter Windows die ersten drei Optionen, sofern keine Startvorgabe existiert. Sie gelten prozessweit, auch für andere JDK-HTTP-Verbindungen; das Quellenformular weist darauf hin. Administratorvorgaben bleiben unverändert. Die vorhandene Proxy-Konfiguration des Space Harvesters bleibt maßgeblich; `java.net.useSystemProxies` wird nicht automatisch gesetzt. Für eingebettete Nutzung oder den Server gelten weiterhin die expliziten JVM-Startparameter vor `-jar`; der Quelladapter ändert sie nicht. Windows-Vertrauenszonen und Netzwerk- und Sicherheitsrichtlinien bleiben maßgeblich. Verwende keinen `allHosts`-Modus. Die JDK-Dateien `sspi_bridge.dll` und `j2gss.dll` sind bei der getesteten Installation bereits enthalten. JNA ist nicht erforderlich. Die vollständige Desktop-Verbindungsprüfung mit der echten SharePoint-Instanz steht noch aus.

## Umfang der ersten Implementierung

* Verbindungsprüfung, Bibliotheken, Ordnernavigation und native Suche über REST.
* OData-Folgeseiten unter derselben Site und demselben Endpunkt.
* Originaldateien mit Prüfung der Revision und Dateigröße; gemeinsamer Cache und Textprojektion.
* Vorhandene Seiten-IDs bleiben erhalten.

Die Navigation lädt die direkten Kinder eines Ordners, einschließlich aller zugehörigen REST-Folgeseiten. Die erste Implementierung hat noch keine eigene UI-Paginierung für sehr große einzelne Ordner. Die inkrementelle Aktualisierung verwendet SharePoint GetChanges mit listenspezifischen ChangeTokens, abgeleitet vom gespeicherten Synchronisationszeitpunkt. Bei nicht unterstützten Endpunkten, ungültigen Tokens oder strukturellen Änderungen erfolgt eine vollständige Inventur. Löschungen werden konservativ über den Inventarabgleich festgestellt; ein exakter serverseitiger ChangeToken wird nicht dauerhaft gespeichert. Die vollständige Abnahme gegen den realen SharePoint steht noch aus. Die explizite Auswahl zwischen Kerberos und NTLM bleibt eine optionale spätere Erweiterung.
