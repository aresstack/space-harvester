# ADR 0018: Medienbeschaffung und Einmalcode (OTP) im Export

- Status: **Angenommen, zwei Schritte umgesetzt** (Strang M, 18.10.2026; Entscheidungen von Angelo, 18.10. 20:20).
  Ergänzt [ADR 0010](0010-export-ohne-vorbereiteten-bestand.md) (einmal beschaffen, beliebig konvertieren).
- Umgesetzt im ersten Schritt: Einordnung vor dem Download (draw.io-Vorschau überflüssig), Warten des Auftrags auf den
  Einmalcode mit Fortsetzen an derselben Stelle (`SignInRequests`), Status `SIGN_IN_REQUIRED` und `REDUNDANT` in
  `assets.json`, erneutes Beschaffen (`ExportConversions.collectImages`).
- Umgesetzt im zweiten Schritt: Format `json-package` (ZIP mit `pages.json`, `assets.json`, `assets/`) und draw.io als
  Knoten und Verbindungen (`DrawioGraph`, `*.graph.json`).
- PDF und Word: bereits die Writer des Enterprise AI Client (`document-export`, Kopie von `PdfExportWriter` und
  `DocxExportWriter` mit Inhaltsverzeichnis, Bildplatzierung und Skalierung; Paritätstests ohne bekannte Lücken).
- Server: `GET /api/v1/sign-ins`, `POST /api/v1/sign-ins/{id}/code` (`{"code": "…"}`), `POST …/resend`,
  `DELETE /api/v1/sign-ins/{id}`; nur Anforderungen, auf die ein eigener Auftrag wartet. Wartezeit
  `space-harvester.export.sign-in-wait` (Standard `PT0S`: nie).
- Markdown mit Medienordner: Format `markdown-package` (ZIP mit `pages.md` und `assets/<sha256>.png`, relative
  Verweise). Medienstand je Export als Zahl: `ExportDatasetInfo.media()`.
- Offen: Eingabemaske in der App (Strang L), Ansichtsbild der Vorschau doppelt, wenn das
  Diagramm nicht zeichenbar ist.
- Bezüge: Paritäts-Issues #185 (2FA beim Download), #190 (Diagramme), #184 (nur Textfassung von Diagrammen);
  `ExportAssetCollection`, `ExportAssetManifest`, `ExportChapters`, `SignInRequests`.

## Kontext

Bilder müssen exportierbar sein (Angelo 20:20). Vieles ist schon da: Nach jedem Export beschafft ein eigener Auftrag
„Bilder beschaffen“ die Bilder seiner Seiten, legt sie SHA-256-adressiert ab und schreibt `assets.json`; die Converter
bevorzugen bei draw.io die Quelldaten. Es fehlten drei Dinge:

1. Manche Confluence-Server liefern Dateien erst nach einem Einmalcode (zweiter Faktor). Der Confluence-Adapter aus
   dem Enterprise AI Client erkennt das Formular, sendet Code und „neu senden“ und hält die Sitzung für die ganze
   Quelle. Im Auftrag kam davon nur „Zugriff verweigert“ an; alle Bilder fehlten.
2. Das draw.io-Plugin legt neben jedem Diagramm ein PNG als Backup ab. Der Auftrag lud beides, obwohl das Diagramm
   aus den Quelldaten gezeichnet wird. Eine KI kann mit dem gerenderten Bild wenig anfangen.
3. Der JSON-Export kennt nur Seitendaten; Bilder sind nicht transportierbar.

Im Enterprise AI Client funktionieren Einmalcode in der Leseansicht und der PDF-Export mit Bildern und
Inhaltsverzeichnis (Angelos Test 20:20). Ein laufender Export behandelt eine Einmalcode-Abfrage dort aber als
fehlendes Bild; er profitiert nur von einer vorher in der Leseansicht freigeschalteten Sitzung.

## Entscheidung

1. **Zwei Phasen in einem Auftrag, keine neue Pipeline.** Erst Seiten und Texte wie bisher (Export), dann Medien
   (Auftrag „Bilder beschaffen“). Alle Formate lesen denselben Bestand. Kein Dialog, kein Sammler.
2. **Einordnen vor dem Download.** draw.io-Quelldaten zuerst. Ist das Diagramm gezeichnet, wird seine Vorschau
   (`<Name>.png`, auch als Bild der Leseansicht) nicht geladen und steht als `REDUNDANT` mit Grund `drawio-preview`
   in `assets.json`; sie zählt nicht als fehlend. Sind die Quelldaten nicht lesbar oder nicht darstellbar, ist die
   Vorschau der Ersatz und wird geladen. Ein Bild ohne Quelldaten wird immer geladen. Mermaid bleibt Text mit
   gezeichneter Grafik.
3. **Einmalcode: Erkennung und Sitzung im Adapter, Warten im Core, Eingabe in der App.** Der Adapter bleibt
   unverändert (Kopie des Enterprise AI Client). `KnowledgeSourceHarvestSource` reicht seine Abfrage als
   `SignInRequiredException` mit `SignInChallenge` weiter (Grund weiter `ACCESS_DENIED` für alle anderen Aufrufer).
   Der Auftrag wartet an derselben Stelle auf `SignInRequests` (Vorbild `UnlockingDocumentReader` und
   `OneTimeCodePrompt` im Enterprise AI Client), Phase „Wartet auf Anmeldung“. Bereits abgelegte Bilder bleiben.
   Nach der Annahme lädt er dieselbe Datei neu und macht weiter. Je Quelle gibt es eine Anforderung, weil die Sitzung
   für die ganze Quelle gilt; weitere Aufträge derselben Quelle warten auf dieselbe.
   Beim Lesen auf Abruf (Leseansicht-Bild, Anhang, Vorschau) wartet niemand: `Assets` legt dieselbe Anforderung je
   Quelle an oder übernimmt sie (`SignInRequests.open`, gehalten bis zum Ende der Wartezeit), antwortet mit
   `SignInPendingException` (REST 403 `sign-in-required` mit `signInRequestId`) und liest nach der Annahme erneut.
   Schlüssel ist die Quelle, weil die Sitzung der Quelle geteilt ist; mit Sitzungen je Benutzer wäre er Quelle und
   Benutzer.
4. **Ohne Anmeldung** (abgelehnt, Wartezeit abgelaufen, oder keine Oberfläche: Wartezeit null, der Standard im
   Core und im Server, bis eine Eingabemaske sie setzt; empfohlen 15 min) fehlen die übrigen Dateien des Laufs mit
   `SIGN_IN_REQUIRED`, ohne erneut zu warten. Nachholen startet einen neuen Lauf (`collectImages`); schon abgelegte
   Dateien kommen aus dem Speicher, und was der neue Lauf nicht erreicht, behält den Stand des vorigen.
5. **Der Code** wird nur an die Abfrage der Quelle gegeben und danach im `char[]` überschrieben. Er steht in keinem
   Feld, Log, Ereignis, Export und geht an kein Modell. Protokolliert wird nur „angenommen“ oder „nicht angenommen“.
6. **JSON-Export mit Medienverzeichnis**, keine Base64-Einbettung: Format `json-package` liefert ein ZIP mit
   `pages.json` (wie `json`), `assets.json` (Format `space-harvester/export-package-assets/v1`: je Datei Seite, Art,
   Dateiname, Medientyp, `sha256`, Status, Grund, relativer `path`, gezeichnete Grafik, bei draw.io `graph` und
   `text`) und `assets/<Seiten-ID>/<Datei>`. Das Format `json` bleibt unverändert (REST-Verbraucher). PDF und Word
   betten Bilder ein.
7. **draw.io auch als Text**: neben der Originaldatei Beschriftungen, Knoten und Verbindungen als `*.graph.json` für
   KI-Systeme. PDF und Word zeichnen aus den Quelldaten.
8. **PDF wie im Enterprise AI Client**: Bildplatzierung, Skalierung, Umbrüche und Inhaltsverzeichnis von dort
   übernehmen, nicht neu bauen.

## Schnittstelle für die App

- `Harvester.signIns()` → `SignInRequests`: `pending()`, `waiting(jobId)` (Auftrag „Wartet auf Anmeldung“),
  `onChange(Runnable)`, `submit(requestId, char[])` → `ACCEPTED | REJECTED | FAILED | GONE`, `resend(requestId)`,
  `decline(requestId)`. Alle Aufrufe mit Netz blockieren, nicht auf dem EDT.
- `SignInRequest(id, sourceId, service, title, message, error, resendAvailable, jobIds, since)`: Texte der Quelle für
  die Maske („Zusätzliche Anmeldung erforderlich“, Hinweis, Fehler, „Code erneut senden“ nur bei `resendAvailable`).
- `Harvester.Builder.signInWait(Duration)`: Standard `Duration.ZERO` (wartet nie); die App setzt
  `SignInRequests.DEFAULT_WAIT` (15 min), sobald ihre Eingabemaske `signIns()` bedient.
- Medienstand je Export wie bisher `ExportDatasetInfo.images()`; Gründe je Datei in `assets.json`.

## Folgen

- Ein Auftrag kann bis zur Wartezeit im Zustand `RUNNING` stehen, ohne zu laden; die Phase und `waiting(jobId)`
  sagen warum. Abbrechen greift auch während des Wartens.
- Der Server wartet nur mit `space-harvester.export.sign-in-wait`; dann beantwortet der Client über `/api/v1/sign-ins`.
- `assets.json` hat zwei neue Statuswerte; Leser, die nur `STORED` als vorhanden werten, bleiben richtig, sollten
  `REDUNDANT` aber nicht als fehlend melden (`Entry.present()`).
