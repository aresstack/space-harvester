package com.aresstack.spaceharvester.application.sources;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Ziel eines Anhangs, der als Strom kommt ({@link HarvestSource#streamAttachment}): Die Originalbytes fließen hierher,
 * etwa in eine Datei der Ablage, statt ganz in den Speicher (Angelo 11.11.2026, Dateien beliebiger Größe). Ein
 * Neuversuch der Quelle beginnt mit {@link #restart()} von vorn, damit nie zwei Versuche in derselben Datei landen.
 */
public abstract class AttachmentSink extends OutputStream {

    /** Verwirft alles bisher Geschriebene; der nächste Versuch schreibt den Anhang von vorn. */
    public abstract void restart() throws IOException;

    /** Größte Datei, die der Aufrufer annimmt; darüber scheitert {@link #write(byte[], int, int)}, nichts wird gekürzt. */
    public abstract long maxBytes();

    /** Ob ein Schreiben an {@link #maxBytes()} scheiterte: dann ist der Anhang zu groß, die Quelle nicht gestört. */
    public abstract boolean exceeded();
}
