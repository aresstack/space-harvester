# Live-Vergleich der Confluence-Fassade (#31)

`check.py` stellt dieselben lesenden Anfragen an eine echte Confluence-Data-Center-Instanz und an die Fassade des
Harvesters und listet, worin sich Status, Felder und Listenhüllen unterscheiden. Anleitung und Einordnung der
Ergebnisse: [docs/CONFLUENCE_COMPAT.md](../../docs/CONFLUENCE_COMPAT.md#live-vergleich-gegen-die-eigene-instanz).

```bash
export CONFLUENCE_BASE=https://confluence.example.org/confluence
export CONFLUENCE_PAT=…                        # oder CONFLUENCE_USER / CONFLUENCE_PASSWORD
export HARVESTER_BASE=http://localhost:8080/api/compat/confluence/confluence
export HARVESTER_TOKEN=…
python3 tools/confluence-compat-check/check.py --page 123456 --page 234567 --space DEV --parent 123456 \
    --restricted-page 345678 > vergleich.md
```

Nur Python 3, keine weiteren Pakete. Exit-Code 0 ohne unerwartete Unterschiede, sonst 1. Der Bericht nennt Feldpfade,
Statuscodes, Anzahlen und Content-IDs, aber keine Feldwerte, Titel, Texte oder Zugangsdaten.
