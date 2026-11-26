# ADR 0043: Projekte als Wissensarbeitsbereich

- Status: **Angenommen** (27.11.2026) nach Angelos Modell. **Umgesetzt wird erst nach der Abnahme der Bibliotheken
  Research4j und Writing4j.**
- Quelle: Angelos Nachricht vom 27.11.2026, 00:33 MEZ. Maßgeblich ist die letzte Fassung von GPT darin: „Das
  Projekt ist der übergeordnete Arbeitsbereich, und die Favoritensammlung ist ein Bestandteil dieses Projekts.“
- Bezüge:
  - [ADR 0027](0027-beobachter-statt-abfragen.md): kein Polling
  - [ADR 0028](0028-ki-freigabe-je-seite.md): KI-Freigabe
  - [ADR 0029](0029-auswahl-und-ausgabe-aus-dem-bestand.md): Auswahl
  - [ADR 0033](0033-wissen-des-chats-per-verweis-und-werkzeug.md): Chat-Wissen
  - [ADR 0034](0034-favoriten-ordner-als-sammlungen.md): Favoriten-Ordner; drei Punkte werden hier geändert, siehe
    Abschnitt 4
  - [ADR 0038](0038-indexbasierte-progressive-und-agentengestuetzte-suche.md): Suche
- Ändert ADR 0034 in Abschnitt 1 (Einmaligkeit, Wurzel „nur Seite“). Alles andere aus ADR 0034 gilt weiter.

## Kontext

Bisher gibt es eine einzige Favoritenliste mit Ordnern (ADR 0034). Angelo will mehrere Listen. Jede Liste gehört zu
einem Projekt. Ein Projekt enthält neben den Favoriten auch die Fragestellung und später die Arbeitsergebnisse,
etwa ein Buch, das aus den Favoriten geschrieben wird.

Der Benutzer entscheidet selbst, wie er einsteigt:
- ein leeres Projekt anlegen und von Hand Favoriten sammeln,
- eine Fragestellung formulieren und automatisch recherchieren lassen,
- im Chat Fragen zu den Favoriten stellen,
- aus den Favoriten ein Dokument schreiben lassen,
- ein begonnenes Dokument mit weiteren Quellen ergänzen.

Ein Projekt muss also nicht von Anfang an ein Buchprojekt sein. Schreiben ist eine von mehreren möglichen
Tätigkeiten im selben Arbeitsbereich.

## Entscheidung

### 1. Das Projekt ist ein organisatorischer Container, kein Gott-Objekt
- Ein Projekt hat eine ID, einen Namen und Verweise auf seine Bestandteile. Es enthält keine Fachlogik.
- Jeder Bestandteil ist ein eigener Typ mit eigener Ablage und eigenem Anwendungsfall. Es gibt keinen
  `ProjectService`, der alles kann. Wer einen Bestandteil braucht, bekommt nur diesen Bestandteil, nie das ganze
  Projekt.
- **Jede Favoritenliste gehört zu genau einem Projekt.** Es gibt keine Favoriten außerhalb eines Projekts.
- Projekte sind beliebig viele. Ein Projekt kann leer sein.

### 2. Bestandteile eines Projekts

| Bestandteil | Inhalt | Verantwortung |
| --- | --- | --- |
| Auftrag | Fragestellung, Mission, Weidezaun (fachliche Abgrenzung), Zielgruppe; für Schreibaufträge auch Dokumenttyp und Umfang | Was soll untersucht oder geschrieben werden? |
| Favoriten | Ordnerbaum nach ADR 0034, von Hand oder automatisch gefüllt | Ausgewählter Wissensbestand (Verweise, keine Kopien) |
| Recherchekonzept (optional) | Mindmap zur Suchsteuerung | Wo sollen weitere Informationen gesucht werden? **Nur für die Suchsteuerung.** |
| Recherche | Suchaufträge, Fortschritt, vorgeschlagene Quellen | Weitere Quellen beschaffen (Research4j) |
| Wissensanalyse | Cluster, Fragen, Antworten, Belege | Ergebnis von Writing4j |
| Gliederung | Struktur des geplanten Dokuments | Ergebnis von Writing4j |
| Manuskript | Kapitel und Revisionen, Materialstand je Schreibauftrag (Abschnitt 4.3) | Ergebnis von Writing4j |
| Qualität | Belegprüfung, Wissenslücken, Vollständigkeit | Ergebnis von Writing4j |

Bestandteile ohne Inhalt sind leer. Ein Projekt, in dem nur Favoriten gesammelt werden, ist ein vollständiges
Projekt.

### 3. Ablage und Standardprojekt „Allgemein“
- Projekte gehören wie die Favoriten heute zum Bedienprofil der App (ADR 0034, Abschnitt 2), nicht zum Core und
  nicht in die Konfigurationsdatei. Jedes Projekt hat einen eigenen Ordner im Datenordner der App. Darin liegt je
  Bestandteil eine Datei mit Versionsfeld.
- Für alle Dateien gelten dieselben Regeln wie für `favorites.json`:
  - Es wird atomar geschrieben.
  - Eine unlesbare Datei kommt in Quarantäne und wird nie überschrieben.
  - Bei einer Umwandlung wird vorher einmalig gesichert.
- **Die bisherige Favoritenliste wird zum Standardprojekt „Allgemein“.** Die heutige `favorites.json` wird beim
  ersten Start einmalig gesichert und unverändert als Favoriten von „Allgemein“ übernommen, samt Ordnern. Nichts geht
  verloren. „Allgemein“ lässt sich umbenennen, aber nicht löschen, solange es das einzige Projekt ist.
- Läuft die App später mehrbenutzerfähig auf dem Server, gehören Projekte ihrem Besitzer. Die Ablage im Core ist
  dann wie bei Sammlungs-Zusammenfassungen nach ADR 0034 an den Besitzer gebunden.

### 4. Änderungen gegenüber ADR 0034

#### 4.1 Dieselbe Quelle in mehreren Projekten
- Dieselbe Quelle (Seite, Space oder Wissensquelle) darf in beliebig vielen Projekten als Favorit vorkommen. Aus
  demselben Bestand lassen sich so verschiedene Projekte mit verschiedenen Fragestellungen bilden, ohne erneut zu
  sammeln.
- **Innerhalb eines Projekts** bleibt ein Favorit einmalig. Dafür gilt die Identität aus Quelle, Space, Seite und
  Umfang (Abschnitt 4.2), wie bisher im ganzen Baum.
- Der Stern im Seitenbaum und in der Leseansicht wirkt auf das **aktive Projekt**. Aktiv ist das Projekt, das in der
  Favoritenansicht gewählt ist; ohne Wahl ist es „Allgemein“.
  - Gefüllt ist der Stern, wenn die Seite im aktiven Projekt liegt.
  - Liegt sie nur in anderen Projekten, nennt der Tooltip diese Projekte.
- Ein Favorit hat keine globale Einmaligkeit mehr. Alle Stellen, die bisher „ist Favorit?“ über alle Favoriten
  prüfen, fragen künftig je Projekt. Das betrifft den Stern, den Vorfahren-Merker und `Favorites.contains`.

#### 4.2 „Nur diese Seite, ohne Unterseiten“
- Ein Seiten-Favorit hat einen **Umfang**: `WITH_SUBPAGES` oder `PAGE_ONLY`. Bestehende Favoriten und der normale
  Stern erhalten `WITH_SUBPAGES`; damit bleibt die Bedeutung aus ADR 0034, Abschnitt 1, unverändert. `PAGE_ONLY`
  wählt der Benutzer ausdrücklich, etwa im Kontextmenü „Nur diese Seite aufnehmen“, damit eine Übersichtsseite
  nicht unbemerkt alle Unterseiten mitbringt.
- Der Satz aus ADR 0034, Abschnitt 1, „der Core braucht keine Wurzel ‚nur Seite‘“, entfällt.
  `holdings.Selection.Root` bekommt eine Wurzel „nur Seite“ (`Root.page`). Diese Wurzel behandeln
  `Holdings.resolve`, die gespeicherten Seiten, die Ausgaben und die kanonische Auswahl. Die kanonische Auswahl
  ist der Schlüssel der Sammlungs-Zusammenfassung, und das Unterseiten-Flag ist dort schon vorgesehen.
- `ChatPanel.onlyPage` wird auf diese Wurzel umgestellt, nicht daneben gestellt (DRY).
- Die Checkbox am Ordner (ADR 0034, Abschnitt 6) steuert weiter nur die Unterordner. Ein `PAGE_ONLY`-Favorit bleibt
  in beiden Stellungen die einzelne Seite.

#### 4.3 Materialstand beim Start eines Schreibauftrags
- Beim Start eines Schreibauftrags wird der **Materialstand** festgehalten. Er ist eine Liste aus
  `(PageKey, Revision, Inhalts-Fingerabdruck)` der aufgelösten Favoriten, nach Rechten des Aufrufers und KI-Freigabe.
  Er gehört zum Schreibauftrag im Bestandteil „Manuskript“.
- Der Schreibauftrag arbeitet nur mit genau diesen Revisionen. Ändert sich danach eine Quelle oder die
  Favoritenliste, verändert das das laufende oder fertige Manuskript nie unbemerkt:
  - Das Projekt zeigt „Material geändert seit Start: n Seiten“.
  - Der Benutzer startet bewusst einen neuen Schreibauftrag mit neuem Materialstand.
- Ist eine festgehaltene Revision nicht mehr lesbar, wird sie nie durch eine neuere ersetzt. Der Auftrag meldet
  „Revision nicht mehr verfügbar“ mit Grund. Gründe sind etwa: die Quelle hat sich geändert, der Zugriff ist
  entzogen oder die KI-Freigabe ist entzogen.
- Eine entzogene KI-Freigabe gilt sofort. Die betroffene Seite geht nicht mehr ins Modell, auch wenn sie im
  Materialstand steht (ADR 0028).

### 5. Projekt als Auswahl und im Chat
- Ein gewähltes Projekt ist eine Sammlung nach ADR 0034, Abschnitt 3: die Wurzel seiner Favoriten mit allen
  Unterordnern. Ein gewählter Ordner bleibt eine Sammlung innerhalb seines Projekts.
  `ExportPanel.Selection.Collection` bekommt dafür die Projekt-ID neben der Ordner-ID. Es entsteht kein neuer Typ.
- Chat, Export, Stand, Analysen, Zusammenfassung und Podcast funktionieren damit für ein Projekt ohne eigenen
  Zweig. Die Schlüssel der Zusammenfassungen bleiben über die kanonische Auswahl gebildet, also unabhängig von
  Projekt- und Ordner-IDs.
- Im Chat gilt ADR 0033 unverändert: Bereich ist die Sammlung, interne Suche und Websuche bleiben aus, bis der
  Benutzer sie einschaltet.

### 6. Abgrenzung zu Research4j und Writing4j
- **Research4j füllt die Favoriten (Phase 3).**
  - Es bekommt den Auftrag (Fragestellung, Mission, Weidezaun), das optionale Recherchekonzept und einen Port für
    die Suche. Dieser Port nutzt die eine Suchpipeline von Space Harvester nach ADR 0038.
  - Es liefert vorgeschlagene Quellenverweise. Die App prüft Rechte und KI-Freigabe und legt sie als Favoriten im
    Projekt ab. Ihre Herkunft „Recherche“ ist am Favoriten sichtbar.
- **Writing4j macht aus den Favoriten das Dokument (Phase 4).**
  - Es bekommt den Auftrag und das Material zum Materialstand: Passagen mit Verweis und Revision, nur KI-freigegeben.
  - Es liefert Wissensanalyse, Gliederung, Manuskript und Qualität zurück.
  - Erkennt es einen Bedarf an Nachrecherche, meldet es ihn als offene Frage. Daraus wird im Projekt ein
    Nachrecherche-Vorschlag (für Research4j oder von Hand), nie ein stilles Weiterschreiben.
- **Beide kennen das Space-Harvester-Projekt nicht.**
  - Ihre Ein- und Ausgaben sind eigene Typen der Bibliotheken.
  - Die Übersetzung liegt in je einem Adapter in Space Harvester.
  - Keine Bibliothek hängt von Space-Harvester-Typen ab.
- **Das Recherchekonzept dient nur der Suchsteuerung.** Es geht nie an Writing4j und bestimmt nie, was geschrieben
  wird.
- Was in Writing4j selbst passiert, regelt die Bibliothek. Für die Abnahme ist festgehalten:
  - Fragen werden aus zwei Richtungen abgeleitet: aus dem Auftrag allein und aus den Clustern des Materials.
    Eine Kernfrage ohne Beleg ist sichtbar.
  - Passagen werden vor dem Clustern gegen den Weidezaun gefiltert.
- Recherche und Schreiben sind keine Core-Funktionen (Festlegung: im Core keine Recherche und keine Agenten). Der
  Core liefert nur, was er schon kann:
  - Auflösung der Auswahl,
  - Passagen und Vektoren,
  - Revisionen und Fingerabdruck,
  - KI-Freigabe.

### 7. Aufträge
- Recherche und Schreiben laufen als Aufträge. Ein Klick ist ein normaler Auftrag (`submitInteractive`).
  Automatisches läuft im Hintergrund (BACKGROUND).
- Es gelten die Regeln für alle KI-Aufrufe:
  - immer gestreamt,
  - die Job-Parallel-Einstellungen auch innerhalb jedes Schritts,
  - selbstheilende Wiederholung,
  - jeder Fehler mit Grund in der Diagnose.
- Ergebnisse kommen per Beobachter (ADR 0027).
- Die KI-Freigabe wird unmittelbar vor jedem Senden geprüft.

### 8. Oberfläche
- Die Favoritenansicht zeigt oben die Projekte, „Allgemein“ zuerst. Ein Projekt klappt zu seinem Favoritenbaum
  nach ADR 0034 auf.
- Kontextmenü eines Projekts:
  - „Neues Projekt“
  - „Umbenennen“
  - „Löschen“: nur nach „wirklich …?“ Ja/Nein, mit Hinweis auf Manuskript und Analysen
  - „Chat starten“
  - „Schreibauftrag erstellen“
- Die Projektseite zeigt die Bestandteile aus Abschnitt 2 als Reiter. Wie sie aussehen, legt der UX-Manager fest.
  Es gelten die bestehenden UI-Vorgaben:
  - nur Entscheidungsfelder sichtbar,
  - Ableitbares unter „Erweitert“,
  - Aktionen oben rechts in der Tab-Zeile.
- Zwischenergebnisse (Fragen, Belege, offene Fragen) sind vor dem Manuskript sichtbar. So lässt sich prüfen, was
  die KI vorhat, bevor sie ein ganzes Dokument schreibt.

## Umsetzung

Erst nach der Abnahme von Research4j und Writing4j. Dann starten alle Pakete der Gruppe 1 sofort und gleichzeitig
gegen develop. Jedes Paket der Gruppe 2 baut nur auf Gruppe 1 auf und auf der abgenommenen Bibliothek, nie auf ein
anderes Paket der Gruppe 2.

**Gruppe 1**
- C1: `Selection.Root.page` im Core samt `Holdings.resolve`, Ausgaben und kanonischer Auswahl. `ChatPanel.onlyPage`
  wird darauf umgestellt.
- C2: Typ `Materialstand` (PageKey, Revision, Fingerabdruck), nur Vertrag.
- P1: Projektablage mit Standardprojekt „Allgemein“, Umwandlung von `favorites.json`, Sicherung, Quarantäne.
- P2: Favorit mit Umfang (`PAGE_ONLY`) und Einmaligkeit je Projekt, Stern auf das aktive Projekt.

**Gruppe 2**
- P3: `Selection.Collection` mit Projekt-ID; Projekt als Auswahl in Chat, Export, Stand, Analysen (C1, P1).
- P4: Adapter Research4j: Auftrag und Konzept hinein, Quellenverweise als Favoriten heraus (P1, P2).
- P5: Adapter Writing4j: Materialstand und Material hinein, Ergebnisse in die Bestandteile heraus (C1, C2, P1).
- P6: Projektansicht mit Reitern (UX-Manager; P1).

## Offene Punkte
- Freigegebene oder gemeinsame Projekte mehrerer Benutzer sind nicht Teil dieser Runde.
- Ob ein Nachrecherche-Vorschlag Research4j selbsttätig startet oder nur vorgeschlagen wird: Vorgabe ist „nur
  vorgeschlagen“, bis Angelo anders entscheidet.

## Folgen
- Space Harvester wird zusätzlich zur Wissensverwaltung eine Arbeitsumgebung für wissensbasierte Projekte. Der
  Core bleibt dabei die Dokumentbibliothek.
- ADR 0034 gilt weiter. Geändert werden nur die globale Einmaligkeit eines Favoriten und der Verzicht auf die
  Wurzel „nur Seite“.
- Research4j und Writing4j bleiben ohne Space Harvester verwendbar.
