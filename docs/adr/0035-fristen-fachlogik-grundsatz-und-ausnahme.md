# ADR 0035: Fristen-Fachlogik nach Grundsatz und Ausnahme

- Status: **Angenommen** (23.11.2026), vom Architekten abgenommen. Vorgaben Angelos bindend.
- Bezüge: [ADR 0012](0012-core-als-gemeinsame-bibliothek.md) §4 (Abgrenzung), [ADR 0026](0026-anhaenge-und-enthaltene-dateien-als-dokumente.md)
  (Dokumenttext), ADR 0027 (Beobachter statt Abfragen), ADR 0028 (KI-Freigabe je Seite),
  [ADR 0032](0032-fachlogik-aus-aussagen.md) (Analyseschritte, `StepModel`). Ergebnismodell: Paket
  `application.extraction` (PR #1279, Strang „Extraktionsansicht für E-Akte-Dokumente“), ein Modell für beide
  Stränge. Keine neue Bibliothek.

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

## Befund im Code (develop, 07e74afe)

| Baustein | Stelle | Verwendung |
| --- | --- | --- |
| `DocumentText` / `TikaDocumentText` | `application.assets`, `source` | `Extracted(text, truncated)`; Eingang, kein zweiter Parser |
| `StepModel` / `ChatStepModel` | `application.stepmodel`, `source.ai` | KI-Port der Analyseschritte (ADR 0032): Modellzeile je Schritt, Temperatur 0, abgeschnittene Antwort wirft |
| `ChatStatementExtractor` | `application.enrichment.statements` | nur die JSON-Reparaturanfrage wird als `StrictJsonAnswer` herausgelöst |
| `StatementValidator`, `TextSpan`, `GroundedText` | dito | Verankerung wird als `TextGrounding` herausgelöst |
| `KeywordSearch`, `RelevanceSearch` | `application.query`, `application.ranking` | Suche des Standardadapters für Rechtswissen |
| `PageSearchIndex` | `application.query` | Titel- und Metadatenindex (ohne Rechteprüfung); **nicht** als Rechtsquellenindex umgedeutet |
| `AiRelease.mayProcess(sourceId, pageId)` | `application.release` | KI-Freigabe je Dokument |
| `Jobs.submitInteractive`, `JobType` | `application.jobs` | Auftrag der Analyse |
| `StatementFeatures` | `nlp-parser` | Modalität und Verneinung; der Core hängt heute **nicht** von `nlp-parser` ab (siehe O4) |

`StatementChat` wird **nicht** genutzt: keine eigene Modellzeile, keine Wiederholung, abgeschnittene Antworten
unbemerkt. Kein Umbenennen, kein neuer Chat-Port; wird später vereinheitlicht, geht `StatementChat` in `StepModel` auf.

## Entscheidung

### 1. Ein Ergebnismodell, drei Bereiche
- **Dokumentfakten** = `application.extraction` aus #1279: `DocumentAnalysis` (mit `notes`, `processors` als
  `Processor(id, version)`), `AnalysisSection` je Sachverhalt mit stabiler `matterId`, `ExtractedField` (`origin`
  REGEX/AI/DERIVED, `status`, `verified`, `group`, Qualifier NONE/REQUESTED/GRANTED für Antrag/Genehmigung),
  `SourceReference`. Eine neuere Version eines Prozessors ersetzt dessen Felder (Muster ADR 0032).
  `DERIVED` heißt nur Normalisierung oder Rechnen auf Dokumentfakten, nie Regelanwendung.
- **Eine Konfliktregel** (`FieldMerge`, aus `InMemoryDocumentAnalyses.merge`): Identität = (matterId, Schlüssel,
  Gruppe, Qualifier); abweichende Werte derselben Identität werden beide CONFLICT. `ExtractionValidator` nutzt
  denselben Helfer.
- **Rechtswissen** = Port `LegalKnowledgeRepository` (Abschnitt 5).
- **Prüfergebnisse** = `DeadlineAssessment` in `application.deadlines` (Abschnitt 6), nie in `ExtractedField`.
- Abhängigkeiten (ArchUnit): `application.extraction` importiert nie `application.deadlines` oder Rechtswissen.
  Entscheidungen fallen nur im Core (`application.deadlines`); angezeigt wird in der Desktop-App, Command Deck ›
  E-Akte (`app.ui.deck`); dort keine Fristlogik.

### 2. Eingang
- E-Akte: Die App (Modul `command-deck`, WebDAV) liest die Bytes und gibt sie über den Core-Port
  `DocumentContent: DocumentRef → (Bytes, Medientyp)` weiter; der Core ruft `DocumentText.extract`. Kein WebDAV-
  oder E-Akte-Typ im Core. `DocumentRef` = (origin "eakte", href, ETag).
- Bibliothek: `DocumentKey`, übersetzt durch genau einen Adapter nach `DocumentRef`; kein zweites Schlüsselschema.
- KI-Freigabe: Die E-Akte ist Quellen-ID `eakte`; `AiRelease.mayProcess("eakte", href)` wird unmittelbar vor jedem
  Senden im Auftrag geprüft. Ohne Freigabe nur Regex-Stufe mit Hinweis `AI_NOT_RELEASED` (getrennt von
  `AI_UNAVAILABLE`).

### 3. Extraktion in drei Stufen (`application.deadlines`, je Stufe eine `ExtractionStage`)
1. **Regex** (`IdentifierExtractor`): Fachmuster mit Rohschreibweise und normalisiertem Wert (Bewirtschafternummer,
   Objektkonto, Buchungsstelle, Geschäftszeichen, deutsche Daten, Abschnitt/Unterabschnitt, Vorschuss-/
   Verwahrungsbuch). Fristsätze nicht per bloßem Schlüsselwort, sondern mit Modalität und Verneinung
   aus dem Modul `nlp-lexicon` (O4).
   Versionskonvention wie `EnrichmentProcessor`. Sachverhalte werden an Abschnitts- und Kontogrenzen getrennt; ein
   Wert wird nie über Sachverhalte hinweg zugeordnet. Gemeinsame Normalisierer der Kennzeichen, damit Regex und KI
   keine falschen CONFLICTs erzeugen.
2. **KI** (`AiFactExtractor` über `ChatStepModel("deadline-facts", …)`): eigene Modellzeile „Fristen“
   (`ai.deadline-model`, `-max-tokens`, `-thinking`; nur vorhandene Nachdenk-Werte) in beiden Loadern,
   `ConfigurationParityTest`, `AiSettingsPanel` und `CONFIGURATION.md`. Strenges JSON über `StrictJsonAnswer`;
   jeder Wert mit wörtlichem Zitat. Schwerpunkt: Fristaussagen, Bedingungen, Ausnahmen, Antrag vs. Genehmigung,
   durchlaufende Gelder, Fristbezug (einzelne Zahlung / Konto), abweichende Buchungsreihenfolge, Verweise.
3. **Validierung** (`ExtractionValidator`): Zitat über `TextGrounding` im Abschnitt des Sachverhalts (größeres
   Fenster), sonst verworfen oder `verified=false`; Zusammenführung über `FieldMerge`.

**`TextGrounding`** (neutrales Paket `application.text`, dorthin ziehen auch `TextSpan` und `GroundedText`; kein
drittes `TextSpan`). Aus `StatementValidator` herausgelöst ohne Verhaltensänderung (ground, squash, Wortfenster,
Ablehnungsgründe); `StatementValidator` behält seine Prüfungen und Fehlertexte.

```java
public final class TextGrounding {
    public sealed interface Result permits Grounded, Rejected {}
    public record Grounded(GroundedText text) implements Result {}
    public record Rejected(Reason reason) implements Result {}
    public enum Reason { MISSING, NOT_FOUND, AMBIGUOUS }

    /** hint: vermuteter Bereich oder null; window: Suchbereich im Text. */
    public static Result ground(String text, TextSpan hint, String quote, TextSpan window);
}
```

### 4. Auftrag
- Neuer `JobType.DOCUMENT_ANALYSIS`. Klick auf „Analysieren“ → `submitInteractive`; Automatik → BACKGROUND.
- Teile je Sachverhalt, parallel bis `ai.parallel` (je Teil gelesen), jeder Teil mit Wiederholung
  (`StepModel.retries()`, mindestens 2, wie bei `ChatUpdateSummaryModel`; gilt für **alle** Nutzer von `StepModel`,
  auch die Schritte aus ADR 0032), gestreamt. Jeder Fehler mit Grundcode in der
  Diagnose; das Ergebnis ist dann teilweise mit Hinweis.
- Zerlegung an Sachverhaltsgrenzen, mit Tabellen, nach dem Zeichenbudget der Modellzeile. Nie still auslassen:
  `TEXT_TRUNCATED` bzw. `CHUNKS_SKIPPED` mit Anzahl. `ParagraphText.split` ist ungeeignet (verliert Tabellen und
  alles nach dem 40. Absatz).
- Ergebnis per Beobachter (ADR 0027), nicht synchron.

### 5. Rechtswissen
- `LegalKnowledgeRepository` ist ein dünner Core-Port; Treffer mit Quelle, Fundstelle, Ausschnitt, Gültigkeit, Art
  (Norm, Erlass, Einzelgenehmigung), Herkunft. Zustand „nicht eingerichtet/leer“ abfragbar.
- Standardadapter über `KeywordSearch`/`RelevanceSearch`, begrenzt auf Quellen, die der Benutzer als Rechtsquellen
  markiert. Rechtsquellen sind normale Wissensquellen (z. B. files-Ordner mit öffentlichen Gesetzestexten);
  Gültigkeit und Art stehen in den Metadaten. Frühere Erlasse gehören dazu, unterschieden über Metadaten (O3 = ja).
- In-Memory nur als Test-Fake.

### 6. Prüfergebnisse
`DeadlineAssessment` ist versiegelt; nur `DeadlineRuleEvaluator` (deterministisch, seiteneffektfrei) erzeugt es.
- `StandardConfirmed` nur mit `ExceptionCheckClosure(closedBy, closedAt, catalogVersion, knowledgeGeneration,
  DocumentRef samt ETag, matterId)`. **Abgeschlossen wird nur durch ausdrückliche Bestätigung des Benutzers** (O2).
- `DeviationConfirmed` nur mit Beleg der Ausnahme: Sachverhaltsbezug, Wirksamkeit, Rechtswirkung, je nachgewiesen.
- `StandardProvisional` mit Gründen: `KNOWLEDGE_EMPTY`, `UNCHECKED_EXCEPTIONS`, `NO_ORIGIN_DATE`.
- Ohne Entstehungsdatum keine konkrete Frist, sondern die abstrakte Regel. Das Erlassdatum ist nie das
  Entstehungsdatum. Ein Antrag (REQUESTED) zählt nie als Genehmigung.
- Ablage im Core-Speicher je (DocumentRef samt ETag, matterId); veraltet bei Änderung von ETag, Katalogversion,
  Wissensgeneration oder Prozessorversion. Nie stillschweigend „bestätigt“ weiterführen.

### 7. Regelkatalog
`DeadlineRuleCatalog`: Regeln mit Rechtsgrundlage, belegtem Wortlaut, Quelle (URL, Abrufdatum), Gültigkeit,
Voraussetzungen, Ausschlussbedingungen, Art (STANDARD oder AUSNAHME mit verdrängter Regel), Version. Im Repo nur
Schema, Evaluator, öffentliche Normen und erfundene Testregeln. Nicht öffentliche Regeln und Erlasse sind
Benutzerdaten (Wissensquelle oder Core-Speicher), nie im Repo oder in der YAML.

Belegt: § 60 Abs. 1 Satz 2 BHO (https://www.gesetze-im-internet.de/bho/__60.html, abgerufen 23.11.2026): „Ein
Vorschuß ist bis zum Ende des zweiten auf seine Entstehung folgenden Haushaltsjahres endgültig zu buchen; Ausnahmen
bedürfen der Einwilligung des Bundesministeriums der Finanzen.“ Belegt ebenso Nr. 6 und Nr. 8 VO/VW-RiB (Stand
04/21, GMBl 2021 Nr. 57, S. 1246; https://www.verwaltungsvorschriften-im-internet.de/bsvwvbund_01092021_IIE4H10052010006003.htm
und https://zrb.bund.de/vorschriften/verwaltungsvorschriften-zur-bho; von Angelo am 23.11.2026 geschickt, Wortlaut
geprüft). Umgesetzt in `PublicDeadlineRules`: Grundsatz Nr. 6 bzw. Nr. 8 Satz 1; Ausnahmen Sockelbeträge,
durchlaufende Gelder, Sicherheiten, beschlagnahmte Gelder, unanbringliche Auszahlungen, Einwilligung/Zulassung des
BMF (nur mit nachgewiesener Genehmigung). Für § 60 BHO ist kein Datum der Fassung belegt; die Wirksamkeit bleibt dort
offen.

### 8. Abgrenzung
Im Core erlaubt (README, ADR 0012 §4, ADR 0032): Faktenextraktion, der deterministische `DeadlineRuleEvaluator`,
Suche über Wissensquellen mit den vorhandenen Anwendungsfällen. Eine Recherche-Steuerung (welche Quellen, welche
Anfragen) wird kein Orchestrator im Core.

### 9. Tests
Alles synthetisch: ein nachgebildeter Referenz-Erlass mit erfundenen Nummern; kein echter Inhalt im Repo. Abgedeckt:
Kennzeichen, Buchungsstelle vs. Objektkonto, durchlaufende Gelder, getrennte Fristaussagen, Bezug einzelne Zahlung,
abweichende Buchungsreihenfolge, kein Entstehungsdatum aus dem Erlassdatum, mehrere Sachverhalte, keine erfundenen
Fundstellen, leere Wissensbasis, ohne KI, ohne Freigabe, Versionen, Unvollständigkeit; dazu mehrdeutige Kennzeichen,
widersprüchliche Fristen, Verlängerungsantrag, genehmigte Ausnahme. KI-Stufe zusätzlich echt gegen Ollama über
Pyloros mit dem erfundenen Erlass.

## Pakete (kleine PRs auf develop)

| Paket | Inhalt | startet |
| --- | --- | --- |
| P0 Vertrag | nur Typen: `DeadlineFieldKeys` (Schlüssel/Gruppen aus 3), `ExtractionStage`, `LegalKnowledgeRepository` + Treffer + Zustand, `DeadlineAssessment` versiegelt mit Gründen, `TextGrounding`-Signatur, Normalisierer | nach #1279 |
| P1 | `TextGrounding` + `TextSpan`/`GroundedText` nach `application.text`, `StrictJsonAnswer` | sofort |
| P1b | `StepModel.retries()` für alle Nutzer, Test: gescheiterter Schritt wird wiederholt, Grund in der Diagnose | sofort |
| P1c | Modul `nlp-lexicon` (ohne Abhängigkeiten) mit Wortlisten Modalität/Verneinung; `StatementFeatures` delegiert, Verhalten gleich | sofort |
| P2 | Regex-Stufe + Sachverhaltstrennung | nach P0 |
| P3 | KI-Stufe + Modellzeile „Fristen“ + Validierung | nach P0 |
| P4 | Rechtswissen-Adapter über `KeywordSearch`/`RelevanceSearch` | nach P0 |
| P5 | Regelkatalog (§ 60 BHO) + Evaluator | nach P0 |
| P6 Anschluss | `JobType`, Freigabe je Dokument, `DocumentContent`, Provider-Registrierung, Beobachter | nach P0 |
| UI | Extraktionsansicht (Strang Extraktionsansicht, nur App), gegen Beispieldaten | nach #1279 |

## Offene Punkte
- O1: erledigt — Wortlaut Nr. 6/8 VO/VW-RiB belegt (23.11.2026).
- O2: entschieden — Abschluss nur durch ausdrückliche Bestätigung des Benutzers.
- O3: entschieden — frühere Erlasse in denselben Wissensquellen, über Metadaten.
- O4: entschieden — neues, abhängigkeitsfreies Modul `nlp-lexicon` mit den Wortlisten für Modalität und Verneinung;
  Core und `nlp-parser` hängen davon ab, nicht voneinander (P1c).
