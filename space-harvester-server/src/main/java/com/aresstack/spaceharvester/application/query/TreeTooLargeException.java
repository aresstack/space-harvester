package com.aresstack.spaceharvester.application.query;

/** Der Baum eines Space überschreitet die Grenzen einer synchronen Antwort; vollständige Bäume liefert der Export. */
public class TreeTooLargeException extends RuntimeException {

    public TreeTooLargeException(String message) {
        super(message);
    }
}
