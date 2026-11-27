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
- Ergänzt am 27.11.2026 um den Nachtrag „Chats und Artefakte im Projekt, Projekte mit Farbe“. Dieser Teil wird
  vorgezogen und wartet nicht auf die Abnahme der Bibliotheken.

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

### 3. Ablage und Standardprojekt „AresStack“
- Projekte gehören wie die Favoriten heute zum Bedienprofil der App (ADR 0034, Abschnitt 2), nicht zum Core und
  nicht in die Konfigurationsdatei. Jedes Projekt hat einen eigenen Ordner im Datenordner der App. Darin liegt je
  Bestandteil eine Datei mit Versionsfeld.
- Für alle Dateien gelten dieselben Regeln wie für `favorites.json`:
  - Es wird atomar geschrieben.
  - Eine unlesbare Datei kommt in Quarantäne und wird nie überschrieben.
  - Bei einer Umwandlung wird vorher einmalig gesichert.
- **Die bisherige Favoritenliste wird zum Standardprojekt „AresStack“.** Die heutige `favorites.json` wird beim
  ersten Start einmalig gesichert und unverändert als Favoriten von „AresStack“ übernommen, samt Ordnern. Nichts geht
  verloren. „AresStack“ lässt sich weder umbenennen noch löschen (Angelo, 27.11.2026, 16:27 MEZ: „Wir haben ein
  Standard-Default-Projekt, das immer AresStack heißt. Und das kann man nicht ändern.“ Zuvor hieß es hier
  „AresStack“ und war umbenennbar).
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
  Favoritenansicht gewählt ist; ohne Wahl ist es „AresStack“.
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
    Eine Kernfrage ohne Beleg ist sichtbar. Der Nachtrag vom 27.11.2026 ordnet das neu ein: Die Fragen aus dem
    Auftrag zeigen nur Lücken an, sie sind nicht das Ziel.
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
- Die Favoritenansicht zeigt oben die Projekte, „AresStack“ zuerst. Ein Projekt klappt zu seinem Favoritenbaum
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

## Nachtrag vom 27.11.2026: Erschließen statt Abfragen

Quelle: Angelos Nachricht vom 27.11.2026, 00:50 MEZ, und der Auftrag des Koordinators von 00:53 MEZ. Der Nachtrag
ergänzt die Abschnitte 6 und 7. Er ändert nichts an den Abschnitten 1 bis 5.

### N1. Ziel der Schreibphase
- Der Leser kennt das Thema nicht, sonst bräuchte er das Dokument nicht. Er steckt nur den Rahmen ab: Mission und
  Weidezaun. Die Einzelheiten kann er nicht vorgeben.
- Der Mehrwert sind die **Eckpfeiler**, die erst im Material sichtbar werden, und die Fragen, die der Leser mangels
  Fachkenntnis nicht stellen konnte. Aus einem unscharfen Bild soll ein scharfes werden.
- Ausgewählt wird deshalb nicht danach, ob der Benutzer nach einem Sachverhalt gefragt hat. Maßgeblich ist, ob der
  Leser ihn verstehen muss, um den Gegenstand zu begreifen. Kriterien:
  - Gegenstandsbezug
  - Grundlagenwissen
  - Erklärungswert
  - Vernetzung
  - Erkenntnisgewinn
  - angemessene Detailtiefe
- Die Größe eines Clusters ist kein Maß für Wichtigkeit.
- Fragen, die sich allein aus dem Auftrag ergeben, zeigen nur Lücken an. Wie viele davon das Material beantwortet,
  ist eine Nebengröße, nicht das Ziel.

### N2. Recherche und Schreiben sind eine Schleife
- **Befund:** Die Entdeckung endet am Rand des Materials. Was nicht in den Favoriten liegt, bildet keinen Cluster
  und wird nie zur Frage. Das Recherchekonzept stammt von jemandem ohne Fachkenntnis; seine blinden Flecken wandern
  sonst unbemerkt ins Dokument.
- **Entscheidung:** Phase 3 und Phase 4 laufen als Schleife, nicht als einmalige Abfolge. Eine Runde hat vier
  Schritte:
  1. Writing4j analysiert das Material zum Materialstand. Es meldet entdeckte Eckpfeiler mit zu wenig Belegen und
     offene Fragen als **Nachrecherche-Bedarf**, je mit Eckpfeiler, Frage und Grund.
  2. Aus dem Bedarf werden Suchaufträge für Research4j oder Vorschläge an den Benutzer.
  3. Neue Quellen werden Favoriten des Projekts. Herkunft „Recherche“ und die Runde sind am Favoriten sichtbar.
  4. Die nächste Analyse bekommt einen neuen Materialstand (Abschnitt 4.3).
- **Ende der Schleife.** Sie endet, sobald einer dieser Fälle eintritt:
  - Eine Runde bringt innerhalb des Weidezauns keine neuen Eckpfeiler.
  - Die eingestellte Höchstzahl an Runden ist erreicht.
  - Der Benutzer bricht ab.

  Nie endet sie stillschweigend: Der Bestandteil „Recherche“ zeigt je Runde, was gesucht und was gefunden wurde.
- **Wer die Schleife steuert.** Die Anwendung steuert sie, also der Adapter in Space Harvester. Keine Bibliothek
  ruft die andere auf, beide bleiben voneinander unabhängig (Abschnitt 6). Die Schleife ist keine Core-Funktion.
- **Selbsttätig oder vorgeschlagen.**
  - Hat der Benutzer für das Projekt eine automatische Recherche gestartet, läuft die Nachrecherche in diesen
    Grenzen selbsttätig weiter.
  - Bei einem von Hand gefüllten Projekt wird sie nur vorgeschlagen.
- **Weidezaun.** Er wirkt in jeder Runde auf die einzelne Passage, bevor geclustert wird. Sonst bilden fachfremde
  Kapitel einer aufgenommenen Quelle eigene Themen.
- **Recherchekonzept.** Entdeckte Eckpfeiler dürfen es erweitern. Es bleibt reine Suchsteuerung und geht weiterhin
  nie an Writing4j.
- **Manuskript.** Geschrieben wird erst aus dem Materialstand der letzten Runde. Frühere Runden liefern nur Analyse.
- **Betriebsarten.** Die Fragenernte je Cluster und das Beantworten je Frage sind unabhängige Teile eines Auftrags
  (Abschnitt 7). Mit einer GPU laufen sie nacheinander, in der Cloud nebeneinander, mit demselben Ergebnis.

### N3. Beziehung „setzt voraus“
- **Befund:** Eine solche Beziehung gibt es in Space Harvester nicht. Die Kanten des Wissensgraphen sind Verben aus
  Aussagen (`DomainTermGraph`). `RelationKind.BEFORE` ist die zeitliche Abfolge von Handlungen. Beides wird nicht
  zur Wissensvoraussetzung umgedeutet.
- **Entscheidung:** Die didaktische Gliederung entsteht aus der Beziehung „A setzt B voraus“ zwischen Eckpfeilern
  bzw. Fragen.
  - Jede Beziehung trägt einen **Beleg**: die Passage mit Seite, Revision und Fundstelle, in der A mit Hilfe von B
    erklärt wird.
  - Eine Beziehung ohne Beleg gilt als „vermutet“. Sie ist sichtbar, bestimmt aber nie allein die Reihenfolge.
- **Zyklen** werden erkannt und nie stillschweigend aufgelöst.
  - Begriffe, die sich gegenseitig voraussetzen, bilden eine gemeinsame Einheit. Sie werden zusammen eingeführt.
  - Ist das nicht sinnvoll, wird der Zyklus an der am schwächsten belegten Beziehung aufgetrennt.
  - Beides steht mit Grund im Bestandteil „Qualität“.
- **Reihenfolge.** Sie ist deterministisch: topologisch nach den belegten Beziehungen, bei Gleichstand nach Zahl
  der Belege, dann nach Eingabereihenfolge.
- **Wo es liegt.** Die Beziehung entsteht in Writing4j und ist ein Kriterium seiner Abnahme. Space Harvester legt
  das Ergebnis in „Wissensanalyse“ und „Gliederung“ ab.
- **Hinweise aus dem Bestand.** Gibt es zu den Seiten bestätigte Glossarbegriffe und Aussagen, darf der Adapter sie
  als Hinweise mitgeben. Voraussetzung ist das nicht: Der Wissensgraph kennt nur bestätigte Begriffe, und bei einem
  neuen Thema ist das Glossar leer.

### N4. Messung
- Die Abdeckung vorab formulierter Fragen misst das Ziel aus N1 nicht.
- **Sollwert:** Gemessen wird an einem Thema mit anerkanntem, öffentlich zugänglichem Lehrwerk. Sein
  Inhaltsverzeichnis ist das Soll der Eckpfeiler. Das Lehrwerk selbst liegt nicht im Material.
- **Messgrößen:**
  - Anteil der Soll-Eckpfeiler, die gefunden wurden,
  - Anteil fachfremder unter den behaltenen Fragen,
  - Übereinstimmung der Reihenfolge mit dem Lehrwerk, paarweise über „setzt voraus“,
  - als Nebengröße die Abdeckung der Fragen aus dem Auftrag.
- Gemessen wird in beiden Betriebsarten, mit einer GPU und parallel.
- Im Repo liegt nur die Liste der Soll-Eckpfeiler als Begriffe. Texte des Lehrwerks und interne Inhalte liegen
  nicht im Repo.

### N5. Befund zum Clustern in Space Harvester
`TopicClusterer` (`application.passages`) gruppiert Absätze eines einzelnen Dokuments rein nach Ähnlichkeit der
Vektoren. Für das Material eines ganzen Projekts ist er nicht gebaut: Er vergleicht in jeder Runde alle Paare neu.
Das Clustern über das Projektmaterial gehört zu Writing4j. `TopicClusterer` wird dafür weder umgebaut noch kopiert.

## Nachtrag vom 27.11.2026: Chats und Artefakte im Projekt, Projekte mit Farbe

Quelle: Angelos Nachricht vom 27.11.2026, 16:21 MEZ: „Ich möchte meinen Chats außerdem eine Farbe geben, so wie die
Tabs in Firefox. Da kann ich ja auch Gruppen erstellen von Tabs. Und genauso möchte ich hier farbige Gruppen von Chats
erstellen. Das sind dann quasi Projekte. […] Wenn ich an irgendwas gerade arbeite, dann reicht ja oftmals nicht nur
ein Chat aus. Und dann könnten wir die Artefakte, die wir erzeugen, […] dem ganzen Projekt verfügbar machen.“

Dazu Angelos Nachricht von 16:24 MEZ: „Wir opfern den Schriftzug unten in der Ecke, beziehungsweise setzen das nur
für das Default-Projekt. […] Wir machen da so einen kleinen Pfeil nach oben, rechts neben den Schriftzug. […] Und dann
kann man damit das Projekt auswählen. Global, über alle Perspektiven hinweg. […] Das muss man aber nicht nutzen, man
kann auch einfach das Default-Projekt nutzen.“

Der Nachtrag ergänzt die Abschnitte 1, 2, 3 und 8, ändert in Abschnitt 4.1, was „aktives Projekt“ heißt (F6), und
ändert [ADR 0045](0045-schreibumgebung-im-chat.md) in Abschnitt 1.

### F1. Eine farbige Gruppe von Chats ist ein Projekt
- Es gibt nur einen Begriff „Projekt“. Die farbige Gruppe von Chats ist kein zweites Ding neben dem Projekt aus
  Abschnitt 1, sondern dasselbe Projekt mit zwei weiteren Bestandteilen: **Chats** und **Artefakte**.
- Ein Projekt hat neben ID und Namen eine **Farbe** aus einer festen Auswahl. Die Auswahl legt der UX-Manager fest.
- Die Farbe kennzeichnet überall, was zum Projekt gehört: den Chat in der Liste, den Kopf des geöffneten Chats und
  die Reiter der Artefakte.

### F2. Chats im Projekt
- Ein Chat gehört zu genau einem Projekt, wie ein Tab zu einer Gruppe. Ein Chat ohne eigene Zuordnung gehört zum
  Standardprojekt „AresStack“ (Abschnitt 3) und verhält sich wie heute. Alle vorhandenen Chats sind damit ohne
  Umwandlung Chats des Standardprojekts.
- Die Zugehörigkeit steht am Chat selbst (ein Feld mit der Projekt-ID). Sie geht mit dem Chat in den Papierkorb und
  zurück. Fehlt das Feld oder verweist es auf ein Projekt, das es nicht mehr gibt, gehört der Chat zum
  Standardprojekt.
- Der Bereich eines Chats (`ChatNode`, was links gewählt ist) bleibt davon unberührt. Ein Projekt fasst Chats aus
  verschiedenen Bereichen zusammen.
- Bedienung:
  - Am Chat: „Zu Projekt hinzufügen“ mit der Liste der Projekte und „Neues Projekt“, sowie „Aus Projekt
    entfernen“. Entfernen legt den Chat zurück ins Standardprojekt.
  - Die Chat-Liste zeigt zuerst die Chats des aktiven Projekts (F6), gegliedert wie heute in „In diesem Bereich“ und
    „Übrige Chats“. Darunter stehen die anderen Projekte in ihrer Farbe. Jedes klappt zu seinen Chats auf und bietet
    „Neuer Chat in diesem Projekt“. Jeder Chat steht in der Liste genau einmal. Ist das Standardprojekt aktiv und
    gibt es kein weiteres Projekt, sieht die Liste aus wie heute.
  - Ein neuer Chat gehört zum aktiven Projekt und beginnt im gerade gewählten Bereich.
  - Am Projekt: „Umbenennen“, „Farbe ändern“, „Löschen“.

### F3. Artefakte gehören dem Projekt
- Ein Artefakt der Schreibumgebung (ADR 0045) hat genau einen Besitzer: im Standardprojekt den Chat, in jedem
  anderen Projekt das Projekt. Im Standardprojekt teilen Chats ihre Artefakte also nicht, wie heute.
- Alle Chats eines Projekts sehen, öffnen und bearbeiten dessen Artefakte. Die Werkzeuge der KI arbeiten in einem
  Projekt-Chat auf den Artefakten des Projekts.
- Kommt ein Chat mit eigenen Artefakten in ein Projekt, ziehen sie ins Projekt um. Verweise zwischen Artefakten
  bleiben dabei gültig.
- Verlässt ein Chat das Projekt oder wird er gelöscht, bleiben die Artefakte im Projekt.
- Bearbeiten zwei Chats dasselbe Artefakt, gilt der Vertrag aus ADR 0045: ein Speicher, jede Änderung eine Revision,
  nichts wird überschrieben.

### F4. Ablage
- Es gilt Abschnitt 3: je Projekt ein Ordner im Datenordner der App, darin je Bestandteil eine Datei mit
  Versionsfeld, atomar geschrieben, Quarantäne statt Überschreiben.
- Vorgezogen wird nur, was die Chats brauchen: das Projekt mit ID, Name und Farbe, der Bestandteil „Artefakte“ und
  das aktive Projekt. Das Standardprojekt „AresStack“ gibt es dabei von Anfang an, mit fester ID, festem Namen und
  ohne Farbe. Die Umwandlung der Favoritenliste in die Favoriten von „AresStack“ (Paket P1) bleibt, wo sie ist, und
  benutzt später dieselbe Projektablage.

### F5. Löschen eines Projekts
- Nur nach „Projekt wirklich löschen?“ Ja/Nein. Die Frage nennt, wie viele Chats und Artefakte betroffen sind.
- Die Chats bleiben erhalten und gehören danach zum Standardprojekt. Die Artefakte des Projekts gehen mit dem
  Projekt in den Papierkorb und lassen sich wie ein gelöschter Chat bis zum Neustart wiederherstellen.
- Das Standardprojekt lässt sich weder umbenennen noch löschen. War das gelöschte Projekt aktiv, wird das
  Standardprojekt aktiv.

### F6. Aktives Projekt, global gewählt
- Es gibt für die ganze App genau ein **aktives Projekt**, über alle Perspektiven hinweg. Ohne Wahl ist es das
  Standardprojekt. Die Wahl gehört zum Bedienprofil und bleibt über einen Neustart erhalten.
- **Ort der Wahl:** unten in der Ecke beim Schriftzug „AresStack“. Im Standardprojekt steht dort der Schriftzug wie
  heute,
  rechts daneben ein kleiner Pfeil nach oben. In jedem anderen Projekt stehen dort statt des Schriftzugs Name und
  Farbe des Projekts. Der Pfeil öffnet nach oben die Liste der Projekte mit „Neues Projekt“.
- Der Schriftzug „AresStack“ ist damit der Name des Standardprojekts und steht nur dort. Die Jahreszahl des
  Copyright-Vermerks daneben ist immer das laufende Jahr nach dem Datum des Rechners, nie eine feste Zahl.
- Wer keine Projekte anlegt, merkt davon nichts außer dem Pfeil.
- **Was das aktive Projekt bestimmt:**
  - Neue Chats gehören zum aktiven Projekt, in jeder Perspektive.
  - Die Chat-Liste zeigt seine Chats zuerst (F2).
  - Der Stern der Favoriten wirkt auf das aktive Projekt. Damit ändert sich Abschnitt 4.1 in einem Punkt: Aktiv ist
    nicht mehr das in der Favoritenansicht gewählte Projekt, sondern dieses eine, global gewählte.
- Öffnet der Benutzer einen Chat eines anderen Projekts, wird dessen Projekt aktiv. So zeigt die Ecke immer das
  Projekt, in dem gerade gearbeitet wird, und Artefakte landen dort, wo die Ecke es sagt.
- Der Wechsel wird den Ansichten gemeldet (ADR 0027), niemand fragt ab. Ein Wechsel startet nichts von selbst: keinen
  Abgleich, keinen KI-Lauf.

### F7. Umsetzung, vorgezogen
- K1: Projektablage (ID, Name, Farbe) und Projekt-ID am Chat. Reine Logik mit Tests.
- K2: Chat-Liste mit Projekten, Zuordnen und Entfernen, neuer Chat im Projekt, Farbe an Chat und Reitern (K1).
- K3: Besitzer eines Artefakts ist Chat oder Projekt, Umzug beim Beitritt (K1 und der Artefakt-Speicher aus
  ADR 0045). Dieses Paket gehört zum Bau der Schreibumgebung, damit nur eine Stelle den Artefakt-Speicher ändert.
- K4: Aktives Projekt im Bedienprofil und die Wahl unten in der Ecke, über alle Perspektiven (K1).

## Umsetzung

Erst nach der Abnahme von Research4j und Writing4j. Dann starten alle Pakete der Gruppe 1 sofort und gleichzeitig
gegen develop. Jedes Paket der Gruppe 2 baut nur auf Gruppe 1 auf und auf der abgenommenen Bibliothek, nie auf ein
anderes Paket der Gruppe 2.

**Gruppe 1**
- C1: `Selection.Root.page` im Core samt `Holdings.resolve`, Ausgaben und kanonischer Auswahl. `ChatPanel.onlyPage`
  wird darauf umgestellt.
- C2: Typ `Materialstand` (PageKey, Revision, Fingerabdruck), nur Vertrag.
- P1: Projektablage mit Standardprojekt „AresStack“, Umwandlung von `favorites.json`, Sicherung, Quarantäne.
- P2: Favorit mit Umfang (`PAGE_ONLY`) und Einmaligkeit je Projekt, Stern auf das aktive Projekt.

**Gruppe 2**
- P3: `Selection.Collection` mit Projekt-ID; Projekt als Auswahl in Chat, Export, Stand, Analysen (C1, P1).
- P4: Adapter Research4j: Auftrag und Konzept hinein, Quellenverweise als Favoriten heraus (P1, P2).
- P5: Adapter Writing4j: Materialstand und Material hinein, Ergebnisse in die Bestandteile heraus (C1, C2, P1).
- P6: Projektansicht mit Reitern (UX-Manager; P1).

## Offene Punkte
- Freigegebene oder gemeinsame Projekte mehrerer Benutzer sind nicht Teil dieser Runde.
- Ob die Nachrecherche selbsttätig läuft: Vorgabe siehe Nachtrag, Abschnitt N2, bis Angelo anders entscheidet.
- Die Schwellen für die Abnahme der Messung (Nachtrag, Abschnitt N4) werden nach der ersten Messung festgelegt.

## Folgen
- Space Harvester wird zusätzlich zur Wissensverwaltung eine Arbeitsumgebung für wissensbasierte Projekte. Der
  Core bleibt dabei die Dokumentbibliothek.
- ADR 0034 gilt weiter. Geändert werden nur die globale Einmaligkeit eines Favoriten und der Verzicht auf die
  Wurzel „nur Seite“.
- Research4j und Writing4j bleiben ohne Space Harvester verwendbar.
