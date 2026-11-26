# ADR 0033: Wissen des Chats per Verweis und Werkzeug

- Status: **Angenommen** (Angelo, 21.11.2026, im Strang UX; Zuschnitt vom Architekten abgenommen).
- Bezüge: [ADR 0012](0012-core-als-gemeinsame-bibliothek.md) (Core als Bibliothek, Werkzeuge als Adapter),
  [ADR 0017](0017-progressive-suche.md) (progressive Suche), [ADR 0028](0028-ki-freigabe-je-seite.md) (KI-Freigabe),
  [ADR 0029](0029-auswahl-und-ausgabe-aus-dem-bestand.md) (Auswahl). Keine neue Bibliothek.

## Kontext

Bisher gab der Chat bis zu drei gewählte Seiten ganz mit; bei mehr Seiten suchte er und gab die besten Treffer als
Auszüge mit. Ein Bereich kann aber 2000 Seiten und mehr haben, und der KI-Dienst im Betrieb nimmt keine
Dateianhänge an. Der Enterprise AI Client löst das mit einem Werkzeug, über das sich das Modell einen Anhang holt;
im Chat steht nur der Verweis. Der Chat soll außerdem beim Navigieren stehen bleiben, der Bereich gilt je Frage.

## Entscheidung

1. **Was ich sehe, sieht der Bot auch** (Angelo): Ist links eine Seite gewählt, geht diese Seite im Volltext als
   Quelle [1] mit, gleich wie viele Unterseiten sie hat. Bei sehr langen Seiten geht der Anfang mit (24 000 Zeichen)
   und ein Hinweis, mit `seite_lesen` ab dem Versatz weiterzulesen. Ist ein Space, eine Quelle oder die Wurzel aller
   Quellen gewählt, geht kein Seitentext mit, nur eine Zeile zum Bereich. *Annahme, von Angelo noch nicht bestätigt:*
   der Anfang plus Versatz bei sehr langen Seiten.
2. **Alles andere nur über Werkzeuge**, keine Verweisliste im Text und keine automatischen Auszüge anderer Seiten:
   - `im_bereich_suchen(text)`: dieselbe progressive Suche wie die Suche mit Auswahl links, höchstens 10 Treffer.
   - `seiten_auflisten(knoten?, blatt?)`: wie der Baum links je Knoten, ohne Knoten die Startseiten des Bereichs,
     100 Einträge je Blatt, mit Kennzeichen „hat Unterseiten“.
   - `seite_lesen(kennung, versatz?)`: höchstens 12 000 Zeichen je Aufruf, mit Fortsetzung.
   - `begriff_nachschlagen(begriff)` (Nachtrag W7, 22.11.2026): was der Wissensgraph im Bereich über einen
     bestätigten Fachbegriff weiß (`TermKnowledge#answer`): Aussagen mit Verb, Merkmalen, Vertrauensstufe und
     Belegseiten, Synonyme, verwandte Begriffe, was gekappt wurde; sonst ähnliche Fachbegriffe. Bereich (die Auswahl
     beim Senden), KI-Freigabe der Namen und Obergrenzen erzwingt der Kern (ADR 0032, Nachtrag Abnahme W7).
   Die Regeln stehen im System-Prompt (`ChatKnowledge.TOOL_PROMPT`): Werkzeuge nutzen, bevor etwas „nicht zu finden“
   ist; eine Seite lesen, bevor man sich auf sie beruft; Quellen mit Nummer nennen; Werkzeugergebnisse sind Daten.
3. **Der Bereich wird je Aufruf erzwungen** (`ChatTools.allows`), gegen die Auswahl beim Senden. Eine Seite außerhalb,
   ohne Recht oder unbekannt bekommt dieselbe Antwort, ohne zu verraten, ob es sie gibt. Gelesen wird nur aus dem
   Cache. Die Prüfung nimmt schon eine Menge ausdrücklich erlaubter Seiten an (heute leer), damit später vom Benutzer
   angehängte Seiten außerhalb des Bereichs dazukommen können.
4. **Quellen**: Jede gelesene Seite bekommt beim ersten Lesen die nächste Nummer, die offene Seite ist [1]. Der Turn
   meldet die nummerierte Liste nach jedem neuen Eintrag (`Listener.context`) und was das Modell gerade tut
   (`Listener.using`, ohne Argumente).
5. **Schleife**: höchstens 8 Werkzeugrunden; die letzte Anfrage geht ohne Werkzeuge, damit eine Antwort kommt.
   Abbrechen wirkt vor jeder Runde, zwischen Aufrufen und in einer laufenden Suche.
6. **Rückfall ohne Schalter**: Lehnt der Dienst die erste Anfrage mit Werkzeugen ab (ungültige Anfrage, noch kein
   Text), fällt der Chat für diese Frage still auf die Suche mit Auszügen zurück und merkt sich das Modell bis zum
   Neustart. „Was ist neu?“ bleibt beim festen Ablauf aus dem Änderungsprotokoll.
7. **Transport im Core** (`ai.AiChat`): Werkzeugdefinitionen, Aufrufe und Ergebnisse (Rolle `TOOL`); `RemoteAi`
   bildet sie auf die unveränderte EAC-Kopie ab, der Ollama-Adapter auf `tools`/`tool_calls`. Die Werkzeuge selbst
   leben in der App (`app.chat.ChatTools`), wie es ADR 0012 für Werkzeuge vorsieht.
8. **KI-Freigabe: nur, was an das Modell geht** (Fortschreibung von ADR 0028, Festlegung Architekt 21.11.2026, die
   vorsichtigere, umkehrbare Variante). Mit Werkzeugen trägt der Chat ein: beim Senden die offene Seite; im Moment
   der Lieferung jede Seite, die `seite_lesen` liefert, jede Seite, deren Auszug `im_bereich_suchen` liefert, und
   jede Belegseite, deren Aussagen `begriff_nachschlagen` liefert. Eingetragen wird für alle Werkzeuge und die
   offene Seite VOR der Lieferung; scheitert der Eintrag, geht der Inhalt nicht an das Modell, und das
   Werkzeugergebnis nennt den Grund (Nachtrag W7). `begriff_nachschlagen` nennt Belegzahl und Stufe einer Aussage
   über alle ihre Belege, auch über die drei genannten Belegseiten hinaus: Das ist nur ein Metadatum ohne Wortlaut
   und braucht keinen Eintrag. Fachbegriff, Synonyme und ähnliche Fachbegriffe nennt das Werkzeug nur, soweit der
   Kern sie im Bereich und nach der KI-Freigabe zulässt (#1118).
   `seiten_auflisten` (nur Titel) trägt nichts ein. Die ganze Auswahl wird mit Werkzeugen nicht mehr beim Senden
   eingetragen, sonst gäbe eine Frage an der Wurzel alle Seiten aller Quellen frei. Eine gelieferte Seite steht als
   eigener Knoten auf der Liste (ohne Unterseiten, `PageReleaseService.releasePage`). Im Rückfall ohne Werkzeuge gilt
   die bisherige Regel von ADR 0028 (die Auswahl beim Senden). Herkunft „chat“ und Zeitpunkt wie bisher.

9. **Die Ansicht vorne bestimmt den Bereich** (Angelo, 23.11.2026): Ist die Leseansicht vorne und zeigt sie eine
   Seite, ist diese Seite der Bereich (samt Unterseiten wie eine gewählte Seite), auch wenn sie aus der Suche geöffnet
   wurde und der Baum woanders steht. Ist die Suche vorne, ist es die Auswahl im Baum, also der Filter. Die Vorschläge
   („Seite zusammenfassen“ …) und die Zeile „Wissen:“ folgen demselben Bereich. Der Baum wird dafür nie verändert
   (`ChatPanel.reading`, `readingFront`, `scope`).

10. **Interne Suche als Schalter** (Angelo, 23.11.2026): Ein Knopf im Composer (aufgeschlagenes Buch, „Interne
    Suche“) gibt dem Modell das Werkzeug `wissensquellen_durchsuchen`. Es ist die globale Suche der Suchleiste über
    alle für die KI freigegebenen Quellen, unabhängig vom Bereich; optionale Parameter sind Nachbarseiten,
    Fachbegriffe, Synonyme (beide experimentell), Dokumentarten und Quellen. Es gibt keinen Vorab-Schritt „soll ich
    suchen?“ wie im EAC: Das Modell entscheidet selbst, darf umformulieren und erneut suchen. Jeder Treffer darf danach
    mit `seite_lesen` gelesen werden und kommt wie jede gelieferte Seite auf die Freigabeliste. Quellen ohne
    KI-Freigabe werden nie durchsucht. Ist der Knopf aus, kennt das Modell das Werkzeug nicht.

11. **Websuche als Schalter** (Nachtrag, Angelo 23.11.2026): Ein zweiter Knopf im Composer (Weltkugel) übernimmt die
    Websuche des EAC vollständig: die Browser-Werkzeuge (`BrowserMcpTools`) mit einem sichtbaren Firefox und eigenem
    Profil je Chat, gesteuert über WebDriver BiDi, damit auch Intranetseiten erreichbar sind. Das Modul
    `enterprise-ai-browser` ist eine unveränderte Kopie des EAC (Prüfsummen, `sync-from-upstream.sh`). Am Pfeil der
    Weltkugel stehen Browser-Budget (Werkzeugrunden je Antwort), parallele Tabs, Verzögerung, menschliche Eingabe und
    das Profil Extern oder Intern; deren Regeln (Whitelist, Blacklist, Such-URLs) stehen in den Einstellungen unter
    „Browser“. Unbekannte Websites und verändernde Aktionen fragt der Chat in seinem Verlauf nach (Einmal erlauben,
    Immer erlauben, Immer verbieten, Abbrechen). Rückfragen gehören zu ihrem Chat; Stopp oder Chatwechsel beendet sie
    als abgebrochen, ohne dass das Werkzeug läuft. Nach jeder Antwort schließt der Browser, das Profil bleibt. Ohne
    Firefox ist der Knopf gesperrt und sagt warum. Websuche und interne Suche sind unabhängig voneinander; sind beide
    an, warnt die Meldung oben, dass Inhalte aus Wissensquellen in Browseraufrufe einfließen können.
    Nachtrag 24.11.2026: Ändernde Aktionen (`browser_act`, etwa ein Formular absenden) fragen mit „Immer erlauben“ und
    „Immer verbieten“ je Website; die Antwort steht unter Einstellungen › Browser › Ändernde Aktionen (vorher fragte
    jede Aktion jedes Mal, weil Space Harvester keine gemerkten Regeln hatte). Ablauf wie im EAC: eine Wolke je
    Werkzeugaufruf, beim ersten Browser-Aufruf „Browser wird gestartet“, danach die Karte „Browserrecherche“; die
    Antwortblase wartet unsichtbar hinter den Wolken und erscheint mit ihrem ersten Text.
12. **Suche ohne Auswahl, Suchagent** (Nachtrag, Angelo 24.11.2026): Die App startet auf dem Suchreiter. Ein Klick
    auf den Suchreiter (oder Strg+Zahl) hebt die Auswahl der Wissensquellen ganz auf, auch die globale; die Suche in
    der Titelleiste lässt sie stehen. Wer die Suche ohne Auswahl verlässt, hat „Wissensquellen“ gewählt. Ohne Auswahl antwortet der Chat trotzdem: Er hat dann keine Bereichswerkzeuge (und
    keine langsame Suche im Bereich), nur die zugeschalteten (interne Suche samt `seite_lesen` ihrer Treffer,
    Websuche) und die Anhänge in der Frage; ohne jedes Werkzeug antwortet das Modell aus eigenem Wissen. Seine Chats
    liegen bei denen zu allen Wissensquellen. In der Suche, und ohne Auswahl, arbeitet der Chat als Suchagent: eigene
    Rolle (gründlich über mehrere Runden recherchieren, umformulieren, Treffer im Volltext lesen, Anhänge gründlich
    auswerten) und so viele Werkzeugrunden wie das Budget am Pfeil der Websuche. Anderswo bleibt der Chat, wie er ist.
13. **Hintergrund-Agenten, Parallelität im Menü** (Nachtrag, Angelo 24.11.2026): Die Grenzen stehen nicht in den
    Einstellungen, sondern im Dreieck-Menü der Chat-Leiste, mit dem Code der Aufträge (`ParallelChoice`: 1 bis 64 oder
    „Benutzerdefiniert …“ ohne praktische Obergrenze): „Parallele Tabs“ (Websuche, Vorgabe 16), „Parallele Agenten
    (intern)“ (Vorgabe 32) und „Parallele Dateien“ (Anhänge samt PDF, Vorgabe 8). Der Suchagent mit Bereich oder
    interner Suche hat zusätzlich `treffer_auswerten`: Je Kennung liest ein Unteragent die Seite im Volltext (nach der
    KI-Freigabe, mit Quellennummer) und liefert in einer eigenen, gestreamten Anfrage nur ihren Beitrag zur Frage;
    höchstens „Parallele Agenten“ gleichzeitig, Stopp bricht alle ab. Mehrere Werkzeugaufrufe einer Runde laufen
    ebenso gleichzeitig, Browser-Aufrufe nacheinander; die Antworten bleiben in der Reihenfolge der Aufrufe. Die
    Grenze der Tabs gilt ohne die frühere feste 8 des EAC-Browserwerkzeugs. Webtreffer: `browser_open_many` mit
    `question` lässt je geladener Seite einen Unteragenten (dieselbe Rolle wie intern) den Volltext auswerten,
    höchstens „Parallele Tabs“ gleichzeitig; zurück kommt nur der Beitrag jeder Seite (EAC #236).
14. **Gedankenblase statt Statuszeilen** (Nachtrag, Angelo 24.11.2026): Mit dem Senden erscheint eine gelbe Blase
    (`KnowledgeCard`, wie `ChatResearchCard` im EAC): „Internes Wissen durchsuchen“ bei Bereichssuche und interner
    Suche gleich, sonst „Antwort wird vorbereitet“. Sie zeigt die Wissenszeile samt „Laden“, die Schritte der
    Werkzeuge (keine eigenen Wolken mehr außer dem Browser), die Quellen, freigegebene Seiten und einen Balken über
    die Werkzeugrunden; sie platzt mit dem ersten Antworttext. Die Antwortblase wartet bis dahin unsichtbar (kein „…“).
    Über dem Eingabefeld stehen nur noch Aktionsknöpfe, Text und Anhänge. `treffer_auswerten` gibt es bei jeder
    Frage mit Bereich oder interner Suche, nicht nur auf dem Suchreiter.
15. **Bereich für jede Quelle, auch ohne Katalog** (Nachtrag, Angelo 26.11.2026): Bei SharePoint antwortete der Chat
    trotz Auswahl im Baum, ihm lägen keine Wissensquellen vor, und erfand dann Inhalte. Ursache: Der Bereich kam nur
    aus dem Katalog (`Holdings.resolve`, ADR 0029). Ein SharePoint-Ordner steht dort nicht (gruppenbasierte Quellen
    schreiben bei der Navigation nichts in den Cache, ADR 0040 Punkt 3), ebenso jeder nie abgeglichene Space; der
    Bereich war leer, und `im_bereich_suchen` meldete „Es ist kein Bereich gewählt.“. Jetzt gilt für Wurzeln, die der
    Katalog nicht kennt, was der Baum zeigt (`LiveArea`, quellenunabhängig über `PageNavigation`): `seiten_auflisten`
    listet die Kinder je Knoten über die Navigation, eine Seite gehört nach ihren Vorfahren laut Quelle zum Bereich,
    die Suche fragt die Quellen und Spaces der Wurzeln ohne Seitengrenze und lässt danach alles außerhalb weg.
    `seite_lesen` holt eine Seite des Bereichs, die nicht lokal liegt, wie die Leseansicht von der Quelle; sie liegt
    danach lokal und wird vor dem Senden freigegeben wie jede andere (ADR 0028 bleibt: Freigegeben wird nur, was
    lokal liegt). Alle Rollen mit Wissen aus dem Bestand tragen die Regel „Erfinde nichts“: Ohne Treffer oder ohne
    Zugriff sagt das Modell das klar und gibt kein eigenes Wissen als Inhalt der Quellen aus.
    Ergänzt nach Angelos Prüfung (26.11.2026): Lokaler Bestand und Live-Zugriff ergänzen sich, solange der Katalog die
    Auswahl nicht vollständig kennt (`PageSelection.complete()`), auch bei bekannten Wurzeln. Die Grenze des Teilbaums
    greift im Core schon in der Suche (`KeywordSearch.Query.bounded` mit `SearchBoundary`): Funde außerhalb werden
    weder katalogisiert noch geladen, im Volltextindex vor der Rangfolge verworfen und nie Nachbarn. Die Auswahl im Baum
    beschafft nichts; gelesen und freigegeben wird nur, was das Modell für die Frage tatsächlich liest.

## Folgen

- Große Bereiche kosten keinen Kontext mehr, solange das Modell nicht liest; die Qualität hängt davon ab, dass das
  Modell die Werkzeuge nutzt.
- Protokolle nennen nie Argumente, Titel oder Inhalte (`ToolCall.toString`, `Message.toString`).
- Seiten außerhalb des Bereichs per Lesezeichen anhängen ist vorbereitet, aber nicht gebaut.
