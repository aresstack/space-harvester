# REST-Benchmark Synchronisierung (#181)

Stand 18.10.2026, 14:30 UTC. Auftrag: [#181 issuecomment-6250887253](https://github.com/aresstack/space-harvester/issues/181#issuecomment-6250887253), Abgrenzung aus issuecomment-6250576142 und -6250444078. Kein Core-Code geändert, Werkzeug als Draft-PR auf Angelos Wunsch (kein Merge).

## Kurzfassung

1. **Phase 0 ist aus unserem Container nicht bestanden.** Die Netzwerkrichtlinie der Cloud-Umgebung lässt `confluence.atlassian.com` nicht zu: Jede Verbindung endet am ausgehenden Proxy mit `403` auf `CONNECT`, bevor Atlassian überhaupt antwortet (Protokoll im Projektordner `strang-t/container-zugriffspruefung.txt`). Über die Endpunkte selbst sagt das nichts aus. Nach Auftrag kein Umweg über andere Proxys, kein HTML-Scraping.
2. **Deshalb liegt das Messwerkzeug bereit**, das Angelo selbst gegen confluence.atlassian.com oder die eigene Instanz laufen lässt: [ConfluenceSyncBenchmark.java](ConfluenceSyncBenchmark.java), eine Datei, Java 21, ohne Build und ohne Abhängigkeiten. Es misst alle Verfahren A–F aus dem Auftrag und schreibt den Bericht mit den Tabellen unten selbst. Der eingebaute Selbsttest gegen eine simulierte Confluence ist grün.
3. **Die Kernfrage lässt sich schon aus der API beantworten, unabhängig von Messzahlen:** Confluence REST v1 liefert keinen Hash, keinen Änderungszähler und kein ETag für einen Teilbaum oder Space. `childTypes.page` sagt nur „hat Kinder: ja/nein“, die Version einer Seite ändert sich nicht, wenn darunter eine Seite entsteht. **Ein lokaler Child- oder Merkle-Hash spart deshalb keine einzige Anfrage** für einen nachweislich vollständigen Abgleich. Das Werkzeug prüft das an der echten Instanz zusätzlich (Feldsuche nach hash/etag/token in den Antworten, Header `ETag`/`Last-Modified`).
4. **Empfehlung (vorläufig, Zahlen folgen aus Angelos Lauf):** flache Metadateninventur (A) als einzige verlässliche Abgleichsbasis, sie liefert über `expand=ancestors` die Elternbeziehung gleich mit; dazwischen die CQL-Änderungssuche (C); Papierkorb (F) für bestätigte Löschungen, wo die Rechte reichen; Baumabfragen (B) nur für die Navigation und für gezielte Teilbäume, dort als CQL `ancestor = ID` (D) statt rekursiv. Merkle-Hash nur lokal als Fingerabdruck des Katalogs.

## Phase 0: Zugriffsprüfung

| Ort | Ergebnis |
|---|---|
| Cloud-Container dieses Projekts | Kein Zugriff: Der Proxy der Umgebung verweigert die Verbindung (`CONNECT … 403`), auch für `developer.atlassian.com`. Das ist die Netzwerkrichtlinie, keine Antwort von Atlassian. |
| Frühere Versuche (GPT, Angelos Rechner) | Laut Auftrag nicht erfolgreich, Ursache unbekannt. |
| Angelos Rechner mit dem Werkzeug | **offen**: `--probe-only` prüft genau die Punkte aus dem Auftrag (siehe Anleitung). |

Wer die Container-Sperre aufheben will: In den Projekteinstellungen unter „Cloud-Umgebung“ eine eigene Umgebung anlegen und `confluence.atlassian.com` unter „Allowed domains“ eintragen. Das Projekt läuft derzeit auf der eingebauten Umgebung ohne Einstellungen. Nötig ist das nicht, das Werkzeug läuft auch bei Angelo.

## Was das Werkzeug misst

Alle Anfragen nacheinander, Standard höchstens 1 Anfrage/s, Seitengröße 50, Deckel 400 Anfragen je Verfahren und Space, 3000 je Lauf. Bei 429/503, 401/403, Weiterleitung (SSO) oder einer Antwort ohne JSON stoppt der Lauf sofort und vermerkt das. Keine Seiteninhalte (`body.*` wird nie angefordert), keine Schreibzugriffe. Ein PAT geht nur als Bearer-Header hinaus und erscheint in keiner Datei.

| Kürzel | Verfahren | REST-Muster | Entspricht im Code |
|---|---|---|---|
| 0 | Zugriff | `/rest/api/space?limit=2`, je ein Aufruf der Muster unten mit `limit=2`, Einzelseite mit `expand=version,history.lastUpdated,ancestors,childTypes.page`, `/rest/applinks/1.0/manifest` (Version) | – |
| A | Flache Inventur | `/rest/api/content?type=page&spaceKey=K&expand=version,ancestors&limit=50`, `_links.next` | `InventoryCollector` über `PartitionListingSource` (EAC `spacePages`/`spacePageBatch`) |
| B | Baum | `/rest/api/space/K/content/page?depth=root&expand=version,childTypes.page`, dann `/rest/api/content/{id}/child/page?…` nur für Seiten mit `childTypes.page = true` | `KnowledgeSourceHarvestSource.children()` → EAC `rootPagesPath`/`childPagesPath` |
| C | Änderungssuche | `/rest/api/content/search?cql=space="K" and type=page and lastmodified >= "yyyy-MM-dd HH:mm"&expand=version,ancestors` | Port der EAC-Änderungssuche (ADR 0014, Schritt d) |
| D | Teilbaum | CQL `type=page and ancestor = ID` und `parent = ID` gegen die Kinderabfragen aus B für denselben Knoten (Teilbaum ≈ 100 Seiten) | – |
| F | Papierkorb | `/rest/api/content?type=page&status=trashed&spaceKey=K` (meist Space-Admin; Verweigerung wird vermerkt, kein Abbruch) | `RemovalCheck` (DELETED nach ADR 0014) |
| E | Lokaler Merkle-Hash | Hash je Knoten über `pageId|parentId|version|sortierte Kind-Hashes`, Space-Hash an der virtuellen Wurzel; simuliert Bearbeiten, Neu, Verschieben, Löschen nur lokal | – |

Spaces wählt das Werkzeug ohne `--spaces` selbst: alle globalen Spaces auflisten, davon 25 gleichmäßig verteilt per `/rest/api/search?…&limit=1` (`totalSize`) zählen, dann den kleinsten mit mindestens 10 Seiten, den größten und den logarithmisch mittleren nehmen. Gemessen wird je Space: Seiten, Wurzelseiten, Elternknoten, Blätter, Tiefe max/Median, Kinder je Elternknoten, und je Verfahren Anfragen, Bytes, Laufzeit, Median/p95 der Latenz, Anfragen je 100 Seiten, Vollständigkeit als Mengenvergleich der IDs (A gegen B, Elternbeziehung A gegen B, C gegen die Seiten aus A mit `version.when` im Zeitfenster, D gegen B). `--repeat 2` trennt Kalt- und Warmlauf. `--compare` vergleicht mit einem früheren Lauf und zählt neue, entfernte, verschobene und bearbeitete Seiten sowie, ob die Verschiebungen in der CQL-Änderungssuche auftauchen.

## Anforderungskosten aus der API (vor der Messung)

Diese Formeln folgen aus dem Paging der Endpunkte. Der Selbsttest bestätigt, dass das Werkzeug genau so viele Anfragen stellt (simulierte Spaces mit 40, 400 und 2000 Seiten). Die Simulation ist ein zufälliger Baum und **kein Messwert für Confluence**.

Mit N Seiten im Space, R Wurzelseiten, P Seiten mit Kindern (Elternknoten), k_p Kindern je Elternknoten, L Seitengröße, Ä geänderten Seiten im Zeitfenster und S Seiten im Teilbaum:

| Verfahren | Anfragen | Findet | Findet nicht |
|---|---|---|---|
| A Flach | ⌈N/L⌉ | alle sichtbaren Seiten mit Version und Elternkette; Verschiebungen über Vergleich der Eltern | Löschung ≠ Rechteentzug (beides „fehlt“) → `RemovalCheck` |
| B Baum mit `childTypes` | ⌈R/L⌉ + Σ ⌈k_p/L⌉ ≥ P + 1 | dasselbe wie A | dasselbe wie A |
| B Baum ohne `childTypes` | ⌈R/L⌉ + Σ über alle N Seiten ⌈k/L⌉ ≥ N + 1 | dasselbe wie A | dasselbe wie A |
| C Änderungssuche | ⌈Ä/L⌉, mindestens 1 | neue und bearbeitete Seiten | Löschungen, Rechteänderungen; Verschiebungen nur, falls Confluence dabei `lastmodified` setzt (wird mit `--compare` gemessen) |
| D CQL `ancestor` | ⌈S/L⌉ | alle Nachfahren in beliebiger Tiefe | wie C |
| D rekursiv `child/page` | Σ über Elternknoten im Teilbaum ⌈k_p/L⌉ | dasselbe | – |
| F Papierkorb | ⌈gelöscht/L⌉ | bestätigte Löschungen | endgültig geleerten Papierkorb, Rechteentzug |

Annahme, nicht gemessen: In Dokumentations-Spaces hat grob jede dritte bis fünfte Seite Kinder, die meisten Elternknoten weniger als 50. Dann braucht B etwa P ≈ N/4 Anfragen, A nur N/50. **Für einen vollständigen Abgleich ist der Baum damit etwa zehn- bis zwölfmal teurer in Anfragen**, bei 1 Anfrage/s also Minuten statt Sekunden. Die echten Werte für P, k_p und die Bytes (A trägt die Elternkette mit, B nicht) liefert Angelos Lauf.

## Antworten auf die fünf Fragen

1. **Ist die Baumsynchronisierung schneller als eine flache Inventur?** Nein, für den ganzen Space nicht. B braucht mindestens eine Anfrage je Elternknoten, A eine je 50 Seiten. Schneller ist der Baum nur, wenn man ohnehin nur einen kleinen Ast ansieht (Navigation). Messwert folgt.
2. **Wie viele REST-Anfragen braucht jedes Verfahren?** Siehe Formeln: A ⌈N/L⌉, B ⌈R/L⌉ + Σ⌈k_p/L⌉, C ⌈Ä/L⌉, D ⌈S/L⌉, F ⌈gelöscht/L⌉. Die gemessenen Zahlen je Space trägt die Vorlage unten ein.
3. **Können wir Änderungen gezielt in einzelnen Teilbäumen erkennen?** Ja, aber über CQL `ancestor = ID` (eine Anfrage je 50 Nachfahren, beliebige Tiefe, mit `lastmodified` kombinierbar), nicht durch rekursives Aufklappen. Die Kinderliste eines einzelnen Knotens beweist nichts über Enkel. Ob `ancestor` dieselbe Menge liefert wie die Rekursion (Berechtigungen, Indexverzug), prüft D.
4. **Spart ein Child-Hash tatsächlich Anfragen an Confluence?** Nein. Confluence liefert keinen Teilbaum-Hash und keinen Teilbaum-Änderungszähler. Um einen neuen Remote-Hash zu bilden, müsste man alle Elternknoten abfragen, also genau den vollen Baumdurchlauf, den der Hash sparen sollte. Merkle-artiges Überspringen unveränderter Äste ist ohne weitere Anfrage nicht möglich. Lokal nützt der Hash trotzdem: als Fingerabdruck des Katalogs (zwei Inventuren in einem Vergleich gleich oder nicht, passend zum offenen Space-Fingerabdruck aus #6/#16) und um nach einer Änderungssuche nur die Vorfahren der betroffenen Seiten neu zu rechnen.
5. **Welche Kombination ist am effizientesten?** Häufig C (kostet fast nichts), dazu F wo erlaubt, selten A als autoritativer Abgleich mit `RemovalCheck` (findet Übersehenes, Verschiebungen, Rechteverlust des technischen Kontos), B nur für die Anzeige, D für einen gezielt zu prüfenden Ast. Das entspricht ADR 0014; neu ist nur, dass der Katalog die Elternbeziehung aus A übernimmt, ohne einen Baumdurchlauf.

## Metadatenkatalog getrennt vom Inhaltscache

Die Idee trägt, und sie kostet keine zusätzlichen Anfragen: Die flache Inventur A liefert mit `expand=version,ancestors` bereits ID, Space, Elternkette, Version und Änderungszeit jeder sichtbaren Seite. Ein Metadatenkatalog (Schlüssel `sourceId + pageId`, Eltern-ID statt Kinderlisten, `lastCheckedAt`) ist damit ein Nebenprodukt der Inventur, die `InventoryCollector` heute schon fährt, und keine zweite Beschaffung. Den Inhaltscache füllt weiter nur die Suche nach ADR 0013 oder eine ausdrückliche Vollbefüllung. Der EAC könnte denselben Katalog später für seinen Seitenbaum nutzen, ohne Live-Abfragen beim Aufklappen. Ob die Elternkette auf großen, tiefen Spaces nennenswert Bytes kostet, zeigt die Spalte „Bytes“ bei A gegen B.

Zu beachten, wie in den Reviews festgelegt: Fehlt eine Seite für einen einzelnen Benutzer (403/404), ist das nie eine Löschung im gemeinsamen Katalog oder Cache. Nur `RemovalCheck` mit dem technischen Konto entscheidet DELETED oder INACCESSIBLE.

## Anleitung für Angelo

Voraussetzung: Java 21 (`java -version`). Im Ordner `tools/sync-benchmark/` dieses Repos (Zweig `claude/rest-benchmark-sync-69fy8s`) eine Eingabeaufforderung öffnen. Ablauf für eine lokale Claude-Instanz: [ANWEISUNG-LOKALE-CLAUDE.md](ANWEISUNG-LOKALE-CLAUDE.md).

```bat
:: 1. Nur Zugriff prüfen (9 Anfragen)
java ConfluenceSyncBenchmark.java --base-url https://confluence.atlassian.com --probe-only --out ergebnis-probe

:: 2. Wenn das Gate „ja“ zeigt: Messung mit automatischer Space-Wahl, Kalt- und Warmlauf
java ConfluenceSyncBenchmark.java --base-url https://confluence.atlassian.com --repeat 2 --out ergebnis-atlassian

:: Eigene Instanz mit PAT (Datei mit dem Token in der ersten Zeile), feste Spaces
java -Djavax.net.ssl.trustStoreType=Windows-ROOT ConfluenceSyncBenchmark.java ^
     --base-url https://wiki.firma.de/confluence --pat-file C:\geheim\pat.txt --spaces KLEIN,MITTEL,GROSS --out ergebnis-firma

:: Hinter einem Proxy (PAC wird nicht ausgewertet, das Ziel aus der PAC-Datei direkt angeben)
java ConfluenceSyncBenchmark.java --base-url … --proxy proxy.firma.de:8080

:: Verschiebungen messen (nur eigene Instanz): Lauf, eine Seite in Confluence verschieben, zweiter Lauf
java ConfluenceSyncBenchmark.java --base-url … --pat-file … --spaces TEST --out lauf1
::   … Seite verschieben …
java ConfluenceSyncBenchmark.java --base-url … --pat-file … --spaces TEST --compare lauf1\snapshot-TEST.json --out lauf2

:: Selbsttest ohne Netz
java ConfluenceSyncBenchmark.java --self-test
```

Ergebnisse im Ausgabeordner: `bericht.md` (alle Tabellen), `anfragen.csv` (jede Anfrage mit Status, Bytes, Millisekunden, ohne Token), `snapshot-<Space>.json` (IDs, Eltern, Versionen, keine Titel, keine Inhalte). Den Ordner hier hochladen oder `bericht.md` in #181 einfügen. Schnelltest ohne Werkzeug: `https://confluence.atlassian.com/rest/api/space?limit=2` im Browser öffnen; JSON heißt offen, eine Anmeldeseite oder HTML heißt Gate nicht bestanden.

Für confluence.atlassian.com erzwingt das Werkzeug mindestens 250 ms Pause; der Standard bleibt 1 s. Bei einem großen Space greift der Deckel; die Tabelle zeigt dann „nein (Deckel)“, und die Kosten sind aus der Formel hochzurechnen, nicht als vollständig zu lesen.

## Auswertungsvorlage

Wird aus `bericht.md` übernommen; „–“ = noch nicht gemessen.

Phase 0 (confluence.atlassian.com, anonym):

| Prüfung | Status | Content-Type | Hinweise (Login, Rate-Limit, ETag) |
|---|---|---|---|
| `/rest/api/space?limit=2` | – | – | – |
| flache Liste | – | – | – |
| Wurzelseiten `depth=root` | – | – | – |
| `child/page` | – | – | – |
| Einzelseite (Felder hash/etag/token?) | – | – | – |
| CQL Space / `lastmodified` | – | – | – |
| Confluence-Version | – | | |

Spaces:

| Größe | Space | Seiten | Wurzelseiten | Elternknoten | Tiefe max / Median | Kinder je Elternknoten max / Median |
|---|---|---|---|---|---|---|
| klein | – | – | – | – | – | – |
| mittel | – | – | – | – | – | – |
| groß | – | – | – | – | – | – |

Je Space und Lauf (kalt/warm):

| Space | Verfahren | Seiten | vollständig | Anfragen | Bytes | Laufzeit | Median / p95 ms | Anfragen je 100 Seiten |
|---|---|---|---|---|---|---|---|---|
| klein | A Flach | – | – | – | – | – | – | – |
| klein | B Baum | – | – | – | – | – | – | – |
| klein | C Delta (30 Tage) | – | – | – | – | – | – | – |
| klein | F Papierkorb | – | – | – | – | – | – | – |
| … | | | | | | | | |

Vollständigkeit: A = B (nur A / nur B / Eltern verschieden), C gegen erwartete Seiten (fehlt / zusätzlich), D `ancestor` und `parent` gegen B (fehlt / zusätzlich, Anfragen D gegen B-Teilbaum).

Entscheidungsfragen an die Zahlen:
- Ist B/A bei Anfragen auf allen drei Spaces ≥ 5? Dann ist der Baum als Abgleich vom Tisch.
- Liefert D `ancestor` dieselbe Menge wie die Rekursion? Dann taugt es für Teilbaum-Abgleiche.
- Fehlen in C Seiten, die A mit passendem `version.when` zeigt? Dann braucht die Änderungssuche eine Überlappung des Zeitfensters (Indexverzug).
- Zeigt `--compare` verschobene Seiten in C? Dann erkennt die Änderungssuche Verschiebungen, sonst nur die Inventur.
- Ist F mit dem technischen Konto lesbar? Dann kann `RemovalCheck` DELETED autoritativ setzen.

## Was offen bleibt

- Die Messzahlen selbst: braucht einen Lauf außerhalb unseres Containers (Angelo) oder eine Umgebung mit freigegebenem Host.
- Ob Confluence beim Verschieben `lastmodified` setzt: nur auf einer Instanz mit Schreibrechten messbar (`--compare`), nie auf confluence.atlassian.com.
- `/rest/api/content/{id}/descendant/page` ist absichtlich nicht im Werkzeug: Laut Data-Center-Dokumentation sind dort nur Kommentare unterstützt (Review issuecomment-6250576142).
