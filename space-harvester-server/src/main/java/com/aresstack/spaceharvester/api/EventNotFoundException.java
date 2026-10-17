package com.aresstack.spaceharvester.api;

/** Der eigene Auftrag hat kein Diagnoseereignis mit dieser Nummer (mehr). */
class EventNotFoundException extends RuntimeException {

    EventNotFoundException() {
        super("Diagnoseereignis nicht gefunden");
    }

    /**
     * Die Ereignisnummer aus dem Pfad; erst nach der Prüfung des Auftrags gelesen, damit eine ungültige Nummer nichts
     * über fremde Aufträge verrät.
     */
    static long sequence(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new EventNotFoundException();
        }
    }
}
