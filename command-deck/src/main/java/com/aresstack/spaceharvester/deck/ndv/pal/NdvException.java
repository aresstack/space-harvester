package com.aresstack.spaceharvester.deck.ndv.pal;

/**
 * Exception for NDV (Natural Development Server) operations. Übernommen aus MainframeMate.
 */
class NdvException extends Exception {

    public NdvException(String message) {
        super(message);
    }

    public NdvException(String message, Throwable cause) {
        super(message, cause);
    }
}

