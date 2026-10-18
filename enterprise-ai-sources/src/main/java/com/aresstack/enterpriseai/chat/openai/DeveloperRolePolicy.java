package com.aresstack.enterpriseai.chat.openai;

/**
 * Wie der Adapter Nachrichten mit Rolle {@code DEVELOPER} behandelt. Die getestete Enterprise-API lehnt
 * {@code developer} mit HTTP 500 ({@code internal_error}) ab; der Adapter setzt die Rolle deshalb nicht voraus
 * und wandelt nichts stillschweigend um.
 */
public enum DeveloperRolePolicy {
    /** Standard: Anfragen mit DEVELOPER-Nachrichten vor dem Senden als ungültig ablehnen. */
    REJECT,
    /** Ausdrücklich konfiguriert: DEVELOPER-Nachrichten als {@code system} senden. */
    SEND_AS_SYSTEM,
    /** Ausdrücklich konfiguriert: unverändert als {@code developer} senden (für Server, die die Rolle kennen; UNVERIFIED). */
    SEND_AS_DEVELOPER
}
