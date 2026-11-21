/**
 * Adapter: Jira Data Center als Knowledge Source (nur lesend, REST-API 2).
 *
 * <p>Einstieg ist {@link com.aresstack.enterpriseai.proasteion.emporion.holkas.jira.JiraKnowledgeSource}; HTTP läuft über
 * die Naht {@link com.aresstack.enterpriseai.proasteion.emporion.holkas.jira.JiraHttpTransport} (produktiv
 * {@link com.aresstack.enterpriseai.proasteion.emporion.holkas.jira.UrlConnectionJiraTransport}, Proxy und mTLS von außen),
 * Anmeldedaten ausschließlich über {@code adyton}. Aufbau wie {@code source-confluence}: Projekte sind die Partitionen
 * (wie Spaces), Vorgänge die Ressourcen (wie Seiten), JQL die Suche (wie CQL).
 */
package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;
