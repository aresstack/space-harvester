package com.aresstack.spaceharvester.api;

/** Der eigene Auftrag hat kein Diagnoseereignis mit dieser Nummer (mehr). */
class EventNotFoundException extends RuntimeException {

    EventNotFoundException() {
        super("Diagnoseereignis nicht gefunden");
    }
}
