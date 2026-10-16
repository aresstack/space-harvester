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

Nicht im Umfang: eingehendes mTLS für Aufrufer der Harvester-API und die Beantragung von Zertifikaten.
