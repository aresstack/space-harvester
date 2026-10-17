package com.aresstack.spaceharvester.application.jobs;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maskiert Geheimnisse in Diagnosetexten, bevor sie gespeichert oder angezeigt werden: Authorization-Werte, Tokens,
 * Passwörter und Schlüssel in {@code name=wert}-Form, lange Token-ähnliche Zeichenketten und alles an URLs außer
 * Schema, Host und Port. Pfade fallen mit weg, weil sie Seiten-IDs tragen, die ein Auftraggeber nicht lesen darf; für
 * die Fehlersuche zählen Host und Route. Lieber zu viel als zu wenig: Ein maskierter Befund ist noch lesbar, ein
 * durchgesickertes Token nicht zurückzuholen.
 */
public final class Redaction {

    static final String MASK = "***";

    /** Der Wert eines Authorization-Headers, mit oder ohne Schema, ganz. */
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(\"?(?:proxy-)?authorization\"?\\s*[:=]\\s*\"?)(?:(?:bearer|basic|token|negotiate|ntlm)\\s+)?"
                    + "[^\\s\",;&]+");
    /** {@code Bearer …}, {@code Basic …}, {@code Negotiate …}, {@code NTLM …}: jeder Wert ab acht Zeichen. */
    private static final Pattern SCHEME_VALUE = Pattern.compile(
            "(?i)\\b(bearer|basic|negotiate|ntlm)\\s+[A-Za-z0-9._~+/=-]{8,}");
    /** {@code Token …}: nur Werte mit Ziffern oder Base64-Zeichen, damit „Token abgelaufen“ lesbar bleibt. */
    private static final Pattern TOKEN_VALUE = Pattern.compile(
            "(?i)\\b(token)\\s+(?=[A-Za-z._~-]*[0-9+/=])[A-Za-z0-9._~+/=-]{8,}");
    /** {@code password=…}, {@code "token": "…"}, {@code apiKey: …} bis zum nächsten Trenner. */
    private static final Pattern NAMED_VALUE = Pattern.compile(
            "(?i)(\"?(?:pass(?:word|wd)?|pwd|secret|token|api[-_]?key|access[-_]?token|client[-_]?secret|"
                    + "authorization|cookie|set-cookie|private[-_]?key)\"?\\s*[:=]\\s*\"?)[^\\s\",;&]+");
    /** URL mit optionalen Benutzerangaben; behalten werden Schema, Host und Port. */
    private static final Pattern URL = Pattern.compile(
            "(?i)\\b([a-z][a-z0-9+.-]*://)(?:[^/@\\s\"'<>]+@)?([^/?#\\s\"'<>]+)([/?#][^\\s\"'<>]*)?");
    /** JWTs und andere lange, zufällig aussehende Zeichenketten mit Ziffern und Buchstaben. */
    private static final Pattern LONG_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_-])(?=[A-Za-z0-9_-]*[0-9])(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{32,}"
                    + "(?:\\.[A-Za-z0-9_-]{8,}){0,2}(?![A-Za-z0-9_-])");
    /** Auftrags-IDs bleiben lesbar. */
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private Redaction() {
    }

    /** Der Text mit maskierten Geheimnissen; {@code null} bleibt {@code null}. */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = AUTHORIZATION.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + MASK));
        masked = SCHEME_VALUE.matcher(masked).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + " " + MASK));
        masked = TOKEN_VALUE.matcher(masked).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + " " + MASK));
        masked = NAMED_VALUE.matcher(masked).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + MASK));
        masked = URL.matcher(masked).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + m.group(2)
                + (m.group(3) == null ? "" : "/…")));
        masked = LONG_TOKEN.matcher(masked).replaceAll(m -> UUID.matcher(m.group()).matches()
                ? Matcher.quoteReplacement(m.group()) : MASK);
        return masked;
    }
}
