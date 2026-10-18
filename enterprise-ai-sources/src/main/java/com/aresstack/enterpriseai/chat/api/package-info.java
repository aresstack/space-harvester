/**
 * Port für Chat-Completions (Strang A). Keine Provider-Typen.
 *
 * <p>{@link com.aresstack.enterpriseai.chat.api.ChatCompletionPort} ist die einzige Stelle, an der
 * Application und UI mit einem Sprachmodell sprechen. Anfragen und Antworten sind die neutralen Typen aus
 * {@code com.aresstack.enterpriseai.astu.chat}; Adapter (z. B. {@code chat-openai}) übersetzen sie in ihr
 * Protokoll und halten ihre DTOs paketintern.
 */
package com.aresstack.enterpriseai.chat.api;
