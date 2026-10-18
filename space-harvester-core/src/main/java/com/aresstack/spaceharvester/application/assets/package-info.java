/**
 * Asset-Ernte (#19): Anhänge und referenzierte Bilder einer Seite getrennt vom Seiten-JSON nachladen und versioniert
 * ablegen. Heruntergeladen wird nur über die Quelle selbst ({@link
 * com.aresstack.spaceharvester.application.sources.HarvestSource#attachmentFile}), also mit deren Anmeldung, Zertifikaten
 * und Proxy; fremde URLs werden nie abgerufen. Die Bytes liegen im {@link
 * com.aresstack.spaceharvester.application.assets.AssetStore} (dateibasiert, nicht im Lucene-Index), je Inhalt einmal.
 *
 * <p>Textuelle Diagrammdaten (draw.io, SVG) gehen Bildern vor: {@link
 * com.aresstack.spaceharvester.application.assets.AssetKind} ordnet die Anhänge ein, und ein Vorschaubild eines
 * draw.io-Diagramms verweist mit {@code previewOf} auf dessen Quelldaten.
 *
 * <p>Typ und Text (#183): Beim Ablegen prüft {@link com.aresstack.spaceharvester.application.assets.DocumentText} den
 * Typ am Inhalt; Dokumente bekommen ihren Text als {@link com.aresstack.spaceharvester.application.assets.AssetText},
 * je Inhalt und Extraktion abgelegt.
 */
package com.aresstack.spaceharvester.application.assets;
