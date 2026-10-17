package com.aresstack.spaceharvester.security;

/** Die Sicherheitskonfiguration ist unvollständig oder widersprüchlich; der Dienst startet nicht. */
public class InvalidSecurityConfigurationException extends IllegalStateException {

    InvalidSecurityConfigurationException(String message) {
        super(message);
    }
}
