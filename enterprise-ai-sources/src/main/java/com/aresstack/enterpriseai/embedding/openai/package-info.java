/**
 * Adapter für den {@code POST /embeddings}-Endpunkt der internen, OpenAI-/GPT-kompatiblen Enterprise-API
 * (Strang C, AP6).
 *
 * <p>Öffentlich sind nur {@link com.aresstack.enterpriseai.embedding.openai.OpenAiCompatibleEmbeddingAdapter},
 * seine Konfiguration, {@link com.aresstack.enterpriseai.embedding.openai.EmbeddingInputMode} und
 * {@link com.aresstack.enterpriseai.embedding.openai.BearerTokenSource}. HTTP, JSON
 * (Gson) und Wire-DTOs bleiben paketintern; nach außen gehen nur Typen aus {@code embedding-api} und
 * {@code domain.embedding}.
 *
 * <p><b>Verifikationsstand des Endpunkts</b> (verbindlicher Nachtrag, Abschnitt 17): {@code /embeddings} ist in
 * der OpenAPI dokumentiert, aber noch nicht praktisch getestet. Der Adapter setzt deshalb nur das Minimum voraus:
 * {@code model} + ein einzelner String als {@code input}, Antwort mit {@code data[].embedding} als Float-Array.
 * UNVERIFIED und daher nicht vorausgesetzt bzw. nicht gesendet: Array-Input (nur per
 * {@link com.aresstack.enterpriseai.embedding.openai.EmbeddingInputMode#ARRAY_UNVERIFIED}), {@code encoding_format}
 * / base64, {@code dimensions}, {@code user}, {@code usage}.
 */
package com.aresstack.enterpriseai.embedding.openai;
