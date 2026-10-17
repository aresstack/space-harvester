# Client-Zertifikate (mTLS) für Confluence Data Center

Manche Confluence-Instanzen verlangen beim TLS-Verbindungsaufbau ein Client-Zertifikat. Das Zertifikat ist eine
**Geräte-/Dienstidentität auf TLS-Ebene** und ersetzt die Confluence-Anmeldung nicht: `auth` (Basic, PAT, später
OAuth) bleibt unverändert und wird zusätzlich gesendet. Proxy-Routing ist davon ebenfalls getrennt (#34).

## Konfiguration je Quelle

| Feld | Bedeutung |
|---|---|
| `client-certificate.enabled` | `true` schaltet mTLS für diese Quelle ein. Ohne oder mit `false` gilt Standard-TLS; die übrigen Angaben dürfen dann stehen bleiben. |
| `client-certificate.type` | `pkcs12` (Datei, Windows und Linux/Container) oder `windows-my` (Windows-Zertifikatsspeicher). |
| `client-certificate.alias` | Alias des Zertifikats. Er wird erzwungen: Java bietet dem Server nie ein anderes Zertifikat an. |
| `client-certificate.path` | Nur `pkcs12`: Pfad der PKCS#12-Datei (z. B. ein Secret-Mount). |
| `client-certificate.password` | Nur `pkcs12`: Passwort der Datei und ihres Schlüssels, über einen Platzhalter wie `${CONFLUENCE_CLIENT_KEYSTORE_PASSWORD}`. |
| `client-certificate.thumbprint` | Optional: SHA-1-Fingerabdruck (wie Windows ihn anzeigt) oder SHA-256, mit oder ohne Leerzeichen/Doppelpunkte. Passt das Zertifikat unter dem Alias nicht dazu, startet der Dienst nicht. |
| `trust-bundle` | Optional, auf Ebene der Quelle: Name eines Spring-SSL-Bundles mit den vertrauenswürdigen Server-/Proxy-CAs. Ohne Angabe gilt das Vertrauen der JVM. |

### PKCS#12 (empfohlen für Container und Server)

```yaml
space-harvester:
  sources:
    - id: confluence
      type: confluence
      base-url: https://confluence.example.org
      auth:
        type: pat
        token: ${CONFLUENCE_PAT}
      client-certificate:
        enabled: true
        type: pkcs12
        path: /run/secrets/confluence-client.p12
        alias: confluence-client
        password: ${CONFLUENCE_CLIENT_KEYSTORE_PASSWORD}
      trust-bundle: confluence-ca     # optional

spring:
  ssl:
    bundle:
      pem:
        confluence-ca:
          truststore:
            certificate: file:/run/secrets/corporate-ca.pem
```

- Die PKCS#12-Datei und ihr Passwort gehören **nie** ins Repository; Datei als Secret-Mount mit Leserecht nur für
  den Dienstbenutzer, Passwort über Umgebungsvariable oder Secret-Speicher.
- Das Passwort gilt für Datei und Schlüssel (bei PKCS#12 üblich).

### Windows-Zertifikatsspeicher (`windows-my`)

```yaml
      client-certificate:
        enabled: true
        type: windows-my
        alias: confluence-client
        thumbprint: "AB 12 CD ..."    # optional, zur Kontrolle
```

- `Windows-MY` ist der Speicher **`CurrentUser\My` des Kontos, unter dem der Dienst läuft**. Ein Windows-Dienst läuft
  meist unter einem anderen Konto (Dienstkonto, `LocalSystem`, gMSA) als der interaktive Benutzer. Ein Zertifikat,
  das in PowerShell unter dem eigenen Benutzer funktioniert (`-CertificateThumbprint`), ist beim Dienst deshalb
  nicht automatisch vorhanden: Es muss in den Speicher des Dienstkontos importiert werden, inklusive privatem
  Schlüssel und Leserecht darauf.
- Der Alias entspricht in Java dem Anzeigenamen (Friendly Name) des Zertifikats. Ist er nicht eindeutig, hilft der
  `thumbprint` als Kontrolle.
- `windows-my` gibt es nur mit einem Windows-JDK unter Windows. Auf Linux oder im Container meldet der Start einen
  Fehler; dort `pkcs12` verwenden.

## Vertrauen und Identität bleiben getrennt

- Das **Server-Vertrauen** (welchen Server-/Proxy-CAs der Dienst glaubt) kommt aus der JVM oder aus `trust-bundle`.
  Vom Bundle wird nur der Truststore verwendet.
- Die **Client-Identität** (Zertifikat mit privatem Schlüssel) kommt allein aus `client-certificate`. Das Setzen des
  Zertifikats ändert das Server-Vertrauen nicht; es gibt keine Trust-all-Einstellung und keine globalen
  `javax.net.ssl.*`-Änderungen.
- Das Zertifikat bekommt nur der Confluence-Host der Quelle. Weiterleitungen folgt der Adapter nur auf demselben Host;
  andere Hosts (z. B. externe Bilder) sieht er nur mit dem Server-Vertrauen, ohne Client-Zertifikat.

## Fehler

Ist mTLS eingeschaltet, prüft der Dienst das Zertifikat **beim Start** und startet nicht, wenn es fehlt, abgelaufen
oder noch nicht gültig ist, keinen privaten Schlüssel hat, der Alias nicht existiert, das Passwort falsch ist oder der
Fingerabdruck nicht passt. Die Meldung nennt Quelle, Alias, Pfad und Ursache, nie Passwort, Schlüssel- oder
Zertifikatsinhalt. Ohne verwendbares Zertifikat arbeitet eine mTLS-Quelle nie stillschweigend ohne mTLS weiter.

Lehnt der Server das Zertifikat beim Handshake ab, antwortet die API mit `source-unavailable` (503); die Ursache
steht im DEBUG-Log des Pakets `com.aresstack.spaceharvester`.

## Ablauf und Rotation

- Die Gültigkeit wird beim Start geprüft. Ein laufender Dienst erkennt ein inzwischen abgelaufenes Zertifikat erst
  am abgelehnten Handshake; Ablaufdaten deshalb überwachen und rechtzeitig erneuern.
- Rotation: neue PKCS#12-Datei bereitstellen (gleicher oder neuer Alias, Konfiguration ggf. anpassen) bzw. das neue
  Zertifikat in den Windows-Speicher des Dienstkontos importieren, dann den Dienst neu starten.

## Live-Test gegen Confluence Data Center

1. Vorab mit demselben Zertifikat und PAT von Hand prüfen, z. B. in PowerShell unter dem **Dienstkonto**:
   `Invoke-RestMethod https://confluence.example.org/rest/api/user/current -Headers @{Authorization="Bearer $Pat"} -CertificateThumbprint $Thumbprint`
   bzw. mit `curl --cert-type P12 --cert client.p12:<Passwort> -H "Authorization: Bearer $PAT" …/rest/api/user/current`.
2. Dienst mit der Quelle oben starten. Startet er nicht, nennt die Meldung das Zertifikatsproblem.
3. `GET /api/v1/sources/confluence/spaces` muss die sichtbaren Spaces liefern.
4. `GET /api/v1/sources/confluence/spaces/{spaceKey}/pages/metadata?limit=1` muss Metadaten einer berechtigten Seite
   **ohne Body** liefern.

## Live-Test im Unternehmensnetz: PAT + mTLS + PAC (#33/#34)

Das gemeinsame Gate aus dem Umsetzungsplan: dieselbe Metadatenabfrage über die vom Unternehmens-PAC gewählte Route
(meist HTTPS `CONNECT`) mit Client-Zertifikat und PAT, im **Dienstkonto**. Automatisiert ist das nur gegen lokale
Testserver geprüft (`ProxiedMutualTlsTest`); die Windows-Modi brauchen diesen manuellen Lauf.

1. Unter dem Dienstkonto die PAC-Route prüfen, ohne den Dienst zu starten. Der Lauf zeigt PAC-Adresse,
   PAC-Download und `FindProxyForURL`-Ergebnis; Exit-Code 2 heißt: Route nicht bestimmbar.

   ```powershell
   java -Dloader.main=com.aresstack.spaceharvester.source.network.ProxyDiagnosticsMain `
        -cp space-harvester.jar org.springframework.boot.loader.launch.PropertiesLauncher `
        --mode=pac-url-powershell https://confluence.example.org/
   ```

   Findet `pac-url-powershell` unter dem Dienstkonto keine `AutoConfigURL`, dieselbe Prüfung mit
   `--mode=pac-url-manual --pac-url=<PAC-Adresse des Unternehmens>` wiederholen und diesen Modus konfigurieren.
2. Dienst mit Quelle (PAT, `client-certificate`) und `space-harvester.network.proxy.mode` aus Schritt 1 starten.
   Im Log steht beim ersten Abruf `Proxy-Route für https://confluence.example.org/ (…): PROXY …` bzw. `DIRECT`.
3. Schritte 3 und 4 aus dem Abschnitt oben ausführen (Spaces, Metadaten **ohne Body**).
4. Gegenprobe Fail-Closed: mit absichtlich falscher `pac-url` starten; der Abruf muss mit
   `UNAVAILABLE (pac-download-failed)` scheitern und darf nicht direkt verbinden.
5. Asynchroner Weg (#47, Standard `space-harvester.network.async-transport: web-client`): zuerst prüfen, denn
   native Seiten-Snapshots laufen darüber. Ein Sync-Lauf (`POST /api/v1/sources/confluence/spaces/{spaceKey}/syncs`)
   oder eine echte Prüfung (`GET /api/v1/sources/confluence/pages/{pageId}?refresh=true`) muss Seiten liefern; im Log
   steht `Seiten-Snapshots: asynchron über WebClient` und die Proxy-Route wie in Schritt 2. Scheitert nur
   dieser Weg (Schritte 3 und 4 laufen synchron und klappen), als Rückfall in einer Zeile
   `space-harvester.network.async-transport: disabled` setzen und den Befund an Strang A melden.

Bekannte Grenze von `win-proxy-java` 0.2.0 ([win-proxy-java#4](https://github.com/aresstack/win-proxy-java/issues/4)):
`dnsResolve()` löst Hostnamen nicht auf, und `myIpAddress()` kann auf `127.0.0.1` zurückfallen. Nutzt das
Unternehmens-PAC `isInNet(dnsResolve(host), …)` oder `myIpAddress()`, das Ergebnis aus Schritt 1 mit der Route im
Browser des Dienstkontos vergleichen.

Nicht im Umfang: eingehendes mTLS für Aufrufer der Harvester-API und die Beantragung von Zertifikaten.
