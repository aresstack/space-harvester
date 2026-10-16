/**
 * Generischer Knowledge-Source-Port (Strang E, AP11).
 *
 * <p>{@link com.aresstack.enterpriseai.source.api.KnowledgeSourcePort} macht MediaWiki, Confluence und
 * künftige Quellen über dieselbe fachliche Schnittstelle zugänglich: {@code discover(scope)},
 * {@code load(resource)}, {@code discoverLinks(resource)} und optional {@code search(query)}. Die Modelle
 * ({@code KnowledgeResource}, {@code KnowledgeDocument} ...) stammen aus {@code domain.knowledge}; hier liegen
 * nur port-spezifische Typen. Keine Wiki-, Confluence- oder HTTP-Typen.
 *
 * <p>Ein In-Memory-Fake ({@code InMemoryKnowledgeSource}) und die Vertragstests
 * ({@code KnowledgeSourceContractTest}) liegen in den Test-Fixtures dieses Moduls:
 * {@code testImplementation testFixtures(project(':source-api'))}.
 */
package com.aresstack.enterpriseai.source.api;
