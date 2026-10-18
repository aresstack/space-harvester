package com.aresstack.enterpriseai.embedding.openai;

import java.io.IOException;

/** Der Antwort-Body ist größer als für die erwartete Vektoranzahl plausibel; er wird nicht weiter gepuffert. */
final class ResponseTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    ResponseTooLargeException(long limit) {
        super("response body exceeds " + limit + " bytes");
    }
}
