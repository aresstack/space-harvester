package com.aresstack.enterpriseai.chat.openai;

import com.aresstack.enterpriseai.chat.api.ChatCompletionException;
import com.aresstack.enterpriseai.chat.api.ChatErrorKind;

/** Fehlerabbildung HTTP → {@link ChatCompletionException}, ohne Secrets und mit gekürzten Serverantworten. */
final class OpenAiErrors {

    static final int MAX_MESSAGE_LENGTH = 300;

    private OpenAiErrors() {
    }

    static ChatCompletionException forStatus(int status, String body, String token) {
        String detail = OpenAiResponseParser.errorMessage(body == null ? "" : body);
        if (detail == null) {
            detail = body == null ? "" : body;
        }
        String message = "HTTP " + status + (detail.trim().isEmpty() ? "" : ": " + shorten(redact(detail, token)));
        return new ChatCompletionException(kindOf(status), status, message, null);
    }

    static ChatErrorKind kindOf(int status) {
        if (status == 401 || status == 403) {
            return ChatErrorKind.AUTHENTICATION;
        }
        if (status == 429) {
            return ChatErrorKind.RATE_LIMITED;
        }
        if (status >= 500) {
            return ChatErrorKind.PROVIDER_ERROR;
        }
        return ChatErrorKind.INVALID_REQUEST;
    }

    /** Gleicher Fehler mit redigierter Meldung, z. B. für Fehlerobjekte, die mit HTTP 200 kommen. */
    static ChatCompletionException redacted(ChatCompletionException error, String token) {
        String message = error.getMessage();
        String clean = redact(message, token);
        if (clean == null || clean.equals(message)) {
            return error;
        }
        return new ChatCompletionException(error.kind(), error.statusCode(), clean, error.getCause());
    }

    /** Entfernt das Token, falls ein Server es zurückspiegelt. */
    static String redact(String text, String token) {
        if (text == null || token == null || token.isEmpty()) {
            return text;
        }
        return text.replace(token, "***");
    }

    static String shorten(String text) {
        String singleLine = text.replace('\r', ' ').replace('\n', ' ').trim();
        return singleLine.length() <= MAX_MESSAGE_LENGTH ? singleLine
                : singleLine.substring(0, MAX_MESSAGE_LENGTH) + "...";
    }
}
