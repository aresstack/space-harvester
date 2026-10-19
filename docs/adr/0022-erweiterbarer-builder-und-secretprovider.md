# ADR 0022: Erweiterbarer Builder und SecretProvider

- Status: **Angenommen, im Core umgesetzt** (Strang W, 19.10.2026; Auftrag von Angelo: Zertifikate, Zugangsdaten,
  Access Tokens, optionaler Azure Key Vault, Client-Zertifikate und Proxy korrekt einbinden).
- Umgesetzt: `SourceSecrets` und `Harvester.builder().secretProvider` (#188), `ConfiguredSecrets` mit Rückfall auf den
  Anbieter, `Harvester.builder().sourceAdapter` (#187), `KnowledgeSourceProviderAdapter.connected` mit
  `withoutTls`, `withCredentialRefPassThrough`, `requiringCredentials`, `acceptingToken` und `named`; MediaWiki, Git,
  FTP, JES, NDV, SharePoint und BetaView laufen darüber.
- Bezüge: #187, #188, #22 (Azure Key Vault), ADR 0015 (Konfiguration); Enterprise AI Client `AdapterAssembly`
  (Composition Root mit KeePass-Brücke), Corenth (Builder statt Framework im Core).

## Kontext

Der Core ist eine Bibliothek ohne Spring, die der Dienst und andere Programme einbetten. Quellen brauchen Zugangsdaten,
die je nach Programm aus der Konfiguration, aus KeePass oder aus einem Tresor kommen, dazu Proxy-Route und TLS je
Quelle. Die Kopien aus dem Enterprise AI Client kannten bisher nur einen Verweis `options.credentialRef`, den allein
der Anbieter des Programms auflöste; `auth`, Client-Zertifikat und Server-Vertrauen griffen dort nicht.

## Entscheidung

1. **Erweiterung über den Builder.** Ein Programm hängt Quellentypen mit `builder().sourceAdapter` und einen Anbieter
   für Zugangsdaten mit `builder().secretProvider(SourceSecrets)` ein. Der Core liest weder Umgebungsvariablen noch
   Dateien mit Geheimnissen.
2. **Eine Kette für Zugangsdaten.** Der Katalog fragt `ConfiguredSecrets`: zuerst die Werte aus der Konfiguration unter
   `source:<id>` (Anmeldung) und `source:<id>:client-certificate` (PKCS#12-Passwort), sonst den `SourceSecrets` des
   Programms; ohne Anbieter scheitert der Zugriff mit `ACCESS_DENIED`. Azure Key Vault ist kein eigener Anbieter: der
   Dienst löst `${...}` über die optionale Key-Vault-PropertySource auf, bevor die Werte ankommen; ein ungelöster
   Platzhalter verhindert den Start.
3. **Durchreichung.** Ein eigener Verweis unter `options.credentialRef` (etwa `keepass:jes`) geht bei
   `auth.type none` unverändert an den Anbieter, damit Konfigurationen aus dem Enterprise AI Client weiter gelten.
   Neben `auth.type basic` oder `pat` ist er ein Fehler, weil dann `source:<id>` gilt. Ein Verweis auf `source:…` ist
   immer ein Fehler: er holte die Zugangsdaten einer anderen Quelle und schickte sie an diesen Host.
   `auth.type pat` (Token ohne Benutzer unter `source:<id>`) gilt für Typen, die es zulassen (`acceptingToken`, Git)
   und für Confluence.
4. **Netzweg je Quelle.** `connected` baut den Upstream-Provider je Quelle mit Proxy-Route (PAC je Ziel, nie still
   DIRECT), `SSLSocketFactory` aus `trust-bundle` und `client-certificate` und dem User-Agent. Typen, deren Protokoll
   upstream kein TLS kennt (FTP, JES, NDV), lehnen beide Angaben ab statt sie still zu übergehen.
5. **Geheimnisse bleiben unsichtbar.** Material wird je Anmeldung frisch aufgelöst und danach gelöscht, nie geloggt,
   serialisiert oder in `GET /api/v1/configuration` und Exporten gezeigt; `toString` maskiert Passwörter und Tokens.

## Folgen

- Neue Typen mit Netz brauchen keinen eigenen Code für Anmeldung, Proxy und TLS.
- FTPS fehlt, solange der Upstream-Adapter es nicht kann; es käme über die Kopie, nicht lokal.
- `ContextualSourceAdapter` bleibt für Typen ohne Netz (Outlook).
