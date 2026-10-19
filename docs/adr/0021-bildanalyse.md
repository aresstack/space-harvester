# ADR 0021: Bildanalyse

- Status: **Angenommen, im Core und Server umgesetzt** (Strang X, 19.10.2026; Wunsch von Angelo, 19.10. 01:58).
  Ergänzt [ADR 0011](0011-embeddings-ueber-externen-ki-dienst.md) (externer KI-Dienst), [ADR 0015](0015-konfiguration-zur-laufzeit.md)
  (Konfiguration zur Laufzeit) und [ADR 0018](0018-medienbeschaffung-und-otp-im-export.md) (Medien).
- Umgesetzt: Einstellung `space-harvester.ai.vision-model`, `VisionImageDescriber`, `RemoteAi.describeImage`,
  Prozessor `images`, `PageImageAnalysis` (`harvester.imageAnalysis()`), `PageImages.read`, REST
  `GET`/`POST /api/v1/sources/{id}/pages/{pageId}/images`.
- Offen: Reiter „Bilder“ der Leseansicht und Modellwahl in Einstellungen → Modelle (Strang L), Abschnitt `images` im
  Analyse-Export (#359, Strang W, ohne eigenen Code).
- Bezüge: #362, #184 (Parität), #19 (Asset-Pipeline), #359; Enterprise AI Client `SourceImageAnalysisUseCase`,
  `ModelsConfig` (`model.vision`).

## Kontext

Die Leseansicht zeigt Bilder, deren Inhalt für Suche und Analyse verloren ist. Der Enterprise AI Client beschreibt
Bilder mit einem eigenen Vision-Modell (`model.vision`, Kategorie `VISION`): je Bild eine Nutzernachricht mit dem
Prompt „Beschreibe dieses Bild sachlich auf Deutsch in höchstens fünf Sätzen …“ und dem Bild als Data-URL, nur das
Modell gesetzt, im Lesebereich erst auf Abruf, zwischengespeichert je Bild und Version. Der Core hatte dafür schon die
Kopie von `SourceImageAnalysisUseCase`, Bilder im Chat-Adapter, den Port `ImageDescriber` und die Ablage der
Beschreibungen je SHA-256, Beschreiber und Modell in der Asset-Ablage (#19), aber keinen Beschreiber, kein Modell und
keine Sicht je Seite.

## Entscheidung

1. **Eigenes Modell.** `ai.vision-model` steht neben `chat-model`, `embedding-model` und `rerank-model`
   (`AiServiceSettings.visionModel()`, `vision()`), live über den `ConfigurationManager`. Ohne Wert ist die
   Bildanalyse aus: Lesen nennt `vision-model-missing`, Beschreiben antwortet mit `image-description-unavailable`
   (503). Die Modellliste markiert geeignete Modelle mit der Kategorie `vision` des Katalogs.
2. **Ablauf und Prompt des Enterprise AI Client, unverändert.** `RemoteAi.describeImage` ruft die kopierte
   `SourceImageAnalysisUseCase.describe` über `OpenAiCompatibleChatAdapter` auf, mit derselben Proxy-Route, demselben
   Vertrauen und Token wie die übrigen Aufrufe. Keine OCR (der Lesebereich des EAC beschreibt nur).
3. **Nur auf Abruf.** Anzeigen beschreibt nichts; `PageImageAnalysis.describe` (App: Knopf im Reiter, nie auf dem
   EDT; REST `POST`) lädt die Bilder der Lese-Ansicht über die Anmeldung der Quelle und beschreibt jedes, für dessen
   Bytes es mit dem Modell noch keine Beschreibung gibt. Dasselbe Bild geht je Modell nur einmal an das Modell, auch
   auf mehreren Seiten, und die Beschreibung überlebt einen Neuaufbau des Caches (Asset-Ablage, nicht Lucene).
4. **Textquelle vor Bild** (Angelo, 16.10.): Vorschaubilder von draw.io-Diagrammen und SVG werden übersprungen
   (`diagram-source`, `vector-source`), ebenso Bilder über der Asset-Grenze (`too-large`); höchstens 50 Bilder je Seite.
5. **Ergebnis als Anreicherung `images`.** Die Zuordnung Seite → Bilder (Schema `page-images/v1`: Asset-ID, SHA-256,
   Dateiname, Status `described|pending|failed|skipped`, Grund, Beschreibung, Beschreiber samt Analyseversion, Modell,
   Zeitpunkt; nie Bildbytes) liegt in der Anreicherungsablage. Der Prozessor `images` rechnet nie selbst
   (`appliesTo` ist `false`), er legt nur fest, wann das Ergebnis gilt: seine Version enthält Beschreiber und Modell,
   ein Modellwechsel macht es `STALE`, ebenso ein geänderter Seitentext. So erscheint es ohne eigenen Code im
   Analyse-Export (#359).
6. **Rechte.** Bilder gehen nur aus Quellen in `ai.external-processing-sources` an den Dienst (`NOT_RELEASED`, 403
   `image-description-not-released`), auch über die bestehende Route `…/assets/{id}/description`. Lesen braucht
   `content` wie das Bild selbst, Beschreiben zusätzlich `sync` wie das Ernten. Die Beschreibung ist maschinell
   erzeugt (`origin: machine-generated`), kein Teil der Seite.

## Folgen

- Ändert sich nur ein Bild, nicht der Seitentext, bleibt das Ergebnis `CURRENT`; der SHA-256 je Bild zeigt den Stand,
  ein neues `describe` beschreibt das neue Bild.
- Ein neuer Prompt bekommt eine neue Analyseversion (`openai-compatible-vision-v1` → `-v2`); alle Ergebnisse gelten
  dann als veraltet.
- Abweichung ohne Paritätspflicht: Beschreibung einer ganzen Seite in einem Aufruf (der EAC beschreibt im Lesebereich
  Bild für Bild); derselbe Einzelbild-Weg wird je Bild aufgerufen.

## Nachtrag 2026-10-19: Bilder gehören zur gespeicherten Fassung

Review 19.10.: `describe` las die Bilder live aus der Quelle, band das Ergebnis aber an den gespeicherten Text und
dessen Revision. War die Seite in der Quelle schon weiter, standen Bilder einer neueren Fassung als `CURRENT` am alten
Text. Jetzt holt `describe` die Lese-Ansicht wie der Export (`ExportAssetCollection.readingView`) mit ihrer Fassung;
nennt die Quelle eine andere als die gespeicherte oder keine bestätigte, beschreibt es die Bilder trotzdem (die
Beschreibung je Bildbytes und Modell bleibt nutzbar), legt aber die Warnung `page-changed` ab und `PageImages` meldet
`STALE`, bis nach dem nächsten Abgleich erneut beschrieben wird; ist der Cache älter als die Quelle, bleibt also
auch jedes erneute Beschreiben `STALE`, bis der Abgleich die Seite holt. Eine Quelle ohne Lese-Ansicht, die ihre Bilder
trotzdem kennt, liefert sie wie bisher über `viewImages`; die Fassung gilt dort als unbekannt und bleibt ungeprüft.
Kein Schemawechsel (`page-images/v1`), keine Änderung an den Asset-Klassen.
