# enterprise-ai-keepass

Unveränderte Kopie der KeePassRPC-Anbindung aus
[aresstack/enterprise-ai-client](https://github.com/aresstack/enterprise-ai-client)
(`proasteion/platform/security-keepassrpc`). Die Desktop-App koppelt damit KeePass, liest Einträge und legt neue an,
genau wie der Enterprise AI Client. Der Core und der Server hängen nicht davon ab: Er erhält nur die aufgelösten
Zugangsdaten.

Der genaue Upstream-Commit steht in der ersten Zeile von [`UPSTREAM.sha256`](UPSTREAM.sha256).

| Upstream-Modul | Inhalt hier |
|---|---|
| `proasteion/platform/security-keepassrpc` | vollständig: `KeePassRpcSecretProvider`, `KeePassRpcConfig`, `KeePassPairingCallback`, `KeePassPairingKeyStore`, Transport und Krypto, `FakeKeePassRpcServer` als Test-Fixture |
| `adyton`, `astu` `SecretRef` | nicht hier, sondern aus [`enterprise-ai-sources`](../enterprise-ai-sources/UPSTREAM.md) |
| `LICENSE` | die Lizenz des Upstreams (MIT, AresStack), mitkopiert und mitgeprüft |

Änderungen gehören in den Upstream und kommen mit `./sync-from-upstream.sh sync <commit>` zurück;
`./gradlew :enterprise-ai-keepass:check` prüft die Prüfsummen offline. Der Integrationstest gegen ein echtes KeePass
(`KeePassRpcRealServerIT`) läuft nur mit `-Dkeepassrpc.it=true`.
