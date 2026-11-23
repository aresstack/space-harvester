# enterprise-ai-browser

Unveränderte Kopie des chatgebundenen Browsers aus
[aresstack/enterprise-ai-client](https://github.com/aresstack/enterprise-ai-client). Die Desktop-App nutzt ihn für die
Websuche im Chat genau wie der Enterprise AI Client: ein sichtbares Firefox mit eigenem Profil je Chat, gesteuert über
WebDriver BiDi (ohne geckodriver). Weil es ein echter Browser ist, findet die Websuche auch Intranetseiten. Der Core
und der Server hängen nicht davon ab.

Der genaue Upstream-Commit steht in der ersten Zeile von [`UPSTREAM.sha256`](UPSTREAM.sha256).

| Upstream-Modul | Inhalt hier |
|---|---|
| `browser-api` | vollständig: `ChatBrowserPort`, `ChatBrowser`, Seiten, Links, Bedienelemente, Engine-SPI |
| `browser-runtime` | vollständig: Profil je Chat, verzögerter Start, Wiederverbinden nach Verbindungsverlust |
| `browser-firefox` | vollständig: Firefox-Adapter und das vendorte WD4J aus MainframeMate (`de.bund.zrb`, MIT), siehe [`VENDORED.md`](VENDORED.md) |
| `astu` | nur `chat/ChatConversationId` |
| `LICENSE` | die Lizenz des Upstreams (MIT, AresStack), mitkopiert und mitgeprüft |

Neue Abhängigkeit gegenüber den anderen Kopien: `org.reflections:reflections` (wie im Upstream-Build von
`browser-firefox`, für die Wrapper-Suche von WD4J).

Änderungen gehören in den Upstream und kommen mit `./sync-from-upstream.sh sync <commit>` zurück;
`./gradlew :enterprise-ai-browser:check` prüft die Prüfsummen offline. Der Echttest mit zwei Firefox-Instanzen
(`TwoFirefoxChatsIT`) läuft nur mit `-Denterpriseai.firefox.it=true`.
