# ADR 0014: Abgleich, Berechtigung und Suche getrennt

- Status: **Angenommen** (Strang Q, 18.10.2026; Entscheidung von Angelo, 18.10. 13:33, Review in #181
  issuecomment-6250444078). **Fortgeschrieben** am 18.10. (Angelo 14:43–15:10, #181 issuecomment-6251484855),
  ergänzt am 18.10. (Angelo 15:33–15:50): Die Suche entdeckt, aktualisiert wird je Seite nach Strategie,
  Änderungsquellen (Webhooks, Nachholen, Zeitplan) sind optional; siehe „Fortschreibung“. Ergänzt
  [ADR 0013](0013-suche-ueber-den-cache.md), ohne die Suchpipeline zu ändern.
- Bezüge: `SpaceSync`, `InventoryCollector`, `SyncPlanner`, `SyncBaseline`, `RemovalCheck`, `PageQueries` mit
  `CacheFreshness` (#256).

## Kontext

Drei Aufgaben waren vermischt:

1. **Neue Seiten entdecken.** Bisher geschah das nur als Nebeneffekt der Suche (die native Suche findet, der Worker
   lädt) oder im vollständigen Abgleich.
2. **Bekannte Seiten aktuell halten, Gelöschtes entfernen.** Bisher geschah das beim Abruf (Zeitfenster und
   Versionsprüfung) oder im vollständigen Abgleich.
3. **Durchsuchen.** Die Suche liest nur den Cache (ADR 0013).

Die Mechanik für den Abgleich gibt es schon. `InventoryCollector` listet die Metadaten aller Seiten eines Space ohne
Inhalte. `SyncPlanner` vergleicht die Liste mit der bestätigten `SyncBaseline` und lädt nur neue oder geänderte
Inhalte. `RemovalCheck` fragt jede fehlende Seite einzeln nach (`GONE`, `MOVED_OUT`, `STILL_PRESENT`,
`UNVERIFIED`). Es fehlen nur drei Dinge: ein regelmäßiger Auftrag, ein Abgleich, der keine Erstbefüllung verlangt,
und eine saubere Unterscheidung innerhalb von `GONE`. Heute fasst `GONE` eine echte Löschung und fehlende
Sichtbarkeit für das technische Konto zusammen, weil Confluence beides mit 404 beantwortet.

## Entscheidung

1. **Eine konfigurierbare, regelmäßige Metadateninventur ergänzt die schnelle Änderungssuche.** Je Space läuft
   zeitgesteuert ein Abgleichauftrag (`RECONCILE`) über die vorhandene Mechanik. Er listet die Metadaten, vergleicht,
   lädt nur geänderte Inhalte und prüft Fehlendes einzeln mit `RemovalCheck`. Es gibt keinen regelmäßigen Vollabruf
   der Inhalte und keinen zweiten Löschpfad. Der Auftrag ist je Space einstellbar und standardmäßig aus; ist er aus,
   behauptet der Core keine lückenlose Aktualität.
2. **Keine Erstbefüllung als Voraussetzung.** Ohne bestätigte Generation gilt beim Abgleich als bekannt, was im Cache
   liegt, etwa durch Suche oder Abruf. Unbekannte Seiten aus der Inventur lädt der Abgleich nur, wenn der Space
   ausdrücklich vollständig gehalten werden soll.
3. **Schnelle Änderungssuche.** Neue und geänderte Seiten seit einer gespeicherten Marke findet ein leichter Lauf
   (Confluence: CQL `lastmodified`, mit Überlappung der Marke). Er sieht weder Löschungen noch Rechteänderungen; das
   bleibt Sache der Inventur. Er setzt einen Port „geändert seit“ im Enterprise AI Client voraus und kommt nach der
   Inventur.
4. **`GONE` wird getrennt.** `DELETED` heißt, die Quelle hat die Löschung autoritativ bestätigt (Confluence: die Seite
   liegt im Papierkorb; lokale Dateien: die Datei fehlt in einem lesbaren Verzeichnis). `INACCESSIBLE` heißt, die
   Quelle antwortet dem technischen Konto mit „nicht gefunden“, ohne dass eine Löschung bestätigt ist. Ein 403 oder
   ein nicht prüfbarer Zustand bleibt `UNVERIFIED` und ändert nichts.
5. **Folgen für den gemeinsamen Cache.**
   - `DELETED` entfernt die Seite aus Cache, Volltext- und Vektorindex.
   - `INACCESSIBLE` sperrt die Seite sofort für jede Ausgabe und nimmt sie aus Volltext- und Vektorindex. Die
     Rohdaten werden erst entfernt, wenn der Zustand über eine einstellbare Frist bestehen bleibt.
   - `MOVED_OUT` und `STILL_PRESENT` wirken wie bisher.
   - Nur diese autoritativen Feststellungen mit dem technischen Konto bereinigen den gemeinsamen Cache.
6. **Benutzerberechtigung bleibt unabhängig.** Bei jeder Ausgabe prüft `AccessGuard`, mit delegierter Anmeldung in der
   Identität des Benutzers, ob er die Seite sehen darf. Das gilt für Titel, Textauszüge, Ergebnismengen, Vektorsuche,
   Leseansicht und Export. Diese Prüfung ist nicht abschaltbar. Dass ein einzelner Benutzer eine Seite nicht sehen
   darf, ändert den gemeinsamen Cache nie.
7. **Zustand dauerhaft.** Zeitpunkt und Ergebnis jedes Laufs, die Marke der Änderungssuche, die letzte Prüfung je
   Seite (`lastCheckedAt`) und der Beginn eines `INACCESSIBLE`-Zustands werden dauerhaft gespeichert.

## Fortschreibung (18.10.2026)

Gemessen auf confluence.atlassian.com (Strang T): flache Inventur 2 Anfragen und 2,3 s, Baum-Traversierung 69 Anfragen
und 68,8 s, Änderungssuche per CQL `lastmodified` 1 Anfrage und 1,1 s, Papierkorb 1 Anfrage und 0,9 s. Confluence Data
Center meldet Änderungen außerdem selbst über Webhooks (ab 7.7), aber nur, wenn ein Administrator sie registriert und
der Empfänger für Confluence erreichbar ist; das ist nicht überall gegeben. Daraus folgt, und das ersetzt Punkt 1, 3
und 6, soweit sie widersprechen, drei voneinander unabhängige Ebenen:

1. **Entdecken: die Suche.** Welche Seiten in den Cache gehören, entscheidet die native Schlagwortsuche
   ([ADR 0013](0013-suche-ueber-den-cache.md)). Das funktioniert mit leerem Katalog, ohne Erstinventur, ohne
   Zeitplan und ohne Webhook; gesucht wird immer im Cache.
2. **Aktualisieren: je Seite eine wählbare Strategie.** Für jeden Kandidaten gilt: nicht im Cache → Inhalt laden;
   im Cache → nach der eingestellten Strategie „Immer Version prüfen“ (Standard, `CacheFreshness(ZERO, true)`: nur
   Kopfdaten abfragen, Inhalt nur bei neuer Version), „zeitbasiert ohne Versionsprüfung“ oder „zeitbasiert mit
   Versionsprüfung“. Neue Seiten werden immer übernommen.
   Kein Zeitplan lädt dafür Inhalte vorab; ein Abgleich führt unbekannte Seiten nur im Katalog
   (`space-harvester.sync.load-unknown`, Standard aus), außer wer ausdrücklich den ganzen Space halten will.
3. **Änderungsquellen: optional, Standard aus.** Sie geben nur Beschaffungs- und Invalidierungsaufträge in dieselbe
   Pipeline und sind keine Voraussetzung für Ebene 1 und 2:
   - **Webhooks**, wo ein Administrator sie einrichten kann (Geheimnis je Quelle, asynchron, idempotent). Korrigiert
     am 18.10. (Strang M, Freigabe des Koordinators 22:26): **nur Katalog-Markierung, kein Inhaltsabruf.** Jede
     gemeldete Seite wird einzeln nachgefragt (eine Kopfdatenanfrage); eine bekannte Seite mit neuer Version gilt als
     veraltet (`OUTDATED`), eine unbekannte kommt nur in den Katalog (`NOT_LOADED`), eine entfernte wird nach der
     Einzelabfrage als `DELETED` entfernt, eine Rechteänderung markiert die Seite als veraltet oder sperrt sie, wenn das
     technische Konto sie nicht mehr sieht. Begründung: Inhalte lädt nach Ebene 1 und 2 die Suche mit ihrer
     Versionsprüfung, sobald eine Seite gebraucht wird; ein Webhook, der sofort lädt, würde bei vielen Änderungen
     Inhalte beschaffen, die niemand sucht, und die Last der Quelle vom Verhalten fremder Redakteure abhängig machen.
     Ereignisse wirken nur in Spaces mit bestätigter Generation, sonst gibt es keinen Katalog zu markieren. Umsetzung:
     `ConfluenceWebhooks` im Adapter, `ChangeEvents`/`EventSync` im Core (`Harvester.changeEvents()`), Server
     `POST /webhooks/v1/sources/{id}/events` mit HMAC-SHA256 (`X-Hub-Signature`, Geheimnis
     `space-harvester.webhooks.secrets.<Quelle>`), wirksam nur mit `sync.events`.
   - **Nachholen** per Änderungssuche (CQL `lastmodified` seit dem letzten Stand, mit Überlappung) plus
     Papierkorbabfrage je Space: manuell mit optionalem Zeitpunkt (`POST …/syncs?listing=changes&since=…`), beim Start
     (`space-harvester.sync.catch-up-on-start`), nach Zeitplan oder vor jeder Suche, alles Standard aus. Geänderte
     bekannte Seiten werden neu geladen, unbekannte nur in den Katalog übernommen.
   Ohne eine aktivierte Änderungsquelle behauptet der Dienst nicht, den ganzen Bestand aktuell zu halten; für die
   suchgesteuerte Nutzung ist das nicht nötig.
3. **Die Inventur bleibt Sicherheitsnetz** für verpasste Löschungen, Verschiebungen aus dem Space und endgültig aus
   dem Papierkorb gelöschte Seiten; nur sie stellt Vollständigkeit fest. Sie läuft auf Anforderung oder nach
   optionalem Zeitplan.
4. **Rechteprüfung nur am Endergebnis** (ersetzt Punkt 6, wie im Nachtrag zu ADR 0012): Nur die endgültige, aus dem
   Cache erzeugte Ergebnisliste wird seitenweise gegen die Rechte des Benutzers geprüft, und nur, wenn delegierte
   Anmeldung aktiv ist und der Dienst bzw. die Bibliothek keine eigenen Zugangsdaten hat. Keine zusätzliche Prüfung
   für Auszüge der Quelle, Kandidaten, Reranker oder Embeddings. Dass ein Benutzer eine Seite nicht sehen darf, ändert
   den gemeinsamen Cache weiterhin nie.
5. **Zustand.** Jede Generation trägt `changesSince`, den Beginn der Inventur oder Änderungssuche, die sie bestätigt
   hat; die nächste Änderungssuche fragt ab dort. Ereignisse schieben diese Marke nicht vor, weil sie keine
   Vollständigkeit belegen.

## Folgen

- Kleine PRs in dieser Reihenfolge: (a) `GONE` aufteilen und `INACCESSIBLE` sperren und deindizieren, (b) Abgleich
  ohne Erstbefüllung und zeitgesteuerter Auftrag je Space im Core und im Server, (c) dauerhafter Laufzustand,
  (d) Änderungssuche nach dem EAC-Port.
- Weitere Entdeckungswege als Optionen, noch nicht umgesetzt (#181, issuecomment-6250576142): gezielter
  Teilbaum-Abgleich über die vorhandene Navigation (`children(spaceKey, pageId, cursor)`, Confluence
  `/child/page`), ein ganzer Teilbaum per CQL `ancestor = <ID>` und die Änderungssuche per CQL `lastmodified`. Sie
  ergänzen die Inventur, ersetzen sie aber nicht: Löschungen, Verschiebungen und Vollständigkeit erkennt nur die
  paginierte Metadateninventur. Ob sich ein getrennter Metadatenkatalog lohnt, klärt der REST-Benchmark.
- REST: Zustand des Abgleichs je Space (letzter Lauf, Ergebnis, nächste Ausführung); der Auftrag erscheint in den
  bestehenden Aufträgen.
- App: je Space ein Schalter „Regelmäßig abgleichen“ mit Intervall; Einstellung „Gesperrte Seiten entfernen nach“.
- docs/PARITY.md: Abgleich ohne Erstbefüllung und getrennte Löschfeststellung sind Erweiterungen gegenüber dem
  Enterprise AI Client.
