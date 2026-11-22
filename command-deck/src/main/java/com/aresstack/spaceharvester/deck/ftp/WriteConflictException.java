package com.aresstack.spaceharvester.deck.ftp;

/** Die Datei wurde seit dem Lesen auf dem Host geändert; nichts wurde geschrieben. */
public final class WriteConflictException extends FtpReplyException {

    private final transient RemoteFile current;

    public WriteConflictException(RemoteFile current) {
        super("Die Datei wurde inzwischen auf dem Host geändert: " + current.path(), 0);
        this.current = current;
    }

    /** Der Stand auf dem Host. */
    public RemoteFile current() {
        return current;
    }
}
