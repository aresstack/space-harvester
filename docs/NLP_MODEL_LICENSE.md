# Lizenzprüfung des deutschen MaltParser-Modells (#99, #111)

Freigabebedingung für die öffentliche Weitergabe von `maltparser-de-gsd` über GitHub Releases. Solange diese Seite
**Status: ausstehend** zeigt und das Rezept `license.approval=pending` enthält, bleibt jedes Modell ein Entwurfs-Release
oder Workflow-Artefakt und wird nicht veröffentlicht.

**Status: ausstehend.** Die Entscheidung trägt der Abschnitt [Freigabeentscheidung](#freigabeentscheidung) ein.

Diese Seite ist eine technische und rechtliche Einschätzung des Projekts, kein anwaltliches Gutachten. Sie betrachtet
deutsches und EU-Recht; für andere Rechtsordnungen trifft sie keine Aussage.

## Was weitergegeben wird

Das Modell-Asset `maltparser-de-gsd-vX.Y.Z.zip` enthält:

| Datei | Inhalt | Herkunft |
|---|---|---|
| `maltparser-de-gsd.mco` | Gelerntes Parsermodell: LIBLINEAR-Gewichte, Featuremodell, Symboltabellen | aus UD German GSD gelernt |
| `model-manifest.json`, `evaluation.json` | Beschreibung, Prüfsummen, Messwerte | erzeugt |
| `LICENSE-MODEL.md`, `ATTRIBUTION.md` | Lizenz und Namensnennung | `nlp-model-training/model/` |
| `LICENSE-UD_German-GSD.txt` | Lizenzhinweis der Treebank, unverändert | UD German GSD |

Die Treebank selbst (`*.conllu`) wird weder eingecheckt noch mit dem Modell ausgeliefert. Die Symboltabellen in der
`.mco` enthalten aber Wortformen (FORM) aus dem Train-Teil, einzeln und als Paare; was das genau heißt, steht unter
[Prüfung des Modells v1.0.0](#prüfung-des-modells-v100).

## Rechtslage der Quellen

| Bestandteil | Lizenz | Quelle |
|---|---|---|
| UD German GSD, Annotationen (UPOS, Dependenzen) | CC BY-SA 4.0, seit v2.5 (2019-11-15) ohne NC | `LICENSE.txt` und README der Treebank, r2.18 |
| UD German GSD, zugrunde liegende Sätze | keine Lizenz; Google beansprucht kein Eigentum und kein Urheberrecht, Sätze „as is“, in manchen Rechtsordnungen ggf. geschützt, gesammelt unter Schranken oder stillschweigender Erlaubnis | README der Treebank, Abschnitt „Licenses and terms-of-use“ |
| Teile aus TIGER (CoNLL 2006) | Erlaubnis von Hans Uszkoreit für die Nutzung der Sätze in diesem Release | README der Treebank |
| MaltParser 1.9.2 | BSD-3-Clause | POM `org.maltparser:maltparser:1.9.2` |
| LIBLINEAR 1.8 (`de.bwaldvogel:liblinear`), LIBSVM 3.1 | BSD-3-Clause | POMs |
| reload4j 1.2.26 (ersetzt log4j 1.2.16) | Apache-2.0 | POM |
| Apache OpenNLP 2.5.12, `opennlp-models-pos-de` 1.3.0 | Apache-2.0 | POMs; nur für die Evaluation, nicht im Modell |
| Trainings- und Ladecode in diesem Repository | MIT | `LICENSE` |

## Prüfung des Modells v1.0.0

### Geprüftes Artefakt

| Angabe | Wert |
|---|---|
| Modell | `maltparser-de-gsd-v1.0.0`, MaltParser 1.9.2, stacklazy + LIBLINEAR |
| Herkunft | Trainingslauf [39025709576](https://github.com/aresstack/space-harvester/actions/runs/39025709576) vom 17.10.2026 auf main, Artefakt `maltparser-de-gsd-v1.0.0` |
| Geprüfte Kopie | Asset `maltparser-de-gsd-v1.0.0.zip` des Entwurfs-Release (aus diesem Artefakt ohne Neutraining verpackt) |
| SHA-256 der `.mco` | `237885c25519b5e8996602994a81959b8a51a8dc7c331b40954618359c5f6ea3` (gleich `mcoSha256` im Manifest) |
| SHA-256 des Featuremodells | `e764561cefedfd78ea06b3432eac8838d357ee70c6b40728d9426db5c4bf7819` |
| SHA-256 der geprüften ZIP | `3f05fb19a335f088a36bd47eaf9598f5467ab29b792b7e6a54d608e7db4a5ea0` (Lizenzstand pending; ändert sich beim Neuverpacken nach der Freigabe, die `.mco` nicht) |
| Treebank | UD German GSD r2.18, Commit `81d8c3612a88f5867fd089e9c0a5466ef230078b`, Train-Teil SHA-256 `6cb1b600…ac337bf` |

### Vorgehen

`nlp-model-training/tools/mco-audit.py` öffnet die ZIP und darin die `.mco` (selbst ein ZIP), listet die Dateien,
sucht nach Korpusdateien und zählt die Einträge jeder Symboltabelle. Mit `--treebank` vergleicht es die
Wortform-Paare zusätzlich mit dem Train-Teil. Es gibt nur Zählwerte aus, nie Wortformen oder Sätze, braucht kein Netz
und trainiert nichts:

```bash
python3 -I nlp-model-training/tools/mco-audit.py maltparser-de-gsd-v1.0.0.zip \
  --treebank nlp-model-training/build/treebank/de_gsd-ud-train.conllu
```

Die Treebank legt `./gradlew :nlp-model-training:prepareGermanTreebank` gepinnt nach `nlp-model-training/build/treebank/`.

### Feststellungen

Inhalt der `.mco` (unkomprimiert):

| Datei | Größe | Inhalt |
|---|---:|---|
| `odm0.liblinear.moo` | 75,1 MB | serialisiertes LIBLINEAR-Modell (Gewichte, Klassen) |
| `odm0.liblinear.map` | 15,3 MB | serialisierte Abbildung Merkmalscode → Merkmalsnummer, nur Zahlen |
| `symboltables.sym` | 15,5 MB | Symboltabellen: Zeichenkette → Code je Merkmal |
| `feature-model.xml`, `conllu.xml`, `savedoptions.sop`, `maltparser-de-gsd_singlemalt.info` | 12 kB | Featuremodell, Spaltenformat, Optionen; Pfadangaben leer |

- Keine Korpusdateien (`*.conllu`, `*.conll`, `*.txt` u. a.) im Archiv; die `.mco` ist 23,3 MB groß (ZIP-komprimiert).
- In `.moo` und `.map` steht keine der 40.877 Wortformen ab sechs Zeichen als Text; die Wortformen liegen nur in
  `symboltables.sym`.
- Die Symboltabellen enthalten keine Häufigkeiten, keine Satznummern und keine Reihenfolge; jeder Eintrag ist eine
  Zeichenkette mit einem fortlaufenden Code.
- LEMMA, XPOS und FEATS haben je einen Leer-Eintrag, wie es der Eingabevertrag (nur FORM und UPOS) verlangt.

Symboltabellen mit Wortformen:

| Tabelle | Einträge | Inhalt |
|---|---:|---|
| `FORM` | 49.498 | einzelne Wortformen, höchstens 35 Zeichen |
| `MERGE2_FORM_FORM` | 293.583 | Paar aus den Wortformen der beiden obersten Stack-Elemente (`Stack[0]`, `Stack[1]`); davon 282.265 Paare aus zwei Wortformen, 11.318 mit dem Platzhalter `#null#` für eine leere Stack-Position |
| `MERGE2_FORM_UPOSTAG` | 226.185 | Wortform mit einer Wortart |
| `MERGE2_UPOSTAG_FORM` | 98.816 | Wortart mit einer Wortform |
| `MERGE3_FORM_UPOSTAG_UPOSTAG` | 42.001 | Wortform eines linken Abhängigen mit zwei Wortarten |

Die übrigen Tabellen enthalten nur Wortarten, Labels, Abstände und Übergänge (zusammen unter 8.000 Einträge).

Laut `nlp-model-training/model/feature-model.xml` kombiniert genau ein Merkmal zwei Wortformen
(`Merge(FORM Stack[0], FORM Stack[1])`); kein Merkmal enthält mehr als zwei. Die Paare sind die beiden Wörter, zwischen
denen der Parser gerade eine Kante erwägt, also nicht zwingend Nachbarn im Satz. Der Abgleich mit dem Train-Teil zeigt
aber, wie viel der Wortfolge sie trotzdem abbilden:

| Messung | Wert |
|---|---:|
| FORM/FORM-Paare, die im Train-Teil als direkt benachbarte Wörter vorkommen | 139.406 von 282.265 (49 %) |
| Benachbarte Wortpaare des Train-Teils, die als Paar in der Tabelle stehen | 215.277 von 249.964 (86 %) |
| Train-Sätze, deren benachbarte Wortpaare alle in der Tabelle stehen | 1.582 von 13.813 (11 %) |
| Längste lückenlos abgedeckte Wortfolge je Satz | Median 10, 90 % bis 17, höchstens 41 Wörter |
| Partner je Wortform in der Paartabelle | Median 4, Mittel 11,4 |

Das korrigiert die frühere Annahme „einzelne Wörter, keine zusammenhängenden Sätze“: Das Modell speichert keine Sätze,
aber die meisten Nachbarschaften des Train-Textes als ungeordnete Wortpaare. Einen Satz daraus zurückzugewinnen hieße,
ohne Reihenfolge, Häufigkeit und Satzgrenzen eine Kette durch einen Graphen zu raten, in dem eine Wortform im Median vier
Partner hat; bei häufigen Wörtern sind es Tausende. Für kurze Folgen seltener Wörter ist das nicht ausgeschlossen.
Ebenso lässt sich für einen bekannten Satz prüfen, ob seine Wortpaare im Modell stehen.

### Rechtliche Einordnung

**Annotationen und Texte sind getrennte Gegenstände.** Die Treebank lizenziert nur die Annotationen (Wortarten,
Dependenzen) unter CC BY-SA 4.0; die NC-Beschränkung hat Google mit v2.5 (2019-11-15) aufgehoben, kommerzielle Nutzung
ist also im Rahmen von CC BY-SA erlaubt
([README von UD German GSD](https://github.com/UniversalDependencies/UD_German-GSD), Abschnitte „Licenses and
terms-of-use“ und „Changelog“). Für die Satztexte räumt niemand Rechte ein: Google erhebt keinen Anspruch, gibt sie
„as is“ weiter und weist auf mögliches Urheberrecht hin. Die Erlaubnis von Hans Uszkoreit gilt für die TIGER-Sätze „as
part of this release“, nicht als allgemeine Rechteeinräumung. Trainieren, Modell weitergeben und Texte weitergeben sind
deshalb drei verschiedene Fragen.

**Einzelne Wörter sind nicht geschützt, Wortfolgen können es sein.** EuGH, Urteil vom 16.07.2009, C-5/08, Infopaq,
Rn. 45–51 ([EUR-Lex](https://eur-lex.europa.eu/legal-content/DE/TXT/?uri=CELEX:62008CJ0005)): Wörter für sich
genommen sind keine geistige Schöpfung; Schutz entsteht erst durch Auswahl, Anordnung und Kombination, und schon ein
Auszug von elf Wörtern kann geschützt sein, wenn er die schöpferische Eigenart wiedergibt. Eine feste Wortzahl gibt es
nicht. Für das Modell folgt daraus: Die Tabelle `FORM` ist unproblematisch. Die Paartabelle besteht aus Zwei-Wort-
Einträgen, die einzeln kaum Schöpfungshöhe tragen; das Risiko liegt in ihrer Gesamtheit, soweit sich daraus
schöpferische Folgen wieder zusammensetzen lassen (siehe Feststellungen).

**Text und Data Mining erlaubt das Training, nicht die Weitergabe von Texten.**
[§ 44b UrhG](https://www.gesetze-im-internet.de/urhg/__44b.html) erlaubt Vervielfältigungen rechtmäßig zugänglicher
Werke für Text und Data Mining, auch kommerziell, wenn kein wirksamer (bei Online-Werken maschinenlesbarer)
Nutzungsvorbehalt besteht; die Kopien sind zu löschen, sobald sie nicht mehr gebraucht werden. Das Projekt lädt die
Treebank nur zur Laufzeit des Trainings und checkt sie nicht ein. § 44b deckt die Weitergabe geschützter Textteile nicht
ab; für das Modell kommt es darauf an, ob es solche enthält. Datenbankrechte (§§ 87a ff. UrhG) an der Treebank als
Sammlung sind eine eigene Frage: Die Annotationen sind unter CC BY-SA 4.0 lizenziert, das nach Abschnitt 4 auch
Datenbankrechte (Sui-generis-Rechte) einschließt; für die Textsammlung selbst ist das nicht geklärt.

**Modell unter CC BY-SA 4.0, Code unter MIT.** Das Modell wird vorsichtshalber wie eine Bearbeitung der Annotationen
behandelt und unter CC BY-SA 4.0 weitergegeben
([Legal Code](https://creativecommons.org/licenses/by-sa/4.0/legalcode.de),
[Kurzfassung](https://creativecommons.org/licenses/by-sa/4.0/deed.de)), auch wenn gelernte Gewichte womöglich gar keine
Bearbeitung sind. Pflichten bei der Weitergabe: Namensnennung, Lizenzverweis, Hinweis auf Änderungen, bei Bearbeitungen
dieselbe Lizenz (Share-Alike). `LICENSE-MODEL.md`, `ATTRIBUTION.md`, `LICENSE-UD_German-GSD.txt` und `model-manifest.json`
(Feld `license`) erfüllen das für das unveränderte Modell. Share-Alike erfasst das Modell und Bearbeitungen davon; eine
Anwendung, die das Modell als getrennte Datei lädt, wird dadurch nicht zur Bearbeitung und bleibt MIT. Für jede denkbare
Bündelung (z. B. Modell fest in ein Programmarchiv eingebaut) garantiert das diese Seite nicht. Die Lizenzdateien regeln
nur die Rechte an den Annotationen; Rechte Dritter an Satztexten räumen sie nicht ein.

### Bewertung und Restrisiken

**Empfehlung (vorläufig, bedingt):** Die Veröffentlichung von `maltparser-de-gsd-v1.0.0` als getrennt unter CC BY-SA 4.0
lizenziertes Modell ist nach geprüftem Stand vertretbar. Gründe: Die Annotationen sind kommerziell nutzbar lizenziert;
das Training ist durch die Lizenz und § 44b UrhG gedeckt; das Modell enthält keine Sätze, keine Häufigkeiten und keine
Reihenfolge, und kein Merkmal fasst mehr als zwei Wortformen; einzelne Wörter sind nach Infopaq nicht geschützt;
vergleichbare aus UD-Treebanks gelernte Modelle werden öffentlich weitergegeben (z. B. UDPipe, unter CC BY-NC-SA 4.0).

**Restrisiken:**

1. **Wortpaare in der Gesamtheit (mittel).** 86 % der benachbarten Wortpaare des Train-Textes stehen ungeordnet im
   Modell. Kurze Folgen, vor allem aus seltenen Wörtern, ließen sich mit Aufwand teilweise wieder zusammensetzen, und für
   einen bekannten Satz lässt sich die Zugehörigkeit prüfen. Ob das als Wiedergabe geschützter Textteile zählt, ist
   nicht entschieden.
2. **Rechte Dritter an den Texten (gering bis mittel).** Die Herkunft der Sätze ist nicht einzeln belegt; Google hat
   sie unter Schranken oder stillschweigender Erlaubnis gesammelt.
3. **Datenbankrecht an der Textsammlung (gering).** Das Modell entnimmt keine Datensätze, wohl aber eine Auswertung
   des gesamten Textes.
4. **Andere Rechtsordnungen.** Nicht geprüft; die Veröffentlichung über GitHub ist weltweit abrufbar.

**Möglichkeiten, das Risiko 1 zu senken** (jeweils neues Training und neue Version, nur auf ausdrückliche Entscheidung):

- *Ohne Wortform-Paare* (Merkmal `Merge(FORM Stack[0], FORM Stack[1])` streichen, v1.1.0): entfernt die Paartabelle;
  einzelne Wortformen und Wortform-Wortart-Paare bleiben. Der Qualitätsverlust ist nicht gemessen und dürfte deutlich
  kleiner sein als ohne Wortformen.
- *Ohne Wortformen* (nur UPOS, v2.0.0): rund 9 Punkte LAS weniger, Dativobjekte praktisch nicht mehr erkannt
  (`nlp-model-training/README.md`, Konfigurationswahl).
- *Seltene Wortformen auslassen* (z. B. nur Wortformen, die mehrfach im Train-Teil vorkommen): verlangt Änderungen am
  Trainingscode, nicht nur am Featuremodell.

Die Seite empfiehlt keine dieser Möglichkeiten zwingend; sie sind die Antwort, falls Risiko 1 nicht getragen werden soll.

## Prüfliste

- [x] Treebank über Commit und SHA-256 gepinnt, nicht im Repository, nicht im Asset.
- [x] Lizenztext der Treebank und Namensnennung liegen dem Asset bei (CC BY-SA 4.0, Abschnitt 3(a)).
- [x] Modell wird nicht als MIT ausgewiesen; Code-Lizenz und Modelllizenz sind getrennt (`LICENSE-MODEL.md`).
- [x] Vorgesehene Modelllizenz: CC BY-SA 4.0 (Share-Alike, da aus CC-BY-SA-Annotationen abgeleitet).
- [x] Laufzeitabhängigkeiten des Parsers mit freien Lizenzen (BSD-3-Clause, Apache-2.0), keine GPL.
- [x] Inhalt der `.mco` von v1.0.0 geprüft und reproduzierbar gezählt (Abschnitt „Prüfung des Modells v1.0.0“).
- [x] Rechtslage zu Wortformen, Text und Data Mining und CC BY-SA mit Quellen bewertet, Restrisiken benannt.
- [ ] Entscheidung, ob die Weitergabe der Wortformen und Wortform-Paare mit den genannten Restrisiken getragen wird.
- [ ] Entscheidung, ob CC BY-SA 4.0 als Modelllizenz für die Verbraucher (Space Harvester und andere Java-Projekte)
      tragbar ist.
- [ ] Freigabe unten eintragen und im Rezept `license.approval=approved` setzen.

## Freigabeentscheidung

Wird von der verantwortlichen Person ausgefüllt; bis dahin gilt **ausstehend**. Eine grüne CI, ein gemergter PR oder
dieses Dokument selbst sind keine Freigabe.

| Feld | Eintrag |
|---|---|
| Status | ausstehend |
| Entschieden von | – |
| Datum | – |
| Umfang | `maltparser-de-gsd-v1.0.0`, `.mco` SHA-256 `237885c25519b5e8996602994a81959b8a51a8dc7c331b40954618359c5f6ea3`, Lizenz CC BY-SA 4.0, öffentliche Weitergabe über GitHub Releases |
| Getragene Restrisiken | Restrisiken 1 bis 4 aus „Bewertung und Restrisiken“ |
| Veröffentlichte ZIP | SHA-256 aus `SHA256SUMS` des veröffentlichten Release (nach dem Neuverpacken nachtragen) |

Zum Freigeben: Status auf **freigegeben**, Name und Datum eintragen, oben auf der Seite **Status: freigegeben** setzen;
bei Ablehnung **abgelehnt** und die gewählte Möglichkeit aus „Bewertung und Restrisiken“ notieren.

## Ablauf der Freigabe

Das Modell wird dafür nicht neu trainiert; v1.0.0 stammt aus dem Trainingslauf
[39025709576](https://github.com/aresstack/space-harvester/actions/runs/39025709576) vom 17.10.2026 und ist als
Entwurfs-Release `maltparser-de-gsd-v1.0.0` gesichert (Lizenzstand dort noch `pending`).

1. Freigabeentscheidung oben eintragen.
2. Im Rezept `nlp-model-training/model/maltparser-de-gsd.properties` `license.approval=approved` setzen (per PR auf
   main, zusammen mit Schritt 1).
3. Workflow „MaltParser-Modell bereitstellen“ ohne `run_id` starten. Er übernimmt die Assets des Entwurfs, verpackt sie
   mit dem freigegebenen Lizenzstand neu (Modell, Messwerte und Prüfsumme der `.mco` bleiben, ZIP und Manifest
   bekommen neue SHA-256) und aktualisiert den Entwurf ohne Warnhinweis.
4. Entwurf unter Releases prüfen (Assets, Notizen, Messwerte, `mcoSha256` gleich dem Wert oben) und von Hand
   veröffentlichen. Erst danach ist das Modell ohne Token unter
   `https://github.com/aresstack/space-harvester/releases/download/<tag>/<asset>` abrufbar. Die SHA-256 aus
   `SHA256SUMS` des veröffentlichten Release ist der Wert, den Verbraucher (#98) pinnen; er gehört in die Tabelle oben.
