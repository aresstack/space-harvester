/**
 * Adapter: SharePoint-Dokumentbibliotheken als Knowledge Source.
 *
 * <p>Liest Ordner und Dateien einer SharePoint-Site über die REST-API ({@code _api/web}) und liefert den Text
 * der Dokumente über den neutralen {@code KnowledgeSourcePort}; Erkennung und Extraktion übernimmt der Port
 * {@code deigma}. Öffentlich sind nur der Quelltyp, der Credential-Callback und die kurzlebigen Anmeldedaten;
 * HTTP und JSON sind paketintern.
 *
 * <p>Übernommen und adaptiert aus Miguel0888/MainframeMate ({@code app}: {@code de.bund.zrb.sharepoint}, Site,
 * Pfadregeln und Linkauswahl). Statt WebDAV über den Windows-WebClient und PowerShell spricht der Adapter die
 * REST-API mit dem einen HTTP-Client des JDK an.
 */
package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;
