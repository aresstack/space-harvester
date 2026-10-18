/**
 * Adapter für die interne GPT-/OpenAI-kompatible Enterprise-Chat-API (Strang A, AP3).
 *
 * <p>Öffentlich sind nur {@link com.aresstack.enterpriseai.chat.openai.OpenAiCompatibleChatAdapter}, seine
 * Konfiguration {@link com.aresstack.enterpriseai.chat.openai.OpenAiCompatibleChatConfig} und
 * {@link com.aresstack.enterpriseai.chat.openai.DeveloperRolePolicy}. JSON-DTOs, SSE-Parser und
 * HTTP-Transport sind paketintern. HTTP über {@link java.net.HttpURLConnection} aus dem JDK, JSON über Gson.
 *
 * <p>Gekapseltes, real beobachtetes Verhalten der Enterprise-API (Referenzmodell beim Test
 * {@code openai/gpt-oss-120b}); jede Zeile ist in den Tests festgehalten:
 * <ul>
 *   <li>Rollen {@code system}, {@code user}, {@code assistant} funktionieren; {@code developer} liefert HTTP 500.
 *   Der Adapter lehnt DEVELOPER standardmäßig vor dem Senden ab und wandelt nichts stillschweigend um
 *   ({@link com.aresstack.enterpriseai.chat.openai.DeveloperRolePolicy}).</li>
 *   <li>Body immer als {@code application/json; charset=utf-8}, Request und Response als UTF-8-Bytes.</li>
 *   <li>{@code max_tokens}, {@code temperature}, {@code top_p}, {@code top_k}, {@code presence_penalty},
 *   {@code frequency_penalty}, {@code user} werden nur gesendet, wenn gesetzt.</li>
 *   <li>{@code n} wird akzeptiert, aber ignoriert, und deshalb nie gesendet; gelesen wird nur {@code choices[0]}.</li>
 *   <li>{@code stop} als String wird abgelehnt, deshalb immer als Array. Die Stop-Sequenz bleibt im Text; der
 *   Adapter entfernt sie nicht.</li>
 *   <li>{@code usage.*} ist immer 0. Die Felder werden gelesen, ein reiner Null-Block gilt aber als
 *   "nicht gemeldet" und wird nie als Verbrauch interpretiert.</li>
 *   <li>{@code message.content} bzw. {@code delta.content} dürfen {@code null} sein (z. B. bei
 *   {@code finish_reason = length}); das ergibt eine leere Antwort bzw. kein Delta.</li>
 *   <li>Streaming nur über {@code "stream": true} im Body; {@code ?stream=true} wird nie verwendet.</li>
 *   <li>{@code Accept: text/event-stream} wird abgelehnt und nie gesendet; der Server liefert trotzdem
 *   {@code data:}-Zeilen, die mit {@code data: [DONE]} enden. Chunks können stark gebatcht sein, Chunks ohne
 *   Text sind normal.</li>
 * </ul>
 *
 * <p>UNVERIFIED (nicht real getestet, nur defensiv behandelt): Format von Fehler-Bodies außer HTTP 500
 * ({@code {"error":{"message":...}}} wird gelesen, sonst der gekürzte Rohtext); ob der Server bei
 * Verbindungsabbruch die Generierung beendet; {@code error}-Objekte innerhalb eines Streams; ein Stream ohne
 * {@code [DONE]}; {@code finish_reason = content_filter}; eine JSON-Antwort trotz {@code stream=true}.
 */
package com.aresstack.enterpriseai.chat.openai;
