# ADR 0014: Abgleich, Berechtigung und Suche getrennt

- Status: **Angenommen** (Strang Q, 18.10.2026; Entscheidung von Angelo, 18.10. 13:33, Review in #181
  issuecomment-6250444078). Ergänzt [ADR 0013](0013-suche-ueber-den-cache.md), ohne die Suchpipeline zu ändern.
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

## Folgen

- Kleine PRs in dieser Reihenfolge: (a) `GONE` aufteilen und `INACCESSIBLE` sperren und deindizieren, (b) Abgleich
  ohne Erstbefüllung und zeitgesteuerter Auftrag je Space im Core und im Server, (c) dauerhafter Laufzustand,
  (d) Änderungssuche nach dem EAC-Port.
- REST: Zustand des Abgleichs je Space (letzter Lauf, Ergebnis, nächste Ausführung); der Auftrag erscheint in den
  bestehenden Aufträgen.
- App: je Space ein Schalter „Regelmäßig abgleichen“ mit Intervall; Einstellung „Gesperrte Seiten entfernen nach“.
- docs/PARITY.md: Abgleich ohne Erstbefüllung und getrennte Löschfeststellung sind Erweiterungen gegenüber dem
  Enterprise AI Client.
