package com.aresstack.spaceharvester.application.standing;

/** Seite oder Aussage ist dem Aufrufer nicht als analysiert sichtbar (nicht unterscheidbar von „gibt es nicht“). */
public class UnknownStandingTargetException extends RuntimeException {

    public UnknownStandingTargetException(String message) {
        super(message);
    }
}
