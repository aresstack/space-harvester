package com.aresstack.spaceharvester.application.access;

/** Die Quelle konnte ein durchgereichtes Token nicht eindeutig prüfen. Die Meldung enthält nie das Token. */
public class DelegationUnavailableException extends RuntimeException {

    public DelegationUnavailableException(String sourceId) {
        super("Delegierte Anmeldung bei Quelle " + sourceId + " nicht prüfbar");
    }
}
