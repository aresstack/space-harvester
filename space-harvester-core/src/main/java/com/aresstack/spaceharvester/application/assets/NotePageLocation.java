package com.aresstack.spaceharvester.application.assets;

/** Identify a OneNote page within the current extracted revision, not a PDF page or a guessed source GUID. */
public record NotePageLocation(int index) {
    public NotePageLocation {
        if (index < 1) {
            throw new IllegalArgumentException("OneNote page index must be positive");
        }
    }
}

