package com.aresstack.spaceharvester.deck.files.local;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;

/** Gemeinsames der lokalen Adapter: verständliche Gründe statt Ausnahmeklassen. */
final class LocalFiles {

    private LocalFiles() {
    }

    static String reason(IOException e) {
        if (e instanceof NoSuchFileException) {
            return "Nicht (mehr) vorhanden.";
        }
        if (e instanceof AccessDeniedException) {
            return "Kein Zugriff.";
        }
        if (e instanceof FileAlreadyExistsException) {
            return "Den Namen gibt es hier schon.";
        }
        if (e instanceof DirectoryNotEmptyException) {
            return "Der Ordner ist nicht leer.";
        }
        if (e instanceof NotDirectoryException) {
            return "Das ist kein Ordner.";
        }
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
