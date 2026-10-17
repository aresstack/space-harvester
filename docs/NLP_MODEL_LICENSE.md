# Lizenzprüfung des deutschen MaltParser-Modells (#99)

Freigabebedingung für die öffentliche Weitergabe von `maltparser-de-gsd` über GitHub Releases. Solange diese Seite
**Status: ausstehend** zeigt und das Rezept `license.approval=pending` enthält, bleibt jedes Modell ein Entwurfs-Release
oder Workflow-Artefakt und wird nicht veröffentlicht.

**Status: ausstehend.** Freigabe durch: – (Angelo). Datum: –.

## Was weitergegeben wird

Das Modell-Asset `maltparser-de-gsd-vX.Y.Z.zip` enthält:

| Datei | Inhalt | Herkunft |
|---|---|---|
| `maltparser-de-gsd.mco` | Gelerntes Parsermodell: LIBLINEAR-Gewichte, Featuremodell, Symboltabellen | aus UD German GSD gelernt |
| `model-manifest.json`, `evaluation.json` | Beschreibung, Prüfsummen, Messwerte | erzeugt |
| `LICENSE-MODEL.md`, `ATTRIBUTION.md` | Lizenz und Namensnennung | `nlp-model-training/model/` |
| `LICENSE-UD_German-GSD.txt` | Lizenzhinweis der Treebank, unverändert | UD German GSD |

Die Treebank selbst (`*.conllu`) wird weder eingecheckt noch mit dem Modell ausgeliefert. Die Symboltabellen in der
`.mco` enthalten aber die Wortformen (FORM) und Labels aus dem Train-Teil, also einzelne Wörter der zugrunde liegenden
Texte, keine zusammenhängenden Sätze.

## Rechtslage der Quellen

| Bestandteil | Lizenz | Quelle |
|---|---|---|
| UD German GSD, Annotationen (UPOS, Dependenzen) | CC BY-SA 4.0 | `LICENSE.txt` und README der Treebank, r2.18 |
| UD German GSD, zugrunde liegende Sätze | keine Lizenz; Google beansprucht kein Eigentum und kein Urheberrecht, Sätze „as is“, in manchen Rechtsordnungen ggf. geschützt, gesammelt unter Schranken oder stillschweigender Erlaubnis | README der Treebank, Abschnitt „Licenses and terms-of-use“ |
| Teile aus TIGER (CoNLL 2006) | Erlaubnis von Hans Uszkoreit für die Nutzung der Sätze in diesem Release | README der Treebank |
| MaltParser 1.9.2 | BSD-3-Clause | POM `org.maltparser:maltparser:1.9.2` |
| LIBLINEAR 1.8 (`de.bwaldvogel:liblinear`), LIBSVM 3.1 | BSD-3-Clause | POMs |
| reload4j 1.2.26 (ersetzt log4j 1.2.16) | Apache-2.0 | POM |
| Apache OpenNLP 2.5.12, `opennlp-models-pos-de` 1.3.0 | Apache-2.0 | POMs; nur für die Evaluation, nicht im Modell |
| Trainings- und Ladecode in diesem Repository | MIT | `LICENSE` |

## Prüfliste

- [x] Treebank über Commit und SHA-256 gepinnt, nicht im Repository, nicht im Asset.
- [x] Lizenztext der Treebank und Namensnennung liegen dem Asset bei (CC BY-SA 4.0, Abschnitt 3(a)).
- [x] Modell wird nicht als MIT ausgewiesen; Code-Lizenz und Modelllizenz sind getrennt (`LICENSE-MODEL.md`).
- [x] Vorgesehene Modelllizenz: CC BY-SA 4.0 (Share-Alike, da aus CC-BY-SA-Annotationen abgeleitet; die vorsichtige
      Annahme, auch wenn gelernte Gewichte möglicherweise kein Bearbeitungswerk sind).
- [x] Laufzeitabhängigkeiten des Parsers mit freien Lizenzen (BSD-3-Clause, Apache-2.0), keine GPL.
- [ ] Entscheidung, ob die Weitergabe der in den Symboltabellen enthaltenen Wortformen aus den nicht lizenzierten
      Sätzen zulässig ist (siehe offene Punkte).
- [ ] Entscheidung, ob CC BY-SA 4.0 als Modelllizenz für die Verbraucher (Space Harvester und andere Java-Projekte)
      tragbar ist: Share-Alike gilt für das Modell und Bearbeitungen davon, nicht für die Anwendung, die es lädt.
- [ ] Freigabe hier eintragen (Status, Name, Datum) und im Rezept `license.approval=approved` setzen.

## Offene Punkte

1. **Rechte an den zugrunde liegenden Texten.** Die Treebank lizenziert nur die Annotationen. Ein Parsermodell gibt
   die Texte nicht wieder, enthält aber Wortformen als Merkmale. Andere Projekte geben aus UD-Treebanks gelernte
   Modelle weiter (z. B. die UDPipe-Modelle unter CC BY-NC-SA 4.0); eine Rechtsberatung ersetzt das nicht.
2. **Wortformen reduzieren (Option).** Wenn die Weitergabe der Wortformen nicht gewünscht ist, ließe sich ein Modell
   nur aus UPOS trainieren (Rezept: Featuremodell ohne FORM, MAJOR-Version). Es wäre messbar schlechter; den Abstand
   zeigt ein Vergleichslauf auf Dev.
3. **Share-Alike für Verbraucher.** Wer das Modell verändert (z. B. weitertrainiert) und weitergibt, muss es wieder
   unter CC BY-SA 4.0 stellen. Bündelt ein Deployment das Modell, gehören `LICENSE-MODEL.md` und `ATTRIBUTION.md`
   mit dazu.

## Ablauf der Freigabe

1. Offene Punkte entscheiden, diese Seite auf **Status: freigegeben** setzen und Name und Datum eintragen.
2. Im Rezept `nlp-model-training/model/maltparser-de-gsd.properties` `license.approval=approved` setzen (per PR).
3. Workflow „MaltParser-Modell trainieren“ mit `publish: draft` starten; der Entwurf trägt dann keinen Warnhinweis.
4. Entwurf unter Releases prüfen (Assets, Notizen, Messwerte) und von Hand veröffentlichen. Erst danach ist das Modell
   ohne Token unter `https://github.com/aresstack/space-harvester/releases/download/<tag>/<asset>` abrufbar.
