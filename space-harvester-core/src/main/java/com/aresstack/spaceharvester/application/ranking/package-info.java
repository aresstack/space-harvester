/**
 * Reranking der Suchtreffer (ADR 0004): Schnittstelle {@link com.aresstack.spaceharvester.application.ranking.SearchReranker}
 * mit No-op-Standard und der Absicherung {@link com.aresstack.spaceharvester.application.ranking.Reranking}, die nur
 * Umordnungen der bereits freigegebenen Treffer zulässt; {@link com.aresstack.spaceharvester.application.ranking.RelevanceSearch}
 * für {@code sort=relevance} mit dem Kosinus-Reranker (#89).
 */
package com.aresstack.spaceharvester.application.ranking;
