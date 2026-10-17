/**
 * Vorbereitung für Embeddings (ADR 0004): ein {@link com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor}
 * über einem austauschbaren {@link com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider}.
 * Ohne konfigurierten Provider wird nichts registriert und kein Modell aufgerufen.
 */
package com.aresstack.spaceharvester.application.enrichment.embedding;
