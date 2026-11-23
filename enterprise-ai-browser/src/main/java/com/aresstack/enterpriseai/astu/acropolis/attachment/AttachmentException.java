package com.aresstack.enterpriseai.astu.acropolis.attachment;

/** Ein Anhang ließ sich nicht ablegen, finden oder lesen. Die Meldung ist für Menschen und das Modell bestimmt. */
public class AttachmentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AttachmentException(String message) {
        super(message);
    }

    public AttachmentException(String message, Throwable cause) {
        super(message, cause);
    }
}
