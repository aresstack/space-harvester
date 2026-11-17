# ADR 0031: Thematische Update-Zusammenfassung je Quelle und je Space (Nachtrag zu ADR 0030)

- Status: **Angenommen** für Angelos Vorgaben (16./17.11.2026); Entwurf des Architekten (Strang AT), 17.11.2026. Die
  offenen Punkte unten gelten als Empfehlung, bis Angelo widerspricht.
- Konzept für Angelo: https://claude.ai/artifact/RyDFKP37WYHDGMLH2SYxbt
- Baut auf: ADR 0030 (Protokoll, Marke, Bericht, Unterschied je Seite, Zusammenfassung), ADR 0020 (Themen-Clustering),
  ADR 0024 (Auftrag nach Muster Aussagenanalyse), ADR 0027 (kein Polling), ADR 0028 (KI-Freigabe je Seite),
  ADR 0029 (Auswahl links bestimmt die Mitte).

## Vorgaben Angelos (16./17.11.2026, bindend)

1. Updates und KI-Zusammenfassung gibt es auf der Ebene der Wissensquelle (über alle Spaces) UND auf der Ebene des
   Space.
2. Die Zusammenfassung ist inhaltlich, nicht chronologisch: Derselbe Themenkomplex, an mehreren Tagen, auf mehreren
   Seiten, von mehreren Personen geändert, ist EINE Änderung.
3. Die KI muss das nicht allein lösen: vorverarbeitete Daten und Werkzeuge sind ausdrücklich erwünscht.
4. Vorhandene Vorverarbeitungen sollen der KI zuarbeiten: der experimentelle Wissensgraph (Aussagen, MaltParser, noch
   nicht erprobt) und die NLP-Satzzerlegung mit Ähnlichkeitsvergleich.
5. Oberfläche: Reiter „Text“ zeigt die Updates (Material, „maschineller“), die „Leseansicht“ (Buch) die
   KI-Zusammenfassung; die Textseite ist die Quelle der Zusammenfassung. Updates stehen als Wolkenleiste oben wie das
   Inhaltsverzeichnis, standardmäßig ist die letzte Änderung markiert.

Alles Übrige ist Vorschlag des Architekten.

## Entscheidung

### 1. Ebenen
- Die Update-Marke bleibt je Quelle (ADR 0030 Abschnitt 3). Die Space-Ebene ist ein Ausschnitt desselben Updates:
  `updates`, `report`, Zähler und Zusammenfassung nehmen einen optionalen Space.
- Zusammenfassungen werden je (Quelle, Update, Ebene) abgelegt: Ebene = Quelle oder ein Space. Jede entsteht auf Wunsch.
- Rechte je Seite wie bisher; eine Zusammenfassung sieht nur, wer alle eingegangenen Seiten sehen darf.

### 2. Update-Text
- Der Core bildet zu einem Update und einer Ebene eine feste Textform (`update-text/v1`): je geänderter Seite Art,
  Pfad, Zeitpunkte, Versionen und die geänderten Absätze mit Überschriftenpfad. Sie ist Anzeige des Reiters „Text“,
  Kopier- und Exportform und, gefiltert nach KI-Freigabe, das Material der KI. Eine Projektion, kein zweiter Bestand.

### 3. Änderungseinheiten (Vorverarbeitung, ohne KI-Text)
- Beim Bilden des Unterschieds (CL5, im Commit) wird je geändertem Absatz zusätzlich der Überschriftenpfad seines
  Abschnitts abgelegt; die Protokollzeile trägt Bearbeiter und Labels, soweit die Quelle sie liefert. Diese Angaben
  sind nachträglich nicht zu gewinnen und haben deshalb Vorrang.
- Eine Änderungseinheit = geänderter Absatz (oder Seitenereignis ohne Text: neu, entfernt, umbenannt, verschoben) mit:
  Text alt/neu, Überschriftenpfad, Vektor, Begriffe (TermIndex), Teilbaum/Elternseite/Labels/Space, Verweise, Art der
  Änderung (regelbasiert: Korrektur, Zahl/Datum, neuer Abschnitt, entfallener Abschnitt, große Überarbeitung),
  Zeitpunkt, Bearbeiter.
- Vektoren kommen vom vorhandenen `EmbeddingProvider`, nur für Seiten mit `AiRelease.mayProcess`; abgelegt neben dem
  Unterschied, Schlüssel Text-Hash und Modellversion, gelöscht mit dem Unterschied.

### 4. Themenvorschläge
- Aus den Einheiten entsteht ein gewichtetes Netz: Kante = gewichtete Summe aus Vektor-Nähe, gleichem Abschnitt bzw.
  Teilbaum, gemeinsamen Begriffen und Labels, Verweisen zwischen den Seiten. Zeit und Bearbeiter sind KEIN
  Gruppierungsmerkmal.
- Gruppen entstehen deterministisch (agglomerativ wie `TopicClusterer`, auf der kombinierten Distanz); kleine
  Einzelthemen bleiben sichtbar. Je Gruppe: Mitglieder, Repräsentanten (nächste am Schwerpunkt), Gewicht (Seiten,
  Textmenge, Art), beteiligte Spaces, Zeitraum, Zahl der Bearbeiter, tragende Signale.
- Satzebene (im Core vorhanden: Satzzerlegung, Vektor- und Wortlautvergleich): geänderte Absätze werden in Sätze
  zerlegt und abgeglichen. Daraus: der tatsächlich geänderte Satz; gewanderter Text (auf Seite A entfernt, auf Seite B
  nahezu gleich eingefügt) gilt als Verschiebung, nicht als inhaltliche Änderung; dieselbe Änderung auf vielen Seiten
  wird eine Einheit mit mehreren Fundstellen.
- Aussagen (ADR 0024, `StatementAnalyzer`, nur in der Desktop-App belegt): für geänderte Sätze vorher und nachher
  gerechnet ergeben sich neue, entfallene und geänderte Aussagen. Gemeinsame Objekte verbinden Einheiten als weiteres
  Signal; der vorhandene Graph liefert Nachbarschaften. Experimentell: geringes Anfangsgewicht, eingeschaltet nur,
  wenn der Prüfstand eine Verbesserung zeigt; lexikalische Gleichheit bleibt Kandidat (keine Verschmelzung). Gerechnet
  nur im Auftrag „Zusammenfassen“, nie von selbst (ADR 0024 bleibt); ohne Analysator entfällt das Signal und das
  Ergebnis nennt das. Die Aussagen-Unterschiede gehen als vorverarbeitete Angabe je Thema an das Modell.
- Ohne Embedding-Dienst gruppiert der Core nur nach Struktur, Begriffen und Verweisen und kennzeichnet das.
- Ein Reranker wird nicht eingesetzt (er ordnet Treffer zu einer Frage; hier wird gruppiert).
- Die Themen werden je Quelle und Update einmal gebildet; die Space-Ebene beschränkt die Einheiten auf den Space.
- Prüfstand: feste Beispiel-Updates mit von Hand festgelegten Gruppen; Gewichte und Schwellen werden daran eingestellt.

### 5. Zusammenfassung (ersetzt das Verfahren update-summary-v1)
- Auftrag auf Wunsch, anhaltbar (ADR 0024), feste Schritte, kein frei handelnder Agent im Core:
  1. Prüfen: Das Modell erhält die Themenvorschläge mit Repräsentanten und antwortet mit Entscheidungen
     (zusammenlegen, teilen, belassen). Der Core prüft und führt sie aus; Unbrauchbares wird verworfen.
  2. Je Thema: Titel und Text, was sich inhaltlich geändert hat, gestützt auf die Absätze innerhalb eines Zeichenbudgets.
  3. Überblick über alle Themen.
- Ergebnis: Überblick, Themen (Titel, Text, Seiten, Spaces, Zeitraum, Zahl der Bearbeiter, Gewicht), Neu, Entfernt,
  Struktur, kleine Korrekturen als Zahl, nicht Ausgewertetes als Zahl. Reihenfolge nach Gewicht, nie chronologisch.
- Unverändert: nur freigegebene Seiten ans Modell (auch im Pfad), Seitentext ist Material, Antworten werden gegen die
  gefragten Seiten geprüft, unbekannter Inhalt wird nie geschätzt, Löschen bei Entzug der Freigabe, mit der Quelle und
  mit der Aufbewahrung, `stale` bei geändertem Bericht.

### 6. Werkzeuge
- Die vorverarbeiteten Daten stehen als Abfragen des Core bereit (REST): Updates je Ebene, Update-Text (ganz oder je
  Thema), Themen mit Seiten und Signalen, ähnliche Änderungen zu Text oder Begriff, Umgebung einer Änderung.
- Chat der App und Enterprise AI Client können sie als Werkzeuge anbieten. Für die Zusammenfassung selbst sind sie
  nicht nötig.

### 7. Oberfläche
- Quelle oder Space gewählt: Wolkenleiste (`DocumentNavigationSky`) mit einer Wolke je Update, das jüngste
  abgeschlossene vorausgewählt und mittig; „Seit dem letzten Update“ gestrichelt am Ende; Erstbefüllung und Updates
  ohne Änderungen in der Ebene blass. Aufgeklappt zeigt die zweite Reihe die Themen des gewählten Updates.
- „Text“: der Update-Text. „Leseansicht“: die Zusammenfassung als Lesetext, Seiten als Verweise. Aktionen als
  Icon-Knöpfe oben rechts. Beide Reiter folgen derselben Auswahl.
- Kein Pop-up, kein Polling.

**Leseansicht (Angelo, 17.11.2026).** Die Leseansicht zeigt immer die KI-Zusammenfassung des gewählten Updates und
der gewählten Ebene (Quelle oder Space). Fehlt sie, stößt die Leseansicht den Core-Auftrag „Zusammenfassen“ selbst
an, sobald sie sichtbar ist, und zeigt währenddessen „Zusammenfassung wird erstellt …“. Die Updates selbst stehen
unter „Text“.

Grenzen, die bleiben:
- Zusammengefasst wird nur beim Öffnen der Leseansicht, nie im Hintergrund, nie nach einem Abgleich und nie für
  verdeckte Reiter. Es gibt weiterhin keinen Pop-up-Zwang.
- Je Update und Ebene höchstens ein selbst angestoßener Auftrag. Nach einem Fehlschlag wird nicht von selbst
  wiederholt; die Ansicht bietet den Einstieg zum erneuten Versuch.
- Eine vorhandene, inzwischen veraltete Zusammenfassung (offenes Update mit neuen Änderungen) wird nicht von selbst
  neu erstellt; die Ansicht nennt, dass sie veraltet ist, „Zusammenfassen“ erstellt sie neu.
- Erstbefüllung und Updates ohne Änderung bekommen keine Zusammenfassung; ohne eingestelltes Chat-Modell sagt die
  Ansicht, warum keine da ist.
- Die Freigabegrenze bleibt im Core: Nur KI-freigegebene Seiten gehen ans Modell (ADR 0028). Namen von Bearbeitern
  gehen nie ans Modell (ADR 0031).
- Ein selbst angestoßener Auftrag, der noch nicht begonnen hat, wird abgebrochen, wenn der Benutzer Update oder
  Ebene wechselt, bevor er läuft. So löst das Durchklicken im Baum keine Reihe von Modellaufrufen aus. Ein
  laufender Auftrag läuft zu Ende, sein Ergebnis bleibt gespeichert.
- Der Core startet für dasselbe Update und dieselbe Ebene keinen zweiten Auftrag, solange einer läuft oder wartet;
  der Aufruf liefert den vorhandenen.

## Offene Punkte (Empfehlung gilt, bis Angelo widerspricht)
1. Marke je Quelle, Space als Ausschnitt (statt eigener Marke je Space).
2. Vorauswahl: jüngstes abgeschlossenes Update.
3. Bearbeiter: in der Zusammenfassung nur die Zahl, Namen höchstens im Reiter „Text“.

## Arbeitspakete
- TZ1 (Core, eilt): Space-Ebene für updates/report/Zähler/latestMark; Überschriftenpfad je geändertem Absatz beim
  Bilden des Unterschieds; Bearbeiter und Labels in der Zeile, soweit die Quelle sie liefert.
- TZ2 (Core): Update-Text `update-text/v1` je Ebene, Abfrage und REST.
- TZ3 (Core): Änderungseinheiten, Vektoren, Netz, Themenvorschläge, Art der Änderung, Gewicht, Prüfstand. Ohne KI-Text.
- TZ4 (Core): Zusammenfassung v2 mit Prüfschritt, je Thema, Überblick; Ablage je Ebene; stale; Löschregeln.
- TZ5 (App): Wolkenleiste der Updates, „Text“ mit Update-Text, Quelle und Space.
- TZ6 (App): „Leseansicht“ mit Überblick und Themen, Themen als zweite Wolkenreihe, Zusammenfassen je Ebene.
- TZ7 (Core und Chat): Werkzeuge für Rückfragen.

Parallel sofort: TZ1, TZ2, TZ3, TZ5. TZ4 und TZ6 gegen eine früh festgelegte Schnittstelle; TZ7 zuletzt.
