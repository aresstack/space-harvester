# ADR 0025: Abgebrochenes Einlesen setzt fort statt neu zu beginnen

- Status: **Angenommen** (Strang Q, 10.11.2026; Wunsch Angelo 10.11.2026: „eine Art Download-Manager, der nicht jeden
  Download immer wieder von vorne beginnt“).
- Baut auf: [ADR 0010](0010-export-ohne-vorbereiteten-bestand.md) (Export liest selbst ein),
  [ADR 0014](0014-abgleich-berechtigung-suche-getrennt.md) (Cache kennt Seiten auch ohne Generation, `knownPages`).

## Kontext

Ein Abgleich oder ein Export mit Einlesen merkt alle Schreibvorgänge in einer `StagedGeneration` vor und macht sie
erst mit der neuen Generation sichtbar. Bricht der Lauf ab (Abbruch, Laufzeitgrenze, Absturz), wird alles verworfen,
auch die schon geladenen Seiten. Bei einer Erstbefüllung mit Tausenden Seiten beginnt „Erneut versuchen“ dann von vorn.
Zudem hält der Lauf die Schreibsperre des Caches über den ganzen Abruf, sodass Beschaffung und Suche warten.

## Entscheidung

1. **Neue Seiten sofort und dauerhaft.** Seiten, die weder Generation noch Cache kennen (`PageChange.Kind#NEW`), lädt
   `PlanApplier#loadNew` vor `SyncBaselineStore#begin` und legt sie mit `SyncBaselineStore#keepLoaded` ab, wie es die
   Beschaffung tut (`LucenePageCache#saveWaiting`: begrenzte Wartezeit, überschreibt nichts Neueres). Sie sind damit
   vor der Generation sichtbar, genau wie Seiten aus Suche oder Abruf (ADR 0014, Punkt 2).
2. **Fortsetzen ist Wiederholen.** Der nächste Lauf vergleicht die Inventur mit `knownPages`; schon abgelegte Seiten
   derselben Version sind `UNCHANGED` und werden nicht erneut geladen. Ein eigener zweiter Download-Weg entsteht
   nicht.
3. **Änderungen bekannter Seiten bleiben atomar.** Neue Versionen, Kopfdaten, Entfernen und Sperren laufen weiter
   über die `StagedGeneration` und werden nur mit der Generation sichtbar.
4. **Umsetzungen ohne diese Fähigkeit** (`keepsLoadedPages() == false`, etwa Testfakes) verhalten sich wie bisher.
5. **Anhalten und Fortsetzen im selben Auftrag** (Wunsch Angelo 10.11.2026: Play-/Pause-Knopf). Neuer Zustand
   `JobState.PAUSED` („Angehalten“), getrennt von Abgebrochen und Fehlgeschlagen. `Jobs#pause` nutzt den Abbruch-
   Prüfpunkt: der laufende Schritt endet sauber, ein wartender Auftrag ist sofort angehalten. `Jobs#resume` reiht
   einen angehaltenen, gescheiterten (etwa Verbindungsabbruch) oder abgebrochenen Auftrag unter derselben ID wieder
   ein; er läuft von vorn, holt nach Punkt 2 aber nur, was fehlt, und seine Zähler beginnen neu. Nur Abgleich und
   Export (`JobType#resumable`); ein Abgleich setzt nicht fort, solange für den Space ein anderer läuft.
6. **Nach einem Neustart** ist die Arbeit eines Auftrags nicht mehr im Speicher. Seit Punkt 8 läuft er trotzdem im
   selben Eintrag weiter (`Jobs#rerun`): `JobStatus#parameters` legt ab, was ein gleichwertiger Lauf braucht
   (Export: Tiefe, Formate, Aktualität; Abgleich: Betriebsart, Nachholen ab; Bilder: Export-ID), und der zuständige
   Anwendungsfall baut daraus dieselbe Arbeit. `job-not-resumable` (409) kommt nur noch für Aufträge, die noch laufen
   oder fertig sind, für andere Arten und für alte Bilderaufträge ohne Export-ID.
7. **Ein Teilbaum-Export lädt nur seinen Teilbaum.** Die Inventur listet weiter den ganzen Space (für die
   Elternkette), Inhalte lädt das Einlesen aber nur für Startseite und Unterseiten (`SyncPlan#withLoadingLimitedTo`).
   Der übrige Space wird wie bei `CATALOG_ONLY` behandelt: unbekannte Seiten kommen nur als Kopfdaten in den Katalog,
   geänderte bleiben veraltet. Die Generation ist damit nicht `CONFIRMED`; der Export gilt trotzdem als vollständig,
   wenn ihre Lücken (`SpaceProvisioning#unloaded`) alle außerhalb des Teilbaums liegen. Ein späterer Space-Export
   liest dann neu ein und lädt nur das Fehlende.

8. **Zustandsobjekte und ein Einstieg für Aktionen** (Angelo 10.11.2026). `JobLifecycleState` leitet aus
   `JobState`, `JobStatus#incomplete` und `JobProgress#failed` genau einen wirksamen Zustand ab (Wartet, Läuft,
   Anhalten/Abbruch angefordert, Angehalten, Fertig, Fertig mit Fehlern, Fehlgeschlagen, Abgebrochen); jeder Zustand
   nennt seine erlaubten `JobAction`s in Anzeigereihenfolge. ▶ und ⏸ erscheinen nie zusammen, vor dem Start steht
   „Jetzt starten“ (`Jobs#prioritize`, an den Anfang der Warteschlange), gelöscht wird nur Beendetes, und Löschen
   lässt die Daten liegen. `JobActions#perform` prüft am Zustand und gibt an Abgleich, Export, Bilder oder `Jobs`
   weiter; keine Aktion legt einen neuen Auftrag an. `COMPLETED` heißt nur technisch beendet: unvollständige Exporte
   und Bilder melden `JobContext#incomplete`.
9. **Bilder sind fortsetzbar** (`JobType#ASSETS`). Beim Anhalten schreibt der Auftrag das bisherige Manifest; ein
   Fortsetzen übernimmt fertig geprüfte Seiten mit ihren Einträgen und Zählern ohne Abruf, schon abgelegte Dateien
   kommen aus dem Speicher.

Folgen in eigenen Schritten: einheitliche, einstellbare Wiederholungen ohne zweite Schleife über dem REST-Client,
„Nur Fehler neu versuchen“ und „Neu erzwingen“ (neue Generation, erst nach Erfolg sichtbar), beide im selben Eintrag.

REST: `POST /api/v1/exports/{id}/pause|resume` und `POST /api/v1/syncs/{id}/pause|resume`; die Knöpfe in der
Auftragszeile und im Overlay baut Strang OH über `Workspace#pauseJob`, `#resumeJob` und `#canResume`; künftig
zeichnet sie nur `Workspace#jobState(id).actions()` und ruft `Workspace#performJobAction`.

## Folgen

- Ein abgebrochener Lauf hinterlässt neue Seiten ohne Generation; Suche und Lesen finden sie, der Export erst mit
  einer bestätigten Generation.
- Die Schreibsperre ist während des Abrufs neuer Seiten nur je Seite belegt.
- Ein Teilbaum-Export lädt nur die Seiten seines Teilbaums statt des ganzen Space.
- Offen: Die Inventur selbst hält ihren Stand noch nicht fest und läuft sequenziell (eigener Schritt; der EAC listet
  ebenfalls sequenziell).
