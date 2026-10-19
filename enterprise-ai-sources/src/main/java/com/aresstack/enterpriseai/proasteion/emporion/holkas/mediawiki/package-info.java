/**
 * Adapter: MediaWiki als Knowledge Source (Strang E).
 *
 * <p>Spricht die MediaWiki-Action-API ({@code api.php}) über HTTP an, übersetzt Seiten in indexierbaren Text
 * und liefert sie über den neutralen {@code KnowledgeSourcePort}. Öffentlich sind nur die
 * Konfiguration ({@link com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki.MediaWikiSiteConfig}), der
 * Credential-Callback und der Adapter selbst; HTTP-Transport, JSON-Parsing und HTML-Verarbeitung sind
 * paketintern.
 *
 * <p>Übernommen und adaptiert aus Miguel0888/MainframeMate ({@code wiki-integration}:
 * {@code JwbfWikiContentService}, {@code HtmlPostProcessor}; {@code app}: {@code WikiSourceScanner}).
 */
package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;
