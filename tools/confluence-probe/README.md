# Confluence-Probe für den Spike #7

Mit dieser Probe lässt sich prüfen, wie vollständig und reproduzierbar die nativen Confluence-Antworten sind,
ohne dass Seiteninhalte das Unternehmensnetz verlassen. Die Abrufe laufen im Browser, der bereits angemeldet ist
und Zertifikat und Proxy kennt ([#33](https://github.com/aresstack/space-harvester/issues/33),
[#34](https://github.com/aresstack/space-harvester/issues/34)). Ausgewertet wird lokal; weitergegeben wird nur der
Bericht.

## 1. Beispielseiten wählen

Fünf bis zehn Seiten, die zusammen möglichst viel abdecken: Tabellen, Info-/Warnungs-Makros, Status, Inhaltsangabe,
Code, Jira, draw.io/Gliffy, eingebettete Bilder, Anhänge, die nur in der Anhangsliste stehen, Links auf andere Seiten
und Spaces, Erwähnungen von Personen, Aufgabenlisten, Seitenlayouts, Excerpt/Include, eine sehr große Seite.

## 2. Antworten speichern

Für jede Seite `<id>` (Content-ID aus „Seiteninformationen“ oder der URL) im Browser öffnen und mit
„Speichern unter“ als **Rohdaten** sichern (nicht die formatierte Ansicht kopieren). `<basis>` ist die Adresse mit
Kontextpfad, z. B. `https://confluence.example.org/confluence`.

| Datei | URL |
|---|---|
| `<id>.a.json` | `<basis>/rest/api/content/<id>?expand=body.storage,version,space,ancestors,metadata.labels,history,history.lastUpdated,metadata.properties` |
| `<id>.b.json` | dieselbe URL ein zweites Mal, einige Minuten später |
| `<id>.attachments.json` | `<basis>/rest/api/content/<id>/child/attachment?expand=version,container,metadata.mediaType&limit=200` |
| `<id>.comments.json` | `<basis>/rest/api/content/<id>/child/comment?expand=body.storage,version&depth=all&limit=200` (nur für Seiten mit Kommentaren) |

Optional, für den Vergleich zwischen Hostnamen oder Konten: `<id>.b.json` über einen anderen Hostnamen (Alias,
Load-Balancer) oder mit einem anderen Benutzer abrufen und das im Bericht vermerken.

## 3. Bericht erzeugen

```bash
python3 tools/confluence-probe/analyze.py <verzeichnis-mit-den-json-dateien> > bericht.md
```

Nur Python 3, keine weiteren Pakete. Der Bericht enthält Feldpfade, Größen, Anzahlen, Makro- und Parameternamen und
Referenzarten, aber keine Titel, Texte, Dateinamen, Benutzernamen oder URLs. Ihn vor dem Weitergeben trotzdem kurz
ansehen: Makronamen von Plugins können interne Produktnamen verraten.

`example/` enthält synthetische Antworten (keine echten Daten), an denen sich die Ausgabe nachvollziehen lässt:

```bash
python3 tools/confluence-probe/analyze.py tools/confluence-probe/example
```

Die gespeicherten Antworten selbst gehören nicht ins Repository.
