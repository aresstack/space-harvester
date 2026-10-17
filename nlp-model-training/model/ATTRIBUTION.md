# Attribution: {{releaseTag}}

Dieses Parsermodell wurde mit MaltParser aus der Treebank **UD German GSD** ({{treebankRelease}}, Commit
`{{treebankCommit}}`) trainiert.

- Treebank: UD German GSD, https://github.com/UniversalDependencies/UD_German-GSD
  - Annotationen (Wortarten und Dependenzen): Google, CC BY-SA 4.0; spätere Korrekturen der UD-Gemeinschaft.
  - Teile der Sätze stammen aus der CoNLL-2006-Fassung der TIGER-Treebank; Hans Uszkoreit hat die Nutzung der
    zugrunde liegenden Sätze in diesem Release erlaubt.
  - Laut README der Treebank beansprucht Google an den zugrunde liegenden Sätzen kein Eigentum und kein
    Urheberrecht; sie können in manchen Rechtsordnungen geschützt sein.
  - Zitierhinweis der Treebank: McDonald et al. (2013), „Universal Dependency Annotation for Multilingual Parsing“,
    ACL 2013.
- Parser: MaltParser 1.9.2, Johan Hall, Jens Nilsson, Joakim Nivre, BSD-3-Clause, http://www.maltparser.org/
- Training, Evaluation und Verpackung: aresstack/space-harvester, Modul `nlp-model-training` (MIT).

Das Modell enthält keine Kopie der Treebank-Sätze als Text, aber aus ihnen gelernte Gewichte und Symboltabellen mit
den Wortformen der Trainingsdaten, einzeln und als ungeordnete Paare von je zwei Wortformen (ohne Reihenfolge,
Häufigkeit oder Satzzuordnung). Einzelheiten und Bewertung: `docs/NLP_MODEL_LICENSE.md` in aresstack/space-harvester.
