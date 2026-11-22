package com.aresstack.spaceharvester.deck.ftp;

/**
 * Ein Text lässt sich nicht im Zeichensatz der Verbindung schreiben (etwa „€“ in ISO-8859-1). Es wird nichts
 * übertragen, statt das Zeichen still durch „?“ zu ersetzen; die Verbindung bleibt stehen.
 */
public final class TextEncodingException extends FtpReplyException {

    public TextEncodingException(String message) {
        super(message, 0);
    }
}
