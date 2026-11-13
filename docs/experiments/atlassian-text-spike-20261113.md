# Atlassian-Textanalyse: erster reproduzierbarer Spike (13.11.2026)

## Zweck

Die im Lehrvideo beschriebene Substantiv-/Verb-Analyse auf echte Confluence-Hilfetexte anwenden.
Nur die öffentliche Atlassian-Hilfe wird verwendet; keine internen Confluence-Quellen, kein KI-Dienst,
keine Änderungen am produktiven Datenbestand.

## Ausführung

Vom Projektstamm unter Windows:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :nlp-parser:downloadGermanParserModel
.\gradlew.bat :space-harvester-app:atlassianTextSpike -PatlassianSpikeSpace=DOC
```

Gradle nutzt für das JavaExec-Ziel ausdrücklich ein Java-21-Toolchain.
Ohne `atlassianSpikeSpace` wählt die Probe bevorzugt öffentliche dokumentationsbezogene Spaces.
Die Probe lädt maximal drei geeignete Seiten. Der Quelladapter ist `ConfiguredSourceCatalog`
mit `HarvestSource`; der Lesetext entsteht über `ConfluenceStorageTextProjector`.
Der Abruf ist unabhängig von der Swing-Oberfläche und von der Benutzerkonfiguration.

Ergebnisse (nicht ins Repository einchecken):
`space-harvester-app/build/atlassian-text-spike/` enthält
`sources.tsv`, `page-<id>.txt`, `english-pos-baseline.txt` und
`german-parser-exploration.txt`.

## Tatsächlicher Lauf

- Verbindung: erfolgreich, 1.009 von der öffentlichen Quelle gemeldete Spaces.
- Dokumentations-Space: `DOC`.
- Seiten: `155` „Feature List“ (1.817 Zeichen), `156` „Setting Up Public Access“
  (1.396 Zeichen) und `325` „Custom Decorator Templates“ (3.423 Zeichen).
- Bei allen drei Seiten wurden echte REST-Snapshots geladen und der Storage-Inhalt
  auf den sichtbaren Lesetext projiziert.
- Das angepinnte deutsche MaltParser-Modell `maltparser-de-gsd-v1.0.0` wurde
  erfolgreich geladen und auf begrenzte Textanfänge angewandt.
- Für eine sinnvollere Wortartenanalyse wurden zusätzlich englische OpenNLP-Modelle
  (UD English EWT 1.3.0: Satz, Token, POS, Lemma) eingesetzt.

| Seite | Englische Sätze | Nomen-Tokens | Verb-Tokens | Auffällige normalisierte Nomen |
|---|---:|---:|---:|---|
| 155 | 30 | 109 | 31 | page (16), space (7), comment (4) |
| 156 | 21 | 67 | 43 | site (11), access (8), space (7), user (6) |
| 325 | 39 | 164 | 75 | decorator (19), confluence (13), page (11), template (11) |

Über mehrere Seiten wiederkehrende Begriffe: `page` (27 Erwähnungen auf zwei Seiten),
`site` (20 auf drei Seiten), `space` (15 auf drei Seiten), `user` (9 auf zwei Seiten).
Die Lemmatisierung führt z. B. `pages/page` und `decorators/decorator` zusammen.

## Auswertung und Grenzen

1. **Abruf und Lesetext funktionieren.** In der Textprojektion fehlen absichtlich
   Makroinhalte, Linkziele und Ressourcenreferenzen. Für ein Fachmodell ist das
   teilweise hilfreich; bei Handlungsanleitungen kann aber Kontext verloren gehen.
2. **Der deutsche Parser ist für englische Hilfetexte nicht brauchbar.** Er interpretiert
   beispielsweise `that` als Verb oder `to` als Nomen. Die syntaktisch erzeugten
   Tripel sind keine verwendbaren fachlichen Beziehungen.
3. **Englische POS-Analyse verbessert Nomen und Verben deutlich, aber nicht perfekt.**
   Beispielsweise wird `can` teilweise als Nomen gewertet; das Verb `logged`
   wird teils fehlerhaft zu `logge` lemmatisiert. Ohne Goldstandard sind keine
   Präzisions-/Recall-Zahlen seriös.
4. **Wiederholung ist ein Signal, kein Geschäftsobjektbeweis.** `site`, `space`
   und `page` erscheinen über mehrere Texte, können aber Systemeigenschaften,
   UI-Begriffe oder fachliche Gegenstände bezeichnen.
5. **Nominalisierte Verben sind lediglich Kandidaten.** Endungen wie `-tion`,
   `-ment` und `-ing` finden etwa `editing`, `management`,
   `customization`, aber auch `section` und `option`. Nur sprachlicher
   Kontext und fachliche Prüfung können Tätigkeiten, Objekte und Attribute trennen.
6. **Keine automatischen Use Cases und keine bestätigten Entitäten.** Englisch
   trainiertes Dependency Parsing wäre erforderlich, um belastbare
   Subjekt–Prädikat–Objekt-Kandidaten zu erzeugen. Synonym- und Fachmodellprüfung
   kommen erst danach.

## Nächste Validierung

Mit zwei bis fünf Textabschnitten ein manuell annotiertes Korpus erstellen:
Nomen, Verben, Nominalisierungen, Fachobjekte, Attribute, Rollen, Aktivitäten
und nachweisbare Beziehungen getrennt markieren. Dann Precision/Recall pro
Analyse-Stufe messen und gezielt Parser-/Regelverbesserungen vergleichen.
