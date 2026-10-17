/**
 * Exporte eines Space aus dem Cache als Auftrag (#11, #12): liest über den Cache-Port
 * {@link com.aresstack.spaceharvester.application.query.PageCacheReader} aus genau einer Generation und schreibt über
 * {@link com.aresstack.spaceharvester.application.export.ExportArtifactWriter}. Ein Artefakt besteht aus einem flachen
 * Seiten-Array, einem getrennten Baum nur aus IDs, den nativen Payloads Byte für Byte und einem Manifest; es wird erst
 * veröffentlicht, wenn alles geschrieben ist.
 */
package com.aresstack.spaceharvester.application.export;
