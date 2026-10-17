# Secrets: YAML, Umgebungsvariablen, Azure Key Vault

**Stand:** 16.10.2026 · Issue [#22](https://github.com/aresstack/space-harvester/issues/22), Anmeldearten
[#24](https://github.com/aresstack/space-harvester/issues/24), Zugriffsschutz der eigenen API: [SECURITY.md](SECURITY.md).

Zugangsdaten der Quellen (`auth.password`, `auth.token`, `client-certificate.password`) kommen aus der normalen
Spring-Konfiguration. Azure Key Vault ist eine **optionale** zusätzliche Quelle für Platzhalter. Ohne ihn braucht
der Dienst weder Azure-Zugang noch Azure CLI.

## Varianten

**A – direkter Wert in einer lokalen, nicht eingecheckten Datei** (z. B. `config/application-local.yml`, Profil
`local`). Kein Kontakt zu Azure.

```yaml
space-harvester:
  sources:
    - id: confluence
      type: confluence
      base-url: https://confluence.example.org
      auth:
        type: pat
        token: "local-development-token"   # nur lokal; produktive Secrets nie ins Git
```

**B – Platzhalter aus einer Umgebungsvariablen:** `token: ${CONFLUENCE_PAT}`. Kein Kontakt zu Azure.

**C – Platzhalter aus Azure Key Vault** mit Profil `azure-keyvault`:

```yaml
space-harvester:
  sources:
    - id: confluence
      type: confluence
      base-url: https://confluence.example.org
      auth:
        type: pat
        token: ${confluence-api-token}     # Secret dieses Namens im Key Vault
```

```bash
SPRING_PROFILES_ACTIVE=azure-keyvault \
AZURE_KEYVAULT_ENDPOINT=https://<vault-name>.vault.azure.net/ \
java -jar space-harvester.jar
```

Das Profil bindet den Vault über den offiziellen **Spring Cloud Azure Key Vault Secrets Starter** (7.4.0, gebaut
gegen Spring Boot 4.1) als zusätzliche `PropertySource` ein. Spring löst `${confluence-api-token}` beim Binden auf;
es gibt keinen eigenen REST-Client und keine eigene Platzhaltersyntax. Mehrere Profile lassen sich kombinieren,
z. B. `local,azure-keyvault`.

## Anmeldung am Key Vault

Spring Cloud Azure nutzt die `DefaultAzureCredential`-Kette, es stehen also keine Azure-Passwörter in der YAML:

- **Lokal:** `az login` (Azure CLI), danach starten.
- **In Azure:** Managed Identity des Dienstes; ggf. `spring.cloud.azure.credential.managed-identity-enabled=true`
  und `spring.cloud.azure.credential.client-id` für eine benutzerzugewiesene Identität.
- **Sonst:** Service Principal über `AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET`.

Die Identität braucht nur Leserechte auf Secrets (Rolle „Key Vault Secrets User“ bzw. Zugriffsrichtlinie *Get*,
*List*).

## Verhalten und Reihenfolge

- **Ohne Profil `azure-keyvault`** ist die Key-Vault-PropertySource abgeschaltet
  (`spring.cloud.azure.keyvault.secret.property-source-enabled: false` in `application.yml`). Kein Netzwerkaufruf,
  keine Credential-Abfrage, auch nicht bei gesetztem `AZURE_KEYVAULT_ENDPOINT` oder nicht erreichbarem Vault.
- **Mit Profil** liest der Dienst beim Start die Secrets des Vaults. Ist der Vault nicht erreichbar oder die
  Anmeldung abgelehnt, startet der Dienst nicht.
- **Ausdrücklich gesetzte Werte gewinnen:** Ein direkter Wert in der YAML bleibt, wie er ist. Der Vault füllt nur
  Platzhalter.
- **Kein ungelöster Platzhalter als Zugangsdatum:** Spring lässt `${...}` ohne Wert stehen. Der Harvester prüft
  deshalb `auth.username`, `auth.password`, `auth.token` und `client-certificate.password` und bricht den Start ab,
  z. B. `auth.token: Platzhalter ${confluence-api-token} hat keinen Wert`. Die Meldung nennt nur den Namen.
- **Reihenfolge der Spring-Quellen** (höhere zuerst): Kommandozeile, Java-Systemeigenschaften, Umgebungsvariablen,
  Azure Key Vault, `application-<profil>.yml`, `application.yml`. Key-Vault-Secret-Namen mit Bindestrich passen auf
  Platzhalter gleichen Namens; Namen sind ohne Groß-/Kleinschreibung.
- **Empfehlung für geteilte Vaults:** nur die benötigten Secrets laden, damit kein anderes Secret zufällig eine
  Einstellung gleichen Namens überdeckt:

  ```yaml
  spring.cloud.azure.keyvault.secret.property-sources[0]:
    name: azure-key-vault
    endpoint: ${AZURE_KEYVAULT_ENDPOINT}
    secret-keys: [confluence-api-token]
  ```

  Achtung: Eine eigene Liste unter `property-sources` ersetzt die aus dem Profil vollständig; `name` und `endpoint`
  daher mit angeben.

Secret-Werte erscheinen weder in Logs, Fehlermeldungen, Actuator noch in API-Antworten.

## Spring Cloud Config Server

Ein Config Server liefert nur YAML. Platzhalter darin löst der Harvester selbst auf, mit Profil `azure-keyvault`
also aus dem Vault; der Config Server muss das Secret nicht kennen. Der Config-Client selbst ist noch nicht
eingebaut (#3): Der Spring-Cloud-Release-Train für Spring Boot 4.1 (2026.0) ist bisher nur als Meilenstein
veröffentlicht, und eine ungeprüfte Kombination mit 2025.1 (Boot 4.0) wird nicht festgeschrieben.

## Live-Test

1. Vault anlegen, Secret `confluence-api-token` mit dem PAT des technischen Confluence-Benutzers.
2. `az login` mit einem Konto, das Secrets lesen darf.
3. Quelle wie unter Variante C konfigurieren, mit `SPRING_PROFILES_ACTIVE=azure-keyvault` und
   `AZURE_KEYVAULT_ENDPOINT` starten.
4. `GET /api/v1/sources/confluence/spaces` mit einem technischen Client (SECURITY.md) aufrufen: Spaces kommen,
   im Log steht kein Token.
5. Gegenprobe: Secret umbenennen, Neustart bricht mit „Platzhalter ${confluence-api-token} hat keinen Wert“ ab.

Automatisiert prüft `AzureKeyVaultSecretsTest` dasselbe gegen einen HTTPS-Ersatz des Vaults: Auflösung bis zum
Connector, kein Kontakt ohne Profil, direkter Wert bleibt, fehlendes Secret und nicht erreichbarer Vault.
