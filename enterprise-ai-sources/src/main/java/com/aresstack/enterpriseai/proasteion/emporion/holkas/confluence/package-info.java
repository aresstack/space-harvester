/**
 * Adapter: Confluence Data Center als Knowledge Source (Strang F, AP15).
 *
 * <p>Einstieg ist {@link com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceKnowledgeSource}; HTTP läuft über
 * die Naht {@link com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceHttpTransport} (produktiv
 * {@link com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.UrlConnectionConfluenceTransport}, Proxy und mTLS von außen),
 * Anmeldedaten ausschließlich über {@code adyton}. Fachlich übernommen aus MainframeMate
 * {@code ConfluenceRestClient}.
 */
package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;
