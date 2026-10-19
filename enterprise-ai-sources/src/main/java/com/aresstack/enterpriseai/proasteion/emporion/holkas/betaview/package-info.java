/**
 * Adapter: BetaView-Dokumentarchiv als Knowledge Source.
 *
 * <p>Meldet sich an der BetaView-Weboberfläche an (Struts-Formular, CSRF-Token aus {@code getAppContext.action}),
 * führt die Auswahl eines Favoriten aus und liefert die gefundenen Dokumente über den neutralen
 * {@code KnowledgeSourcePort}; den Text der heruntergeladenen Dokumente erkennt und extrahiert der Port
 * {@code deigma}. Öffentlich sind nur der Quelltyp, der Credential-Callback und die kurzlebigen Anmeldedaten;
 * HTTP, Sitzungs-Cookies und HTML sind paketintern.
 *
 * <p>Übernommen und adaptiert aus Miguel0888/MainframeMate ({@code betaview-integration}:
 * {@code BetaViewHttpClient}, {@code LoadResultsHtmlUseCase}, {@code ResultFilter},
 * {@code DownloadDocumentUseCase}, {@code DocumentTabParser}, {@code HtmlResultsTableModel},
 * {@code HiddenInputExtractor}, {@code AppContextCsrfTokenParser}).
 */
package com.aresstack.enterpriseai.proasteion.emporion.holkas.betaview;
