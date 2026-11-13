# Experiment: Deutsche Fachdomäne Bibliotheksausleihe (13.11.2026)

## Ziel und Herkunft

Die Substantiv-Verb-Analyse an einem zusammenhängenden deutschsprachigen Realtextkorpus testen. Die Originaltexte stammen ausschließlich von offiziellen, öffentlichen Seiten der Staatsbibliothek zu Berlin.

## Quellen

| ID | Seite | URL | Lesetext | Analysiert |
|---|---|---|---:|---:|
| anmeldung | Anmeldung und Bibliotheksausweis | https://staatsbibliothek-berlin.de/service/anmeldung | 5.678 | 5.462 |
| ausleihe | Bestellung, Ausleihe und Rückgabe | https://staatsbibliothek-berlin.de/service/hinweise-zu-ausleihe-und-benutzung | 4.148 | 4.148 |
| leihfrist | Leihfrist, Verlängerung und Mahngebühr | https://staatsbibliothek-berlin.de/service/hinweise-zu-ausleihe-und-benutzung/leihfrist-verlaengerung-mahngebuehr/ | 2.654 | 2.654 |
| faq | Häufige Fragen zur Benutzung | https://staatsbibliothek-berlin.de/service/faq | 20.218 | 5.345 |

Der HTML-Hauptinhalt wird mit dem bereits vorhandenen jsoup extrahiert. Für die Auswertung werden die bestehenden OpenNlpGermanPreprocessor, DependencyAnalysis, MaltModelLoader und ObjectCentricExtraction genutzt. PageText berechnet eine dokumentierte Textprüfsumme. **Dies ist kein Confluence-Harvest und keine vollständige Source-Synchronisierung.** Das Tool ist ein isolierter CLI-Test.

## Ausführen

In PowerShell aus dem Projektstamm:

~~~powershell
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :nlp-parser:downloadGermanParserModel
.\gradlew.bat :space-harvester-app:germanLibraryTextSpike
~~~

Der JavaExec-Task verwendet die Java-21-Toolchain. Die Desktop-Konfiguration wird nicht verändert. Die Ergebnisse liegen ausschließlich unter space-harvester-app/build/german-library-text-spike.

Ausgaben:
- anmeldung.txt, ausleihe.txt, leihfrist.txt und faq.txt: vollständige extrahierte Lesetexte
- sources.tsv: URLs, Längen, Input-SHA256
- terms.tsv: Nomen/Verben mit Häufigkeiten und Dokumenthäufigkeiten
- relations.tsv: extrahierte Aussagen und jeweiliger Originalsatz
- analysis.md: zusammenfassender Bericht

Die NLP-Verarbeitung wurde je Seite auf maximal 5.500 Zeichen beschränkt. Die vollen extrahierten Texte bleiben lokal gespeichert und werden nicht ins Git aufgenommen.

## Messwerte

| Seite | Sätze | Nominale Erwähnungen | Aussagen |
|---|---:|---:|---:|
| anmeldung | 74 | 263 | 56 |
| ausleihe | 46 | 206 | 37 |
| leihfrist | 29 | 135 | 32 |
| faq | 64 | 277 | 66 |
| Gesamt | 213 | 881 | 191 |

Häufige Nomen: Bibliotheksausweis (33, vier Seiten), Medium (17, vier Seiten), Verlängerung (14, vier Seiten), Bibliothekskonto (13, vier Seiten), Anmeldung (10, vier Seiten), Leihfrist (7, zwei Seiten), Bestellung (5, drei Seiten).

Häufige Verben: erhalten (14), verlängern (12), bestellen (7), benötigen (6), anmelden (4), ausfüllen (3), ausleihen (2).

Plausibles Ergebnis: Aus „Füllen Sie zunächst das Anmeldeformular aus“ wurde „Sie → ausfüllen → das Anmeldeformular“.

Fehlerhaftes Ergebnis: Aus „Danach stellen wir Ihnen ein Bibliothekskonto aus“ entstand „wir → ausstellen → Ihnen“. Der Dativempfänger wurde irrtümlich als Objekt interpretiert; das betroffene Objekt ist „ein Bibliothekskonto“.

## Qualitative Befunde

1. Die deutsche Nomen- und Verbextraktion ergibt inhaltlich brauchbare Fachbegriffskandidaten. Häufigkeit ist aber kein Beweis für ein Geschäftsobjekt.
2. Die Lemmatisierung reduziert Varianten, zeigt jedoch Schwächen bei Lesesaal/Lesesälen und anderen Flexionen.
3. Nominalisierte Tätigkeiten sind gut sichtbar: Anmeldung, Bestellung, Verlängerung, Vormerkung. Pauschale Endungsregeln liefern auch falsche Treffer wie Zeitung, Institution, Bankverbindung und Hausordnung.
4. Beziehungen müssen semantisch geprüft werden. Das Beispiel mit dem Dativempfänger zeigt die Grenze der aktuellen Rollenextraktion.
5. Die semantische Bereinigung ist noch offen: Haus, Tag, Ort, Service und Bibliothekskonto stehen nicht für dieselbe fachliche Objektklasse.
6. Die vollständigen Texte, URLs und SHA-256-Hashes erlauben einen reproduzierbaren Vergleich späterer Varianten. Beim erneuten Webabruf können die Texte bereits verändert sein.

## Nächster Qualitätsschritt

20–30 ausgewählte Sätze manuell annotieren: Hauptwörter, Verbprädikate, Nominalisierungen, Akteure, betroffene Objekte, Empfänger, Bedingungen und Fachbegriffe. Danach Präzision/Recall je Stufe messen und Sonderfälle mit Dativ/Akkusativ sowie trennbaren Verben gezielt vergleichen. Synonyme nur als unbestätigte Vorschläge behandeln.
