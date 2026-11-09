# ADR 0023: Dateianhänge im Export

- Status: **Angenommen, Umsetzung offen** (Strang M, 09.11.2026; Frage von Angelo, 09.11. 13:51).
  Ergänzt [ADR 0018](0018-medienbeschaffung-und-otp-im-export.md) (Medien im Export) und
  [ADR 0021](0021-bildanalyse.md) (Bildanalyse).
- Offen: Beschaffung und JSON-Paket (M), Abschnitt `attachments` im Analyse-Export (M mit W), Einstellung in der
  App (L, nur auf Bestellung).
- Bezüge: #183 (Textextraktion), #328 (`TikaDocumentText`), #365 (Vorschauen), #385 (Anhangstexte im
  Volltextindex); Enterprise AI Client `ConfluenceConfig.includeAttachments`, `deigma/document-tika`.

## Kontext

An Confluence-Seiten hängen neben Bildern auch PDFs, PowerPoint-Folien, Word-Dateien oder Mails. Für die Analyse
zählt deren Text. Der Core kann das meiste schon: alle Anhänge auflisten, mit Grenze herunterladen und ablegen
(`Assets`, `LargeFileGate`), den Typ an den Magic Bytes prüfen und den Text über Apache Tika lesen
(`TikaDocumentText`, Kopie von EAC `document-tika`, PDF, Office, ODF, RTF, `.eml`, `.msg`; 20 MiB, 30 s, 1 Mio.
Zeichen). Die Volltextsuche findet Anhangstexte an ihrer Seite (#385). Tika ist also keine neue Abhängigkeit.

Der Export nimmt davon nichts mit: `ExportAssetCollection` beschafft nur Bild-, Vektor- und Diagramm-Anhänge.
Der Enterprise AI Client exportiert Nicht-Bild-Anhänge ebenfalls nicht; er meldet textartige Anhänge nur auf
Wunsch je Quelle (`includeAttachments`, Standard aus) als eigene Ressourcen.

## Entscheidung

1. **Anhänge gehören zum Export, wie die Bilder.** Der Auftrag „Bilder beschaffen“ (ADR 0018) legt zusätzlich die
   Dokument-Anhänge der exportierten Seiten ab: derselbe Downloadweg (`Assets`), dieselbe Anmeldung (OTP),
   dieselbe Ablage je SHA-256, dieselben Statuswerte im Manifest. Kein zweiter Beschaffungsweg.
2. **Optional, Standard an.** Einstellung `space-harvester.export.attachments.enabled` (Standard `true`),
   `…media-types` (Standard: PDF, Office, OpenDocument, RTF, `message/rfc822`, `application/vnd.ms-outlook`,
   `text/*`) und `…max-size` (Standard 20 MiB, die Grenze der Textextraktion). Größere oder andere Dateien stehen
   im Manifest mit Status und Grund, ohne Inhalt. Live änderbar wie alle Einstellungen (ADR 0015).
3. **Original und Text.** Das JSON-Paket legt das Original unter `media/` ab und daneben den mit Tika gelesenen
   Text als `<name>.txt`; das Manifest nennt beide Pfade und den SHA-256 des Originals. Kann Tika die Datei nicht
   lesen, bleibt das Original mit Hinweis `text-unavailable`.
4. **Analyse-Export additiv.** Je Seite `attachments: [{attachmentId, title, mediaType, sha256, text}]` neben
   `images` (ADR 0021); `analysis-export/v1` bleibt, der Konverter hebt seine Version an. Word, PDF und Markdown
   nennen die Anhänge am Kapitelende mit Namen, ohne Text.
5. **Grenzen wie gehabt.** Bilder laufen weiter über ADR 0021. Anhänge nicht freigegebener Quellen gehen nie an
   einen KI-Dienst; der Export enthält keine Zugangsdaten. Rechte wie die Seite: kein Anhang ohne lesbare Seite.

## Folgen

- Ein Export wird größer; die Einstellung begrenzt Typen und Größe.
- Absätze und Fakten lesen Anhangstexte vorerst nicht; das wäre eine eigene Entscheidung.
- Gewollte Abweichung vom Enterprise AI Client (SH+), in PARITY.md durch Strang S nachzutragen.
