# ADR 0035: Fristen-Fachlogik nach Grundsatz und Ausnahme

- Status: **Entwurf** (23.11.2026), zur Prüfung durch den Architekten.
- Bezüge: [ADR 0026](0026-anhaenge-und-enthaltene-dateien-als-dokumente.md) (Dokumenttext, Fundstellen),
  [ADR 0032](0032-fachlogik-aus-aussagen.md) (Aussagen), [ADR 0033](0033-wissen-des-chats-per-verweis-und-werkzeug.md).
  Ergebnismodell: Paket `application.extraction` aus dem Strang „Extraktionsansicht für E-Akte-Dokumente“ (ein Modell
  für beide Stränge). Keine neue Bibliothek.

## Vorgaben Angelos (23.11.2026, bindend)

1. **Grundsatz-Ausnahme-Prinzip**: Es gilt die gesetzliche Standardfrist, sofern sie nicht durch eine einschlägige
   Ausnahme verdrängt wird (Richtlinien, Dienstanweisungen, Erlasse, Einzelgenehmigungen). Die Ausnahmen sind
   zahlreich und verstreut; ihre Suche, Auswahl und Bewertung ist das eigentliche Problem, nicht die Fristberechnung.
2. **Drei Ergebniszustände**: *Standardfrist bestätigt* (Ausnahmenprüfung abgeschlossen, keine einschlägige),
   *Abweichende Frist bestätigt* (wirksame, einschlägige Ausnahme nachgewiesen), *Vorläufige Standardfrist*
   (Ausnahmen nicht vollständig geprüft). Keine gefundene Ausnahme heißt nicht, dass es keine gibt.
3. Eine besondere Vorschrift verdrängt nicht automatisch: geprüft werden Sachverhaltsbezug, Wirksamkeit zum
   maßgeblichen Zeitpunkt und Rechtswirkung.
4. **Schritt 1 ist die Anzeige**: Die Extraktion zeigt dem Nutzer die relevanten Informationen, vor allem die
   Merkmale, an denen Ausnahmen erkennbar sind. Die Regelanwendung prüft der Nutzer vorerst selbst.
5. Drei getrennte Bereiche: **Dokumentfakten**, **Rechtswissen**, **Prüfergebnisse**. Die KI mischt nie Gefundenes
   mit Recherchiertem.
6. Nichts erfinden: Fehlendes bleibt unbekannt; Erlassdatum ist nicht Entstehungsdatum; Antrag ist keine Genehmigung;
   Widersprüche bleiben sichtbar.

## Befund im Code (develop, 138e3560)

| Baustein | Stelle | Eignung |
| --- | --- | --- |
| `DocumentText` / `TikaDocumentText` | `application.assets`, `source` | liefert `Extracted(text, truncated)`; Eingang der Fachlogik, kein zweiter Parser |
| `DocumentStructure` / `StructuredDocument` | `application.assets` | Blöcke (Überschrift, Absatz, Tabelle) für Abschnittsbezug der Fundstellen, später |
| `StatementChat` | `application.enrichment.statements` | KI-Port des Core (Nachrichten → Antwort); `RemoteAi#statementChat` ruft gestreamt (`streamed(...)`) |
| `ChatStatementExtractor` | dito | Muster für strenge JSON-Antwort + Reparaturanfrage; Inhalt (Erwähnungen/Handlungen) passt **nicht** zu Merkmalen |
| `StatementValidator`, `TextSpan`, `GroundedText` | dito | Verankerungsregel „Wortlaut muss an der Stelle stehen, eindeutig“ — wird für Merkmale wiederverwendet |
| `PageSearchIndex` | `application.query` | Seitenindex je Quelle mit Generationen und Rechteprüfung; **kein** Rechtsquellenindex, nicht umdeuten |

Folge: Die KI-Stufe nutzt den Port `StatementChat` (umbenennen in einen allgemeinen Namen ist ein eigener
Refaktor-PR, falls der Architekt es will). Die Verankerung (Text an Stelle prüfen, einmal vorkommender Text repariert
einen falschen Bereich, zweimal = mehrdeutig) wird aus `StatementValidator` in eine gemeinsame Hilfe
`TextGrounding` herausgezogen und von beiden genutzt, nicht kopiert. Für Rechtsquellen gibt es vorerst nur den Port.

## Entscheidung

### 1. Ein Ergebnismodell, drei Bereiche
- **Dokumentfakten** = `application.extraction` (`DocumentAnalysis`, `AnalysisSection` je Sachverhalt,
  `ExtractedField` mit `origin` REGEX/AI/DERIVED, `status` EXPLICIT/DERIVED/NOT_FOUND/CONFLICT, `verified`,
  `SourceReference`). Ergänzt um `group` (Felder einer Fristaussage), `notes` (Prüfhinweise mit festen Codes) und
  `processors` (Kennung+Version je Stufe). `DeadlineAnalysisService` implementiert `DocumentAnalysisProvider`.
- **Rechtswissen** = Port `LegalKnowledgeRepository` (Treffer mit Quelle, Fundstelle, Ausschnitt, Gültigkeit,
  Herkunft). Erste Umsetzung In-Memory; der Zustand „nicht eingerichtet/leer“ ist ausdrücklich abfragbar.
- **Prüfergebnisse** = eigenes Modell in `application.deadlines` (`DeadlineAssessment`), nie in `ExtractedField`.

### 2. Extraktion in drei Stufen (Paket `application.deadlines`)
1. **Regex** (`IdentifierExtractor`): Kennzeichen mit Rohschreibweise und normalisiertem Wert (Bewirtschafternummer,
   Objektkonto, Buchungsstelle, Geschäftszeichen, Daten, Abschnitt/Unterabschnitt, Vorschuss-/Verwahrungsbuch,
   Fristformulierungen per Schlüsselwort „spätestens“, „bis zum“, „innerhalb von“). Sachverhalte werden an
   Abschnittsgrenzen und Kontonennungen getrennt; ein Wert wird nie über Sachverhalte hinweg zugeordnet.
2. **KI** (`AiFactExtractor` über `StatementChat`, gestreamt): strenges JSON, jeder Wert mit wörtlichem Zitat;
   Schwerpunkt Fristaussagen, Bedingungen, Ausnahmen, Antrag vs. Genehmigung, durchlaufende Gelder,
   Fristbezug (einzelne Zahlung / Konto), abweichende Buchungsreihenfolge, Verweise auf Normen und Erlasse.
3. **Validierung** (`ExtractionValidator`): Zitat muss im Originaltext stehen (`TextGrounding`), sonst verworfen
   oder `verified=false`. Regex und KI werden zusammengeführt; abweichende Werte desselben Schlüssels im selben
   Sachverhalt werden beide CONFLICT.

Ohne KI-Dienst liefert Stufe 1 einen regelbasierten Teilbefund mit Hinweis `AI_UNAVAILABLE`; ein abgeschnittener
Text trägt `TEXT_TRUNCATED`.

### 3. Merkmale für die Ausnahmesuche
Die Extraktion priorisiert die Merkmale, an denen eine Ausnahme erkennbar ist: Kontoart (Vorschuss/Verwahrung),
Einzel- oder Sammelkonto, Kontenzweck (z. B. durchlaufende Gelder, Sockelbetrag einer Geldstelle), Buchungsstelle,
Bewirtschafter, Geltungszeitraum, genannte Normen und Bezugserlasse, Fristaussagen samt Bedingung, Anträge und
Genehmigungen (getrennt, `note` „beantragt“/„genehmigt“). Daraus entsteht später die Suchanfrage an
`LegalKnowledgeRepository`.

### 4. Regelkatalog und Evaluator (erst nach der Extraktion)
- `DeadlineRuleCatalog`: versionierte Regeln mit Rechtsgrundlage, **belegtem Wortlaut und Quelle**, Gültigkeit,
  Voraussetzungen, Ausschlussbedingungen, Art (STANDARD oder AUSNAHME mit verdrängter Regel).
- `DeadlineRuleEvaluator` prüft nach Grundsatz und Ausnahme und liefert genau einen der drei Zustände je
  Sachverhalt: `STANDARD_CONFIRMED`, `DEVIATION_CONFIRMED`, `STANDARD_PROVISIONAL`. `STANDARD_CONFIRMED` ist nur
  erreichbar, wenn die Ausnahmenprüfung als abgeschlossen gilt; solange der Rechtsquellenindex leer oder
  unvollständig ist, ist das Ergebnis höchstens `STANDARD_PROVISIONAL`. Wer die Prüfung abschließen darf
  (Nutzerbestätigung, vollständiger Katalog für einen Kontenzweck), ist offen (Punkt O2).
- Je Ausnahme: Sachverhaltsbezug, Wirksamkeit zum maßgeblichen Zeitpunkt, Rechtswirkung, jeweils
  nachgewiesen/nicht nachgewiesen/offen. Ein Antrag zählt nie als Genehmigung.
- Ohne Entstehungsdatum keine konkrete Frist, sondern die abstrakte Regel („Ende des zweiten auf die Entstehung
  folgenden Haushaltsjahres“). Das Erlassdatum wird nie als Entstehungsdatum genommen.
- Entscheidungen fallen nur im Core; das Command Deck zeigt an.

### 5. Rechtsnormen im Katalog
Nur mit belegtem Wortlaut aus offizieller Quelle, versioniert. Belegt (gesetze-im-internet.de, § 60 BHO, abgerufen
23.11.2026): Abs. 1 Satz 2 „Ein Vorschuß ist bis zum Ende des zweiten auf seine Entstehung folgenden Haushaltsjahres
endgültig zu buchen; Ausnahmen bedürfen der Einwilligung des Bundesministeriums der Finanzen.“ Nr. 6 und Nr. 8
VO/VW-RiB (Fassung 2021) sind **noch nicht belegt**; sie kommen erst mit Wortlaut aus einer offiziellen Quelle in
den Katalog (offen O1).

### 6. Tests
Der Referenz-Erlass wird nur **synthetisch nachgebildet** mit erfundenen Nummern (Bewirtschafter, Konto, Datum,
Geschäftszeichen); kein echter Inhalt im Repo. Abgedeckt werden alle Abnahmekriterien des Auftrags
(Kennzeichen, Buchungsstelle vs. Objektkonto, durchlaufende Gelder, getrennte Fristaussagen, Bezug einzelne
Zahlung, abweichende Buchungsreihenfolge, kein Entstehungsdatum aus dem Erlassdatum, mehrere Sachverhalte, keine
erfundenen Fundstellen, leere Wissensbasis, ohne KI, Versionen, Unvollständigkeit) sowie synthetisch: mehrdeutige
Kennzeichen, widersprüchliche Fristen, Verlängerungsantrag, genehmigte Ausnahme.

## Pakete (kleine PRs auf develop)

| Paket | Inhalt | hängt ab von |
| --- | --- | --- |
| P1 | `TextGrounding` aus `StatementValidator` herausziehen (Verhalten unverändert) | – |
| P2 | Regex-Stufe + Sachverhaltstrennung + `DeadlineAnalysisService` als Provider, synthetischer Erlass | Modell-PR der Extraktionsansicht |
| P3 | KI-Stufe über `StatementChat` + Validierung + Zusammenführung; Live-Test gegen Ollama | P1, P2 |
| P4 | `LegalKnowledgeRepository` + In-Memory-Adapter | – |
| P5 | Regelkatalog (§ 60 BHO, VO/VW-RiB erst mit Beleg) + Evaluator mit drei Zuständen | P2, P4 |

## Offene Punkte
- O1: Offizielle Quelle für den Wortlaut von Nr. 6/8 VO/VW-RiB (Angelo hat sie geprüft; bitte Link oder Datei).
- O2: Wann gilt die Ausnahmenprüfung als abgeschlossen? Vorschlag: nur durch ausdrückliche Bestätigung des Nutzers;
  bis dahin `STANDARD_PROVISIONAL`.
- O3: Sollen Ausnahmen aus früheren Erlassen (Bezugsschreiben) in denselben Rechtsquellenindex? Vorschlag ja,
  unterschieden über Metadaten.
